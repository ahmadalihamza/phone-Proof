"""Commercial acceptance uses explicit simulations; no real money or gateway is claimed."""
import io
import uuid
from dataclasses import replace
from datetime import timedelta
from decimal import Decimal

import pytest
from pypdf import PdfReader
from sqlalchemy import select, func

from app import payments
from app.auth import require_admin
from app.billing import (Plan, Transaction, Subscription, PaymentAttempt, PaymentEvent,
                         PaymentReceipt, Usage)
from app.db import SessionLocal, utcnow
from app.main import app
from app.models import User
from app.payment_providers import PaymentObservation, VerifiedWebhook, ProviderUnavailable, InvalidWebhook
from .test_platform import API, create


def checkout(client, user, plan='dealer', **kwargs):
    response = client.post(API+'/payments/create', headers=user, json={
        'idempotency_key': str(uuid.uuid4()), 'provider': 'development', 'plan_id': plan, **kwargs})
    assert response.status_code == 200, response.text
    return response.json()


def settle(client, user, transaction, outcome='PAID'):
    response = client.post(f'{API}/payments/{transaction["id"]}/development-settle',
                           headers=user, json={'outcome': outcome})
    assert response.status_code == 200, response.text
    return response.json()


def expire_subscription():
    with SessionLocal() as db:
        sub = db.scalar(select(Subscription).where(Subscription.status == 'ACTIVE'))
        sub.current_period_end = utcnow() - timedelta(seconds=1)
        sub_id = sub.id
        db.commit()
        return sub_id


@pytest.fixture
def billing_admin(user):
    with SessionLocal() as db:
        account = db.scalar(select(User))
    app.dependency_overrides[require_admin] = lambda: account
    yield user
    app.dependency_overrides.pop(require_admin, None)


def test_plan_terms_cannot_change_after_checkout_or_retry(client, user):
    key = str(uuid.uuid4())
    tx = checkout(client, user, idempotency_key=key)
    with SessionLocal() as db:
        p = db.get(Plan, 'dealer')
        p.price=Decimal('9999.00');p.report_limit=999;p.period_days=1;p.active=False
        db.commit()
    retried = checkout(client, user, idempotency_key=key)
    assert retried == tx
    paid = settle(client, user, tx)
    assert paid['amount'] == '2999.00'
    sub = client.get(API+'/subscriptions/current', headers=user).json()
    assert sub['report_limit'] == 30
    from datetime import datetime
    assert datetime.fromisoformat(sub['current_period_end']) - datetime.fromisoformat(sub['current_period_start']) == timedelta(days=30)
    with SessionLocal() as db:
        transaction = db.get(Transaction, tx['id'])
        transaction.amount = Decimal('0.01')
        with pytest.raises(ValueError, match='Financial purchase terms'):
            db.commit()


def test_double_checkout_and_idempotency_conflicts_are_rejected(client, user):
    key=str(uuid.uuid4());checkout(client,user,idempotency_key=key)
    for body in [dict(idempotency_key=key,plan_id='report'),dict(idempotency_key=str(uuid.uuid4()),plan_id='dealer')]:
        response=client.post(API+'/payments/create',headers=user,json={'provider':'development',**body})
        assert response.status_code==409,response.text


def test_report_cannot_be_purchased_twice_and_failed_checkout_can_retry(client, user):
    inspection,_=create(client,user)
    tx=checkout(client,user,'report',inspection_id=inspection)
    body={'provider':'development','idempotency_key':str(uuid.uuid4()),'plan_id':'report','inspection_id':inspection}
    assert client.post(API+'/payments/create',headers=user,json=body).status_code==409
    settle(client,user,tx,'FAILED')
    retry=checkout(client,user,'report',inspection_id=inspection)
    settle(client,user,retry)
    assert client.post(API+'/payments/create',headers=user,json=body).status_code==409
    assert client.get(f'{API}/inspections/{inspection}/entitlement',headers=user).json()['source']=='purchase'


