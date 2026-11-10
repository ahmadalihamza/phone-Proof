"""Transactional payment orchestration shared by callbacks, status checks and jobs."""
import hashlib
from datetime import timedelta
from decimal import Decimal

from fastapi import HTTPException
from fastapi.responses import JSONResponse
from sqlalchemy import select

from .auth import as_utc
from .audit import audit
from .billing import (Plan, Transaction, Subscription, PaymentAttempt, PaymentEvent,
                      PaymentReceipt, Usage, lock_account, subscription, serialize_transaction)
from .db import begin_write, utcnow
from .inspections import owned
from .models import User
from .payment_providers import (PaymentRequest, ProviderUnavailable, InvalidWebhook, get_provider)


def create_checkout(db, user, data, renewal_of=None):
    lock_account(db, user)
    adapter = get_provider(data.provider, db)
    inspection_id = str(data.inspection_id) if data.inspection_id else None
    old = db.scalar(select(Transaction).where(
        Transaction.user_id == user.id, Transaction.idempotency_key == data.idempotency_key))
    if old:
        if (old.plan_id != data.plan_id or old.inspection_id != inspection_id or old.provider != data.provider
                or (old.plan_snapshot or {}).get('renewal_of') != renewal_of):
            raise HTTPException(409, 'Checkout idempotency conflict')
        if old.status == 'PENDING' and not old.provider_reference:
            initiate_checkout(db, user, old, adapter)
        return serialize_transaction(old)
    plan = db.get(Plan, data.plan_id)
    if not plan or not plan.active:
        raise HTTPException(422, 'Plan unavailable')
    if not plan.period_days and not inspection_id:
        raise HTTPException(422, 'Report purchase requires an inspection')
    if plan.period_days and inspection_id:
        raise HTTPException(422, 'Subscription purchase cannot be bound to one inspection')
    if inspection_id:
        owned(db, user, inspection_id)
        used = db.get(Usage, inspection_id)
        paid_or_pending = db.scalar(select(Transaction).where(Transaction.user_id == user.id,
            Transaction.inspection_id == inspection_id, Transaction.status.in_(['PENDING', 'PAID'])))
        if used or paid_or_pending:
            raise HTTPException(409, 'This report already has an entitlement or pending purchase')
    if plan.period_days:
        active = subscription(db, user)
        pending = db.scalar(select(Transaction).where(Transaction.user_id == user.id,
            Transaction.kind == 'SUBSCRIPTION_PURCHASE', Transaction.status == 'PENDING'))
        if active or pending:
            raise HTTPException(409, 'An active subscription or pending subscription purchase already exists')
    snapshot = {'version': 1, 'id': plan.id, 'name': plan.name, 'price': str(plan.price),
                'currency': plan.currency, 'report_limit': plan.report_limit,
                'period_days': plan.period_days, 'renewal_of': renewal_of}
    transaction = Transaction(user_id=user.id, idempotency_key=data.idempotency_key,
        kind='SUBSCRIPTION_PURCHASE' if plan.period_days else 'REPORT_PURCHASE',
        plan_id=plan.id, inspection_id=inspection_id, amount=plan.price, currency=plan.currency,
        provider=data.provider, plan_snapshot=snapshot, fulfillment_status='PENDING')
    db.add(transaction)
    db.flush()
    attempt = PaymentAttempt(transaction_id=transaction.id, provider=data.provider)
    db.add(attempt)
    audit(db, user.id, 'CHECKOUT_CREATED', transaction.inspection_id,
          {'transaction_id': transaction.id, 'amount': str(transaction.amount), 'provider': transaction.provider})
    initiate_checkout(db, user, transaction, adapter)
    return serialize_transaction(transaction)


