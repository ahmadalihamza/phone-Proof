"""External provider side effects survive request rollback through a durable intent."""
from decimal import Decimal
from uuid import uuid4

import pytest
from sqlalchemy import func, select

from app import payments
from app.billing import Checkout, PaymentAttempt, Plan, Transaction
from app.db import SessionLocal
from app.models import Audit, User
from app.payment_providers import PaymentObservation


class StatefulGateway:
    def __init__(self):
        self.calls = []
        self.sessions = {}
        self.crash_after_acceptance = False

    def create_payment(self, request):
        # A separate connection must see the intent before any external effect.
        with SessionLocal() as observer:
            assert observer.get(Transaction, request.transaction_id) is not None
            assert observer.scalar(select(PaymentAttempt).where(
                PaymentAttempt.transaction_id == request.transaction_id)) is not None
        self.calls.append(request)
        reference = self.sessions.setdefault(request.transaction_id, 'fixture-' + request.transaction_id)
        if self.crash_after_acceptance:
            raise RuntimeError('Synthetic process failure after external acceptance')
        return reference

    def verify_payment(self, reference):
        request = next(call for call in self.calls if 'fixture-' + call.transaction_id == reference)
        return PaymentObservation(reference, request.transaction_id, request.amount, request.currency, 'PAID')


@pytest.mark.parametrize('crash', [False, True])
def test_provider_acceptance_then_rollback_retains_client_identity_and_terms(client, user, monkeypatch, crash):
    gateway = StatefulGateway()
    gateway.crash_after_acceptance = crash
    monkeypatch.setattr(payments, 'get_provider', lambda name, db: gateway)
    data = Checkout(idempotency_key=str(uuid4()), plan_id='dealer', provider='fixture')
    with SessionLocal() as db:
        account = db.scalar(select(User))
        if crash:
            with pytest.raises(RuntimeError, match='after external acceptance'):
                payments.create_checkout(db, account, data)
        else:
            payments.create_checkout(db, account, data)
        db.rollback()  # Includes the provider reference/result, but never the intent.
    with SessionLocal() as db:
        record = db.scalar(select(Transaction))
        assert record.provider_reference is None
        stable_id = record.id
        plan = db.get(Plan, 'dealer')
        plan.price = Decimal('9000.00')
        plan.report_limit = 999
        plan.active = False
        db.commit()
    gateway.crash_after_acceptance = False
    with SessionLocal() as db:
        result = payments.create_checkout(db, db.scalar(select(User)), data)
        db.commit()
    assert result['id'] == stable_id
    assert result['provider_reference'] == 'fixture-' + stable_id
    assert result['amount'] == '2999.00'
    assert result['plan_snapshot']['report_limit'] == 30
    assert [call.transaction_id for call in gateway.calls] == [stable_id, stable_id]
    assert len(gateway.sessions) == 1
    with SessionLocal() as db:
        assert db.scalar(select(func.count()).select_from(Transaction)) == 1
        assert db.scalar(select(func.count()).select_from(Audit).where(Audit.event == 'CHECKOUT_CREATED')) == 1


def test_checkpoint_failure_never_contacts_provider(client, user, monkeypatch):
    gateway = StatefulGateway()
    monkeypatch.setattr(payments, 'get_provider', lambda name, db: gateway)
    with SessionLocal() as db:
        account = db.scalar(select(User))
        def fail_commit():
            raise RuntimeError('Synthetic durable storage failure')
        monkeypatch.setattr(db, 'commit', fail_commit)
        with pytest.raises(RuntimeError, match='durable storage'):
            payments.create_checkout(db, account, Checkout(
                idempotency_key=str(uuid4()), plan_id='dealer', provider='fixture'))
        db.rollback()
    assert gateway.calls == []


def test_reconciliation_recovers_accepted_intent_without_client_retry(client, user, monkeypatch):
    gateway = StatefulGateway()
    gateway.crash_after_acceptance = True
    monkeypatch.setattr(payments, 'get_provider', lambda name, db: gateway)
    with SessionLocal() as db:
        with pytest.raises(RuntimeError, match='after external acceptance'):
            payments.create_checkout(db, db.scalar(select(User)), Checkout(
                idempotency_key=str(uuid4()), plan_id='dealer', provider='fixture'))
        db.rollback()
    gateway.crash_after_acceptance = False
    with SessionLocal() as db:
        assert payments.reconcile_pending(db) == 1
        db.commit()
    assert len(gateway.sessions) == 1
    assert len({request.transaction_id for request in gateway.calls}) == 1
    with SessionLocal() as db:
        transaction = db.scalar(select(Transaction))
        assert transaction.status == 'PAID' and transaction.fulfillment_status == 'FULFILLED'