def test_receipt_is_private_immutable_and_not_a_tax_invoice(client, user, tmp_path):
    tx=checkout(client,user)
    assert client.get(f'{API}/transactions/{tx["id"]}/receipt/pdf',headers=user).status_code==409
    settle(client,user,tx);settle(client,user,tx)
    receipt=client.get(f'{API}/transactions/{tx["id"]}/receipt',headers=user).json()
    assert receipt['document']['amount']=='2999.00'
    assert receipt['document']['tax_invoice'] is False
    pdf=client.get(f'{API}/transactions/{tx["id"]}/receipt/pdf',headers=user)
    assert pdf.status_code==200 and pdf.headers['cache-control']=='no-store'
    text=''.join(p.extract_text() for p in PdfReader(io.BytesIO(pdf.content)).pages)
    assert tx['id'] in text and 'NOT REAL MONEY' in text and 'not a tax invoice' in text
    assert client.get(f'{API}/transactions/{tx["id"]}/receipt/pdf').status_code==401
    other=client.post(API+'/auth/register',json={'email':'other-billing@example.com','password':'Other-PhoneProof-2026'}).json()
    headers={'Authorization':'Bearer '+other['access_token']}
    assert client.get(f'{API}/transactions/{tx["id"]}/receipt/pdf',headers=headers).status_code==404
    with SessionLocal() as db:
        assert db.scalar(select(func.count()).select_from(PaymentReceipt))==1
        item=db.scalar(select(PaymentReceipt));item.document={'amount':'forged'}
        with pytest.raises(ValueError,match='Append-only'):db.commit()
    from pathlib import Path
    output=Path(__file__).resolve().parents[2]/'output/pdf/billing-development-receipt.pdf'
    output.parent.mkdir(parents=True,exist_ok=True);output.write_bytes(pdf.content)


def test_manual_renewal_uses_scheduled_plan_and_new_cycle_quota(client,user):
    first=checkout(client,user);settle(client,user,first)
    with SessionLocal() as db:
        db.add(Plan(id='starter',name='Starter fixture',price=Decimal('100.00'),currency='PKR',period_days=7,report_limit=5));db.commit()
    sub=client.get(API+'/subscriptions/current',headers=user).json()
    renewal={'idempotency_key':str(uuid.uuid4()),'provider':'development'}
    assert client.post(f'{API}/subscriptions/{sub["id"]}/renew',headers=user,json=renewal).status_code==409
    assert client.post(f'{API}/subscriptions/{sub["id"]}/change-plan',headers=user,json={'plan_id':'starter'}).status_code==200
    expire_subscription()
    assert client.get(API+'/subscriptions/current',headers=user).json() is None
    response=client.post(f'{API}/subscriptions/{sub["id"]}/renew',headers=user,json=renewal)
    assert response.status_code==200,response.text
    tx=response.json();assert tx['plan_id']=='starter'
    settle(client,user,tx)
    assert client.post(f'{API}/subscriptions/{sub["id"]}/renew',headers=user,json=renewal).json()['id']==tx['id']
    new=client.get(API+'/subscriptions/current',headers=user).json()
    assert new['id']!=sub['id'] and new['report_limit']==5
    assert client.get(API+'/billing/entitlements',headers=user).json()['remaining']==5
    assert len(client.get(API+'/subscriptions/history',headers=user).json())==2