def initiate_checkout(db, user, transaction, adapter):
    # This pending intent is the durable outbox: a crash at ANY later point leaves
    # the client key, original purchase terms and provider idempotency key intact.
    # The reconciliation worker can initiate it even if this request never returns.
    # This is deliberately an explicit checkout-only commit, not a change to the
    # normal request commit/rollback contract in get_db().
    db.commit()
    begin_write(db)
    lock_account(db, user)
    db.refresh(transaction)
    # A worker/second request may have completed initiation while we reacquired
    # the account lock. Never overwrite its confirmed provider reference.
    if transaction.status != 'PENDING' or transaction.provider_reference:
        return
    attempt = db.scalar(select(PaymentAttempt).where(PaymentAttempt.transaction_id == transaction.id))
    if not attempt:
        # Historical purchases have separate review/recovery rules.
        return
    snapshot = transaction.plan_snapshot or {}
    try:
        reference = adapter.create_payment(PaymentRequest(transaction.id, transaction.amount,
            transaction.currency, snapshot.get('name', transaction.plan_id)))
        if not isinstance(reference, str) or not 1 <= len(reference) <= 100:
            raise ProviderUnavailable('Invalid provider reference')
        transaction.provider_reference = reference
        attempt.provider_reference = reference
        attempt.last_error = None
    except ProviderUnavailable:
        # Do not roll back a possibly accepted payment initiation. The worker
        # retries creation with the SAME transaction ID, then verifies settlement.
        attempt.last_error = 'PROVIDER_UNAVAILABLE'
    db.flush()


def ensure_development_attempt(db, transaction):
    """Recover pre-migration simulated attempts without inventing historical terms."""
    attempt = db.scalar(select(PaymentAttempt).where(PaymentAttempt.transaction_id == transaction.id))
    if not attempt:
        reference = transaction.provider_reference or 'development-' + transaction.id
        transaction.provider_reference = reference
        attempt = PaymentAttempt(transaction_id=transaction.id, provider='development', provider_reference=reference)
        db.add(attempt)
        db.flush()
    return attempt


def issue_receipt(db, transaction):
    if db.scalar(select(PaymentReceipt).where(PaymentReceipt.transaction_id == transaction.id)):
        return
    user = db.get(User, transaction.user_id)
    snapshot = transaction.plan_snapshot or {}
    document = {'version': 1, 'transaction_id': transaction.id, 'customer': user.email,
        'description': snapshot.get('name', transaction.plan_id), 'plan': snapshot,
        'amount': str(transaction.amount), 'currency': transaction.currency,
        'payment_method': transaction.provider, 'provider_reference': transaction.provider_reference,
        'paid_at': as_utc(transaction.paid_at).isoformat(), 'inspection_id': transaction.inspection_id,
        'development': transaction.provider == 'development', 'document_type': 'PAYMENT_RECEIPT',
        'tax_invoice': False}
    db.add(PaymentReceipt(transaction_id=transaction.id, document=document))


def fulfill(db, transaction):
    snapshot = transaction.plan_snapshot
    user = db.get(User, transaction.user_id)
    if not snapshot:
        # Old rows lack a terms snapshot. Current mutable plan values cannot
        # establish what the customer bought at the historical checkout.
        transaction.fulfillment_status = 'REVIEW_REQUIRED'
    elif transaction.kind == 'SUBSCRIPTION_PURCHASE':
        if subscription(db, user):
            transaction.fulfillment_status = 'REVIEW_REQUIRED'
        else:
            now = utcnow()
            db.add(Subscription(user_id=user.id, plan_id=transaction.plan_id, transaction_id=transaction.id,
                current_period_start=now, current_period_end=now + timedelta(days=snapshot['period_days']),
                report_limit=snapshot['report_limit']))
            transaction.fulfillment_status = 'FULFILLED'
    else:
        transaction.fulfillment_status = 'FULFILLED'
    if transaction.fulfillment_status == 'REVIEW_REQUIRED':
        audit(db, user.id, 'PAYMENT_FULFILLMENT_REVIEW_REQUIRED', transaction.inspection_id,
              {'transaction_id': transaction.id})