def test_cancel_clears_plan_change_and_expiry_retains_consumed_reports(client,user):
    tx=checkout(client,user);settle(client,user,tx)
    sub=client.get(API+'/subscriptions/current',headers=user).json()
    assert client.post(f'{API}/subscriptions/{sub["id"]}/change-plan',headers=user,json={'plan_id':'dealer'}).status_code==200
    client.post(f'{API}/subscriptions/{sub["id"]}/cancel',headers=user)
    current=client.get(API+'/subscriptions/current',headers=user).json()
    assert current['next_plan_id'] is None and current['cancel_at_period_end']
    assert client.post(f'{API}/subscriptions/{sub["id"]}/change-plan',headers=user,json={'plan_id':'dealer'}).status_code==409
    inspection,_=create(client,user)
    with SessionLocal() as db:
        account=db.scalar(select(User))
        db.add(Usage(inspection_id=inspection,user_id=account.id,source='subscription',subscription_id=sub['id']))
        db.commit()
    expire_subscription()
    assert client.get(API+'/subscriptions/current',headers=user).json() is None
    assert client.get(API+'/subscriptions/history',headers=user).json()[0]['status']=='CANCELLED'
    assert client.get(f'{API}/inspections/{inspection}/entitlement',headers=user).json()['can_generate_verified_report']


def test_refund_is_idempotent_and_does_not_suspend_later_cycle(client,user,billing_admin):
    first=checkout(client,user);settle(client,user,first);expire_subscription()
    second=checkout(client,user);settle(client,user,second)
    response=client.post(f'{API}/transactions/{first["id"]}/refund',headers=user,json={'reason':'Billing acceptance fixture'})
    refund=response.json()
    for _ in range(2):
        result=client.post(f'{API}/admin/refunds/{refund["id"]}/approve',headers=billing_admin)
        assert result.status_code==200,result.text
    current=client.get(API+'/subscriptions/current',headers=user).json()
    assert current is not None and current['status']=='ACTIVE'
    detail=client.get(f'{API}/transactions/{first["id"]}',headers=user).json()
    assert detail['status']=='REFUNDED' and detail['refund']['status']=='REFUNDED' and detail['receipt_available']
    with SessionLocal() as db:
        assert db.scalar(select(func.count()).select_from(PaymentEvent).where(PaymentEvent.kind=='REFUND'))==1


def test_refunded_active_cycle_loses_future_quota_and_rejected_refund_does_not(client,user,billing_admin,monkeypatch):
    from app.config import settings
    monkeypatch.setattr(settings,'trial_report_limit',0)
    tx=checkout(client,user);settle(client,user,tx)
    refund=client.post(f'{API}/transactions/{tx["id"]}/refund',headers=user,json={'reason':'Billing acceptance fixture'}).json()
    assert client.post(f'{API}/admin/refunds/{refund["id"]}/approve',headers=user).status_code==200
    assert not client.get(API+'/billing/entitlements',headers=user).json()['can_generate_verified_report']
    new=checkout(client,user);settle(client,user,new)
    request=client.post(f'{API}/transactions/{new["id"]}/refund',headers=user,json={'reason':'Billing acceptance fixture'}).json()
    assert client.post(f'{API}/admin/refunds/{request["id"]}/reject',headers=user,json={'reason':'Report service was delivered'}).status_code==200
    assert client.get(API+'/billing/entitlements',headers=user).json()['source']=='subscription'
    assert client.post(f'{API}/admin/refunds/{request["id"]}/approve',headers=user).status_code==409


class GatewayFixture:
    """In-memory test adapter; never registered or selectable in the application."""
    unavailable=False
    invalid_signature=False
    calls=0
    def create_payment(self,request):
        if self.unavailable:raise ProviderUnavailable()
        return 'fixture-'+request.transaction_id
    def verify_payment(self,reference):
        self.calls+=1
        if self.unavailable:raise ProviderUnavailable()
        return self.observation
    def verify_webhook(self,payload,headers):
        if self.invalid_signature:raise InvalidWebhook()
        return self.event


@pytest.fixture
def gateway(client,user,monkeypatch):
    fixture=GatewayFixture()
    monkeypatch.setattr(payments,'get_provider',lambda name,db:fixture)
    tx=checkout(client,user,provider='fixture')
    fixture.observation=PaymentObservation(tx['provider_reference'],tx['id'],Decimal('2999.00'),'PKR','PAID')
    fixture.event=VerifiedWebhook(str(uuid.uuid4()),tx['provider_reference'],tx['id'],utcnow())
    return fixture,tx