def reconcile_transaction(db, transaction, kind='RECONCILIATION', *, event_id=None, payload_hash=None):
    """Caller holds the account lock. A callback is never sufficient proof of payment."""
    previous = transaction.status
    if previous != 'PENDING':
        return
    attempt = db.scalar(select(PaymentAttempt).where(PaymentAttempt.transaction_id == transaction.id))
    if not attempt:
        if transaction.provider == 'development':
            attempt = ensure_development_attempt(db, transaction)
        else:
            attempt = PaymentAttempt(transaction_id=transaction.id,provider=transaction.provider,
                provider_reference=transaction.provider_reference,last_error='LEGACY_ATTEMPT_UNAVAILABLE',last_checked_at=utcnow())
            db.add(attempt)
            return
    if attempt.last_error=='LEGACY_ATTEMPT_UNAVAILABLE':
        attempt.last_checked_at=utcnow()
        return
    attempt.verification_count += 1
    attempt.last_checked_at = utcnow()
    try:
        adapter = get_provider(transaction.provider, db)
    except HTTPException as error:
        if error.status_code != 503:raise
        attempt.last_error = 'PROVIDER_UNCONFIGURED'
        return
    try:
        if not attempt.provider_reference:
            snapshot = transaction.plan_snapshot or {}
            reference = adapter.create_payment(PaymentRequest(transaction.id, transaction.amount,
                transaction.currency, snapshot.get('name', transaction.plan_id)))
            if not isinstance(reference, str) or not 1 <= len(reference) <= 100:
                raise ProviderUnavailable('Invalid provider reference')
            attempt.provider_reference = reference
            transaction.provider_reference = reference
            db.flush()
        observation = adapter.verify_payment(attempt.provider_reference)
    except ProviderUnavailable:
        attempt.last_error = 'PROVIDER_UNAVAILABLE'
        return
    if (observation.reference != attempt.provider_reference or observation.transaction_id != transaction.id
            or not isinstance(observation.amount, Decimal) or not observation.amount.is_finite()
            or observation.amount != transaction.amount or observation.currency != transaction.currency
            or not isinstance(observation.status, str) or observation.status not in {'PAID', 'FAILED', 'EXPIRED', 'PENDING'}):
        attempt.last_error = 'SETTLEMENT_MISMATCH'
        audit(db, transaction.user_id, 'PAYMENT_SETTLEMENT_REJECTED', transaction.inspection_id,
              {'transaction_id': transaction.id})
        return
    attempt.last_error = None
    attempt.status = observation.status
    if observation.status == 'PENDING':
        return
    transaction.status = observation.status
    if observation.status == 'PAID':
        transaction.paid_at = utcnow()
        fulfill(db, transaction)
        issue_receipt(db, transaction)
    else:
        transaction.fulfillment_status = 'NOT_APPLICABLE'
    db.add(PaymentEvent(transaction_id=transaction.id, provider=transaction.provider, event_id=event_id,
        payload_hash=payload_hash, kind=kind, previous_status=previous, status=transaction.status))
    audit(db, transaction.user_id, 'PAYMENT_' + transaction.status, transaction.inspection_id,
          {'transaction_id': transaction.id, 'provider': transaction.provider})
    db.flush()


def process_webhook(db, provider, payload, headers):
    adapter = get_provider(provider, db)
    if len(payload) > 65536:
        raise HTTPException(413, 'Webhook too large')
    try:
        event = adapter.verify_webhook(payload, headers)
        if (not isinstance(event.event_id, str) or not 1 <= len(event.event_id) <= 100
                or not isinstance(event.reference, str) or not 1 <= len(event.reference) <= 100
                or not event.occurred_at.tzinfo):
            raise InvalidWebhook('Invalid event')
    except (InvalidWebhook, ValueError, TypeError, AttributeError):
        raise HTTPException(401, 'Invalid payment webhook') from None
    except ProviderUnavailable:
        raise HTTPException(503, 'Payment provider verification unavailable') from None
    digest = hashlib.sha256(payload).hexdigest()
    transaction = db.get(Transaction, event.transaction_id)
    if not transaction or transaction.provider != provider:
        raise HTTPException(404, 'Payment transaction unavailable')
    lock_account(db, db.get(User, transaction.user_id))
    db.refresh(transaction)
    old = db.scalar(select(PaymentEvent).where(PaymentEvent.provider == provider, PaymentEvent.event_id == event.event_id))
    if old:
        if old.payload_hash != digest or old.transaction_id != transaction.id:
            raise HTTPException(409, 'Webhook event conflict')
        return {'received': True, 'duplicate': True}
    if abs((utcnow() - as_utc(event.occurred_at)).total_seconds()) > 300:
        raise HTTPException(401, 'Expired payment webhook')
    if event.reference != transaction.provider_reference:
        raise HTTPException(409, 'Payment reference mismatch')
    previous = transaction.status
    reconcile_transaction(db, transaction, 'WEBHOOK', event_id=event.event_id, payload_hash=digest)
    attempt = db.scalar(select(PaymentAttempt).where(PaymentAttempt.transaction_id == transaction.id))
    if (attempt and attempt.last_error) or transaction.status == 'PENDING':
        # Commit rejection/attempt metadata. A retry with this event ID will be
        # independently verified again; a failed verification is never deduplicated.
        return JSONResponse({'received': True, 'verified': False, 'retry_required': True},status_code=503)
    if transaction.status == previous:
        db.add(PaymentEvent(transaction_id=transaction.id, provider=provider, event_id=event.event_id,
            payload_hash=digest, kind='WEBHOOK_OBSERVED', previous_status=previous, status=transaction.status))
    return {'received': True, 'verified': True, 'status': transaction.status}


def settle_refund(db, refund, transaction, actor_id):
    if refund.status == 'REFUNDED':
        return
    if refund.status != 'REQUESTED' or transaction.status != 'PAID':
        raise HTTPException(409, 'Refund is not awaiting approval')
    purchased = db.scalar(select(Subscription).where(Subscription.transaction_id == transaction.id))
    if transaction.kind == 'SUBSCRIPTION_PURCHASE' and not purchased and transaction.fulfillment_status != 'REVIEW_REQUIRED':
        raise HTTPException(409, 'Subscription linkage requires administrative review before refund settlement')
    adapter = get_provider(transaction.provider, db)
    try:
        reference = adapter.refund_payment(transaction.provider_reference, refund.amount, transaction.currency, refund.id)
        if not isinstance(reference, str) or not 1 <= len(reference) <= 100:
            raise ProviderUnavailable('Refund settlement not confirmed')
    except ProviderUnavailable:
        raise HTTPException(503, 'Refund settlement uncertain; retry with the same refund ID') from None
    refund.status = 'REFUNDED'
    refund.provider_refund_id = reference
    refund.completed_at = utcnow()
    transaction.status = 'REFUNDED'
    transaction.fulfillment_status = 'REFUNDED'
    # A refund must never suspend a different, subsequently purchased cycle.
    if purchased:
        purchased.status = 'SUSPENDED'
    db.add(PaymentEvent(transaction_id=transaction.id, provider=transaction.provider,
        kind='REFUND', previous_status='PAID', status='REFUNDED'))
    audit(db, actor_id, 'REFUND_SETTLED', transaction.inspection_id, {'refund_id': refund.id})


def pending_transaction_ids(db, limit=100):
    return list(db.scalars(select(Transaction.id).outerjoin(PaymentAttempt,PaymentAttempt.transaction_id==Transaction.id)
        .where(Transaction.status == 'PENDING')
        .order_by(PaymentAttempt.last_checked_at.asc().nulls_first(),Transaction.created_at).limit(limit)))


def reconcile_pending(db, limit=100):
    """Bounded batch; caller commits. The CLI uses a transaction per candidate."""
    ids = pending_transaction_ids(db,limit)
    checked = 0
    for transaction_id in ids:
        transaction = db.get(Transaction, transaction_id)
        lock_account(db, db.get(User, transaction.user_id))
        db.refresh(transaction)
        try:
            reconcile_transaction(db, transaction)
        except HTTPException as error:
            if error.status_code != 503:
                raise
        checked += 1
    return checked