def test_authenticated_webhook_independent_verification_and_duplicate(gateway,client,user):
    fixture,tx=gateway
    fixture.invalid_signature=True
    assert client.post(API+'/payments/webhooks/fixture',content=b'fixture').status_code==401
    assert fixture.calls==0
    fixture.invalid_signature=False
    for _ in range(2):
        result=client.post(API+'/payments/webhooks/fixture',content=b'fixture')
        assert result.status_code==200,result.text
    assert fixture.calls==1
    assert client.get(f'{API}/transactions/{tx["id"]}',headers=user).json()['status']=='PAID'
    with SessionLocal() as db:
        assert db.scalar(select(func.count()).select_from(Subscription))==1
        assert db.scalar(select(func.count()).select_from(PaymentReceipt))==1
    assert client.post(API+'/payments/webhooks/fixture',content=b'changed').status_code==409


@pytest.mark.parametrize('change',[{'amount':Decimal('1.00')},{'currency':'USD'},{'transaction_id':'other'},
                                {'reference':'wrong-reference'},{'status':'UNVERIFIED'}])
def test_mismatched_provider_verification_never_unlocks(gateway,client,user,change):
    fixture,tx=gateway
    good=fixture.observation;fixture.observation=replace(good,**change)
    response=client.post(API+'/payments/webhooks/fixture',content=b'fixture')
    assert response.status_code==503 and response.json()['verified'] is False
    assert client.get(f'{API}/transactions/{tx["id"]}',headers=user).json()['status']=='PENDING'
    with SessionLocal() as db:
        assert db.scalar(select(func.count()).select_from(Subscription))==0
        assert db.scalar(select(func.count()).select_from(PaymentReceipt))==0
    fixture.observation=good
    assert client.post(API+'/payments/webhooks/fixture',content=b'fixture').json()['status']=='PAID'


def test_stale_callback_and_wrong_reference_are_rejected(gateway,client):
    fixture,_=gateway
    event=fixture.event
    fixture.event=replace(event,occurred_at=utcnow()-timedelta(minutes=10))
    assert client.post(API+'/payments/webhooks/fixture',content=b'fixture').status_code==401
    fixture.event=replace(event,reference='wrong-reference')
    assert client.post(API+'/payments/webhooks/fixture',content=b'fixture').status_code==409
    assert fixture.calls==0


def test_pending_provider_confirmation_is_retryable_without_deduplicating_event(gateway,client,user):
    fixture,tx=gateway
    paid=fixture.observation
    fixture.observation=replace(paid,status='PENDING')
    assert client.post(API+'/payments/webhooks/fixture',content=b'fixture').status_code==503
    fixture.observation=paid
    assert client.post(API+'/payments/webhooks/fixture',content=b'fixture').json()['status']=='PAID'
    assert fixture.calls==2


def test_lost_webhook_reconciles_and_uncertain_creation_reuses_same_id(gateway,client,user):
    fixture,tx=gateway
    fixture.unavailable=True
    response=client.post(f'{API}/payments/{tx["id"]}/reconcile',headers=user)
    assert response.json()['status']=='PENDING'
    fixture.unavailable=False
    with SessionLocal() as db:
        assert payments.reconcile_pending(db)==1
        db.commit()
    assert client.get(f'{API}/transactions/{tx["id"]}',headers=user).json()['status']=='PAID'
    expire_subscription()
    fixture.unavailable=True
    uncertain=checkout(client,user,provider='fixture')
    assert uncertain['provider_reference'] is None and uncertain['status']=='PENDING'
    fixture.unavailable=False
    fixture.observation=replace(fixture.observation,transaction_id=uncertain['id'],reference='fixture-'+uncertain['id'])
    result=client.post(f'{API}/payments/{uncertain["id"]}/reconcile',headers=user)
    assert result.json()['status']=='PAID'


def test_unconfigured_providers_and_client_payment_claims_are_rejected(client,user):
    assert client.post(API+'/payments/webhooks/easypaisa',json={'paid':True}).status_code==503
    body={'provider':'jazzcash','plan_id':'dealer','idempotency_key':str(uuid.uuid4())}
    assert client.post(API+'/payments/create',headers=user,json=body).status_code==503
    body['provider']='development';body['status']='PAID'
    assert client.post(API+'/payments/create',headers=user,json=body).status_code==422


def test_legacy_pending_purchase_requires_review_instead_of_new_plan_terms(client,user):
    with SessionLocal() as db:
        account=db.scalar(select(User))
        tx=Transaction(user_id=account.id,idempotency_key=str(uuid.uuid4()),kind='SUBSCRIPTION_PURCHASE',
            plan_id='dealer',amount=Decimal('100.00'),currency='PKR',provider='development')
        db.add(tx);db.flush();txid=tx.id;db.commit()
    result=settle(client,user,{'id':txid})
    assert result['status']=='PAID' and result['fulfillment_status']=='REVIEW_REQUIRED'
    assert client.get(API+'/subscriptions/current',headers=user).json() is None


def test_concurrent_development_settlement_activates_only_one_cycle(client,user):
    from concurrent.futures import ThreadPoolExecutor
    tx=checkout(client,user)
    with ThreadPoolExecutor(max_workers=2) as pool:
        replies=list(pool.map(lambda _:client.post(f'{API}/payments/{tx["id"]}/development-settle',
            headers=user,json={'outcome':'PAID'}),range(2)))
    assert all(reply.status_code==200 for reply in replies)
    with SessionLocal() as db:
        assert db.scalar(select(func.count()).select_from(Subscription))==1
        assert db.scalar(select(func.count()).select_from(PaymentReceipt))==1
        assert db.scalar(select(func.count()).select_from(PaymentEvent))==1


def test_reconciliation_worker_checks_unknown_attempts_without_blocking_later_candidates(client,user,capsys):
    from app.reconcile_payments import main
    with SessionLocal() as db:
        account=db.scalar(select(User))
        for n in range(2):
            db.add(Transaction(user_id=account.id,idempotency_key='legacy-key-'+str(n),kind='REPORT_PURCHASE',
                plan_id='report',amount=Decimal('299.00'),currency='PKR',provider='unconfigured'))
        db.commit()
    main(['--limit','1']);main(['--limit','1'])
    assert capsys.readouterr().out.count('"checked": 1')==2
    with SessionLocal() as db:
        assert db.scalar(select(func.count()).select_from(PaymentAttempt))==2
        assert all(t.status=='PENDING' for t in db.scalars(select(Transaction)))


def test_admin_revenue_excludes_development_money_and_expired_cycles(client,user,billing_admin):
    tx=checkout(client,user);settle(client,user,tx)
    overview=client.get(API+'/admin/overview',headers=billing_admin).json()
    assert Decimal(overview['paid_total_pkr'])==0
    assert Decimal(overview['development_paid_total_pkr'])==Decimal('2999.00')
    assert overview['active_subscriptions']==1
    expire_subscription()
    assert client.get(API+'/admin/overview',headers=billing_admin).json()['active_subscriptions']==0


@pytest.mark.parametrize('field,value',[('id',str(uuid.uuid4())),('provider_reference','rewritten-reference'),('paid_at',utcnow())])
def test_confirmed_transaction_identity_reference_and_timestamp_are_immutable(client,user,field,value):
    tx=checkout(client,user);settle(client,user,tx)
    with SessionLocal() as db:
        record=db.get(Transaction,tx['id']);setattr(record,field,value)
        with pytest.raises(ValueError):db.commit()
