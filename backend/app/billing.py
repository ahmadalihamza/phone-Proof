"""Commercial state is server-owned and never changes diagnostic evidence."""
from datetime import timedelta
from decimal import Decimal
from typing import Literal
from uuid import UUID
from fastapi import APIRouter, Depends, HTTPException, Request
from pydantic import Field
from sqlalchemy import String, Integer, Numeric, Boolean, DateTime, ForeignKey, JSON, select, func, UniqueConstraint, event, inspect
from sqlalchemy.orm import Mapped, mapped_column
from .db import Base, get_db, utcnow
from .models import uid, User, Inspection, immutable
from .auth import current_user, require_admin, as_utc
from .audit import audit
from .inspections import Strict, owned
from .config import settings

router=APIRouter(tags=['Billing'])


class Plan(Base):
    __tablename__='plans'
    id:Mapped[str]=mapped_column(String(50),primary_key=True)
    name:Mapped[str]=mapped_column(String(100))
    price:Mapped[Decimal]=mapped_column(Numeric(12,2))
    currency:Mapped[str]=mapped_column(String(3),default='PKR')
    report_limit:Mapped[int]=mapped_column(Integer)
    period_days:Mapped[int]=mapped_column(Integer,default=30)
    active:Mapped[bool]=mapped_column(Boolean,default=True)


class Subscription(Base):
    __tablename__='subscriptions'
    id:Mapped[str]=mapped_column(String(36),primary_key=True,default=uid)
    user_id:Mapped[str]=mapped_column(ForeignKey('users.id'),index=True)
    plan_id:Mapped[str]=mapped_column(ForeignKey('plans.id'))
    status:Mapped[str]=mapped_column(String(30),default='ACTIVE')
    current_period_start:Mapped[object]=mapped_column(DateTime(timezone=True),default=utcnow)
    current_period_end:Mapped[object]=mapped_column(DateTime(timezone=True))
    cancel_at_period_end:Mapped[bool]=mapped_column(Boolean,default=False)
    next_plan_id:Mapped[str|None]=mapped_column(ForeignKey('plans.id'),nullable=True)
    report_limit:Mapped[int]=mapped_column(Integer)
    transaction_id:Mapped[str|None]=mapped_column(ForeignKey('transactions.id'),nullable=True,unique=True,index=True)


class Transaction(Base):
    __tablename__='transactions'
    id:Mapped[str]=mapped_column(String(36),primary_key=True,default=uid)
    user_id:Mapped[str]=mapped_column(ForeignKey('users.id'),index=True)
    idempotency_key:Mapped[str]=mapped_column(String(100))
    kind:Mapped[str]=mapped_column(String(30))
    inspection_id:Mapped[str|None]=mapped_column(ForeignKey('inspections.id'),nullable=True)
    plan_id:Mapped[str|None]=mapped_column(ForeignKey('plans.id'),nullable=True)
    amount:Mapped[Decimal]=mapped_column(Numeric(12,2))
    currency:Mapped[str]=mapped_column(String(3),default='PKR')
    status:Mapped[str]=mapped_column(String(30),default='PENDING')
    provider:Mapped[str]=mapped_column(String(30))
    provider_reference:Mapped[str|None]=mapped_column(String(100),nullable=True,unique=True)
    created_at:Mapped[object]=mapped_column(DateTime(timezone=True),default=utcnow)
    paid_at:Mapped[object|None]=mapped_column(DateTime(timezone=True),nullable=True)
    plan_snapshot:Mapped[dict|None]=mapped_column(JSON,nullable=True)
    fulfillment_status:Mapped[str]=mapped_column(String(30),default='PENDING',server_default='LEGACY')
    __table_args__=(UniqueConstraint('user_id','idempotency_key'),)


class Usage(Base):
    __tablename__='report_usage'
    inspection_id:Mapped[str]=mapped_column(ForeignKey('inspections.id'),primary_key=True)
    user_id:Mapped[str]=mapped_column(ForeignKey('users.id'),index=True)
    source:Mapped[str]=mapped_column(String(30))
    subscription_id:Mapped[str|None]=mapped_column(ForeignKey('subscriptions.id'),nullable=True)
    transaction_id:Mapped[str|None]=mapped_column(ForeignKey('transactions.id'),nullable=True,unique=True)
    created_at:Mapped[object]=mapped_column(DateTime(timezone=True),default=utcnow)


class Refund(Base):
    __tablename__='refunds'
    id:Mapped[str]=mapped_column(String(36),primary_key=True,default=uid)
    transaction_id:Mapped[str]=mapped_column(ForeignKey('transactions.id'),unique=True)
    reason:Mapped[str]=mapped_column(String(500))
    status:Mapped[str]=mapped_column(String(30),default='REQUESTED')
    amount:Mapped[Decimal]=mapped_column(Numeric(12,2))
    created_at:Mapped[object]=mapped_column(DateTime(timezone=True),default=utcnow)
    provider_refund_id:Mapped[str|None]=mapped_column(String(100),nullable=True,unique=True,index=True)
    completed_at:Mapped[object|None]=mapped_column(DateTime(timezone=True),nullable=True)


class PaymentAttempt(Base):
    __tablename__='payment_attempts'
    id:Mapped[str]=mapped_column(String(36),primary_key=True,default=uid)
    transaction_id:Mapped[str]=mapped_column(ForeignKey('transactions.id'),unique=True)
    provider:Mapped[str]=mapped_column(String(30))
    provider_reference:Mapped[str|None]=mapped_column(String(100),nullable=True)
    status:Mapped[str]=mapped_column(String(30),default='PENDING')
    development_outcome:Mapped[str|None]=mapped_column(String(20),nullable=True)
    verification_count:Mapped[int]=mapped_column(Integer,default=0)
    last_checked_at:Mapped[object|None]=mapped_column(DateTime(timezone=True),nullable=True)
    last_error:Mapped[str|None]=mapped_column(String(80),nullable=True)
    created_at:Mapped[object]=mapped_column(DateTime(timezone=True),default=utcnow)
    __table_args__=(UniqueConstraint('provider','provider_reference'),)


class PaymentEvent(Base):
    __tablename__='payment_events'
    id:Mapped[str]=mapped_column(String(36),primary_key=True,default=uid)
    transaction_id:Mapped[str]=mapped_column(ForeignKey('transactions.id'),index=True)
    provider:Mapped[str]=mapped_column(String(30))
    event_id:Mapped[str|None]=mapped_column(String(100),nullable=True)
    payload_hash:Mapped[str|None]=mapped_column(String(64),nullable=True)
    kind:Mapped[str]=mapped_column(String(30))
    previous_status:Mapped[str]=mapped_column(String(30))
    status:Mapped[str]=mapped_column(String(30))
    created_at:Mapped[object]=mapped_column(DateTime(timezone=True),default=utcnow)
    __table_args__=(UniqueConstraint('provider','event_id'),)


class PaymentReceipt(Base):
    __tablename__='payment_receipts'
    id:Mapped[str]=mapped_column(String(36),primary_key=True,default=uid)
    transaction_id:Mapped[str]=mapped_column(ForeignKey('transactions.id'),unique=True)
    document:Mapped[dict]=mapped_column(JSON)
    created_at:Mapped[object]=mapped_column(DateTime(timezone=True),default=utcnow)


for model in (PaymentEvent,PaymentReceipt):
    event.listen(model,'before_update',immutable)
    event.listen(model,'before_delete',immutable)


FINANCIAL_FIELDS=('id','user_id','idempotency_key','kind','inspection_id','plan_id','amount','currency','provider','created_at','plan_snapshot')


@event.listens_for(Transaction,'before_update')
def preserve_purchase_terms(mapper,connection,target):
    state=inspect(target)
    if any(state.attrs[field].history.has_changes() for field in FINANCIAL_FIELDS):
        raise ValueError('Financial purchase terms cannot be edited')
    for field in ('provider_reference','paid_at'):
        history=state.attrs[field].history
        if history.has_changes() and history.deleted and history.deleted[0] is not None:
            raise ValueError('Confirmed payment reference and timestamp cannot be edited')


event.listen(Transaction,'before_delete',immutable)


class Promo(Base):
    __tablename__='promo_codes'
    code:Mapped[str]=mapped_column(String(40),primary_key=True)
    max_redemptions:Mapped[int]=mapped_column(Integer)
    expires_at:Mapped[object]=mapped_column(DateTime(timezone=True))
    active:Mapped[bool]=mapped_column(Boolean,default=True)


class Redemption(Base):
    __tablename__='promo_redemptions'
    id:Mapped[str]=mapped_column(String(36),primary_key=True,default=uid)
    user_id:Mapped[str]=mapped_column(ForeignKey('users.id'))
    code:Mapped[str]=mapped_column(ForeignKey('promo_codes.code'))
    inspection_id:Mapped[str|None]=mapped_column(ForeignKey('inspections.id'),nullable=True,unique=True)
    __table_args__=(UniqueConstraint('user_id','code'),)


def seed_plans(db):
    # Development offers only. Production pricing must be explicitly provisioned by admin.
    if settings.app_env != 'production' and not db.get(Plan,'report'):
        db.add_all([Plan(id='report',name='One verified report (development price)',price=Decimal('299.00'),report_limit=1,period_days=0),
                    Plan(id='dealer',name='Dealer monthly (development price)',price=Decimal('2999.00'),report_limit=30,period_days=30)])


def lock_account(db,user):
    return db.scalar(select(User).where(User.id==user.id).with_for_update())


def subscription(db,user):
    sub=db.scalar(select(Subscription).where(Subscription.user_id==user.id,Subscription.status=='ACTIVE').order_by(Subscription.current_period_start.desc()).with_for_update())
    if sub and as_utc(sub.current_period_end)<=utcnow():
        sub.status='CANCELLED' if sub.cancel_at_period_end else 'EXPIRED'
        audit(db,user.id,'SUBSCRIPTION_EXPIRED',data={'subscription_id':sub.id})
        return None
    return sub


def entitlement(db,user,inspection=None):
    lock_account(db,user)
    if inspection and db.get(Usage,inspection.id):
        return {'can_generate_verified_report':True,'source':'purchased_report','remaining':0}
    if inspection:
        paid=db.scalar(select(Transaction).where(Transaction.user_id==user.id,Transaction.inspection_id==inspection.id,
            Transaction.status=='PAID',Transaction.kind=='REPORT_PURCHASE',Transaction.fulfillment_status.in_(['FULFILLED','LEGACY'])))
        if paid:
            return {'can_generate_verified_report':True,'source':'purchase','transaction_id':paid.id,'remaining':1}
    promo=db.scalar(select(Redemption).where(Redemption.user_id==user.id,Redemption.inspection_id.is_(None)))
    if promo:
        return {'can_generate_verified_report':True,'source':'promotion','redemption_id':promo.id,'remaining':1}
    sub=subscription(db,user)
    if sub:
        used=db.scalar(select(func.count()).select_from(Usage).where(Usage.subscription_id==sub.id))
        remaining=max(0,sub.report_limit-used)
        if remaining:
            return {'can_generate_verified_report':True,'source':'subscription','subscription_id':sub.id,'remaining':remaining}
    trial_used=db.scalar(select(func.count()).select_from(Usage).where(Usage.user_id==user.id,Usage.source=='trial'))
    expires=as_utc(user.created_at)+timedelta(days=settings.trial_duration_days)
    remaining=max(0,settings.trial_report_limit-trial_used) if settings.trial_enabled and utcnow()<expires else 0
    return {'can_generate_verified_report':remaining>0,'source':'trial' if remaining else 'payment_required','remaining':remaining,'trial_expires_at':expires.isoformat(),
            'trial_state':'TRIAL_ACTIVE' if remaining else ('TRIAL_EXPIRED' if utcnow()>=expires else 'TRIAL_EXHAUSTED')}


def consume_report_entitlement(db,user,inspection):
    e=entitlement(db,user,inspection)
    if not e['can_generate_verified_report']:
        raise HTTPException(402,'Verified report entitlement required; diagnostic results are preserved')
    if e['source']=='purchased_report':
        return
    db.add(Usage(inspection_id=inspection.id,user_id=user.id,source=e['source'],subscription_id=e.get('subscription_id'),transaction_id=e.get('transaction_id')))
    if e.get('redemption_id'):
        db.get(Redemption,e['redemption_id']).inspection_id=inspection.id
    inspection.commercial_status='TRIAL' if e['source']=='trial' else 'PAID'
    audit(db,user.id,'REPORT_ENTITLEMENT_CONSUMED',inspection.id,{'source':e['source']})


def serialize_transaction(t):
    result={k:getattr(t,k) for k in ('id','kind','inspection_id','plan_id','amount','currency','status','provider','provider_reference','created_at','paid_at','plan_snapshot','fulfillment_status')}
    result['amount']=str(t.amount)
    for field in ('created_at','paid_at'):
        result[field]=as_utc(result[field]).isoformat() if result[field] else None
    return result


@router.get('/plans')
def plans(db=Depends(get_db, scope='function')):
    return {'plans':[{'id':p.id,'name':p.name,'price':str(p.price),'currency':p.currency,'report_limit':p.report_limit,'period_days':p.period_days} for p in db.scalars(select(Plan).where(Plan.active.is_(True)))],
            'providers':['development'] if settings.app_env=='development' and settings.development_payments else [],
            'payment_notice':'DEVELOPMENT PAYMENT — NOT REAL MONEY' if settings.development_payments else 'No payment provider configured'}


@router.get('/billing/entitlements')
@router.get('/billing/usage')
def entitlements(user=Depends(current_user),db=Depends(get_db, scope='function')):
    return entitlement(db,user)


@router.get('/inspections/{inspection_id}/entitlement')
def report_entitlement(inspection_id:UUID,user=Depends(current_user),db=Depends(get_db, scope='function')):
    return entitlement(db,user,owned(db,user,inspection_id))


class Checkout(Strict):
    idempotency_key:str=Field(min_length=8,max_length=100)
    plan_id:str=Field(max_length=50)
    inspection_id:UUID|None=None
    provider:str=Field(pattern=r'^[a-z][a-z0-9_-]{1,29}$')


@router.post('/payments/create')
@router.post('/subscriptions')
def checkout(data:Checkout,user=Depends(current_user),db=Depends(get_db, scope='function')):
    from .payments import create_checkout
    return create_checkout(db,user,data)


class DevelopmentSettlement(Strict):
    outcome:Literal['PAID','FAILED']


@router.post('/payments/{transaction_id}/development-settle')
def settle(transaction_id:UUID,data:DevelopmentSettlement,user=Depends(current_user),db=Depends(get_db, scope='function')):
    if settings.app_env!='development' or not settings.development_payments:
        raise HTTPException(404,'Not found')
    lock_account(db,user)
    t=db.get(Transaction,str(transaction_id))
    if not t or t.user_id!=user.id:
        raise HTTPException(404,'Transaction not found')
    if t.provider!='development':
        raise HTTPException(404,'Transaction not found')
    if t.status!='PENDING':
        if t.status!=data.outcome:raise HTTPException(409,'Transaction already settled')
        return serialize_transaction(t)
    from .payments import reconcile_transaction, ensure_development_attempt
    attempt=ensure_development_attempt(db,t)
    attempt.development_outcome=data.outcome
    db.flush()
    reconcile_transaction(db,t,'DEVELOPMENT_SETTLEMENT')
    return serialize_transaction(t)


@router.post('/payments/webhooks/{provider}')
async def webhook(provider:str,request:Request,db=Depends(get_db, scope='function')):
    from .payments import process_webhook
    return process_webhook(db,provider,await request.body(),dict(request.headers))


@router.get('/transactions')
@router.get('/invoices')
def transactions(user=Depends(current_user),db=Depends(get_db, scope='function')):
    return [serialize_transaction(t) for t in db.scalars(select(Transaction).where(Transaction.user_id==user.id).order_by(Transaction.created_at.desc()).limit(200))]


@router.get('/transactions/{transaction_id}')
@router.get('/payments/{transaction_id}/status')
@router.get('/invoices/{transaction_id}')
def transaction(transaction_id:UUID,user=Depends(current_user),db=Depends(get_db, scope='function')):
    t=db.get(Transaction,str(transaction_id))
    if not t or t.user_id!=user.id:
        raise HTTPException(404,'Transaction not found')
    result=serialize_transaction(t)
    refund=db.scalar(select(Refund).where(Refund.transaction_id==t.id))
    receipt=db.scalar(select(PaymentReceipt).where(PaymentReceipt.transaction_id==t.id))
    result['refund']=None if not refund else {'id':refund.id,'status':refund.status,'amount':str(refund.amount),'reason':refund.reason,'completed_at':refund.completed_at}
    result['receipt_available']=receipt is not None
    return result


@router.get('/subscriptions/current')
def current_subscription(user=Depends(current_user),db=Depends(get_db, scope='function')):
    lock_account(db,user)
    s=subscription(db,user)
    return None if not s else {k:getattr(s,k) for k in ('id','plan_id','status','current_period_start','current_period_end','cancel_at_period_end','next_plan_id','report_limit')}


@router.post('/subscriptions/{subscription_id}/cancel')
def cancel(subscription_id:UUID,user=Depends(current_user),db=Depends(get_db, scope='function')):
    lock_account(db,user)
    s=db.get(Subscription,str(subscription_id))
    if not s or s.user_id!=user.id:
        raise HTTPException(404,'Subscription not found')
    if s.status!='ACTIVE' or as_utc(s.current_period_end)<=utcnow():
        raise HTTPException(409,'Subscription period has ended')
    s.cancel_at_period_end=True
    s.next_plan_id=None
    audit(db,user.id,'SUBSCRIPTION_CANCEL_SCHEDULED',data={'subscription_id':s.id})
    return {'cancel_at_period_end':True,'access_until':s.current_period_end}


class ChangePlan(Strict):
    plan_id:str=Field(max_length=50)


@router.post('/subscriptions/{subscription_id}/change-plan')
def change(subscription_id:UUID,data:ChangePlan,user=Depends(current_user),db=Depends(get_db, scope='function')):
    lock_account(db,user)
    s=db.get(Subscription,str(subscription_id)); p=db.get(Plan,data.plan_id)
    if not s or s.user_id!=user.id:
        raise HTTPException(404,'Subscription not found')
    if s.status!='ACTIVE' or s.cancel_at_period_end or as_utc(s.current_period_end)<=utcnow():
        raise HTTPException(409,'An active uncancelled subscription is required')
    if not p or not p.active or not p.period_days:
        raise HTTPException(422,'Subscription plan unavailable')
    s.next_plan_id=p.id
    audit(db,user.id,'PLAN_CHANGE_SCHEDULED',data={'subscription_id':s.id,'plan_id':p.id})
    return {'effective_at':s.current_period_end,'next_plan_id':p.id,'payment_required_at_renewal':True}


class Renewal(Strict):
    idempotency_key:str=Field(min_length=8,max_length=100)
    provider:str=Field(pattern=r'^[a-z][a-z0-9_-]{1,29}$')


@router.get('/subscriptions/history')
def subscription_history(user=Depends(current_user),db=Depends(get_db, scope='function')):
    lock_account(db,user);subscription(db,user)
    return [{k:getattr(s,k) for k in ('id','plan_id','status','current_period_start','current_period_end','cancel_at_period_end','next_plan_id','report_limit')}
            for s in db.scalars(select(Subscription).where(Subscription.user_id==user.id).order_by(Subscription.current_period_start.desc()).limit(100))]


@router.post('/subscriptions/{subscription_id}/renew')
def renew(subscription_id:UUID,data:Renewal,user=Depends(current_user),db=Depends(get_db, scope='function')):
    from .payments import create_checkout
    lock_account(db,user)
    s=db.get(Subscription,str(subscription_id))
    if not s or s.user_id!=user.id:raise HTTPException(404,'Subscription not found')
    if as_utc(s.current_period_end)>utcnow() or s.status=='SUSPENDED':
        raise HTTPException(409,'Manual renewal is available after the current period ends')
    return create_checkout(db,user,Checkout(idempotency_key=data.idempotency_key,provider=data.provider,
        plan_id=s.next_plan_id or s.plan_id),renewal_of=s.id)


@router.post('/payments/{transaction_id}/reconcile')
def reconcile(transaction_id:UUID,user=Depends(current_user),db=Depends(get_db, scope='function')):
    from .payments import reconcile_transaction
    lock_account(db,user)
    t=db.get(Transaction,str(transaction_id))
    if not t or t.user_id!=user.id:raise HTTPException(404,'Transaction not found')
    reconcile_transaction(db,t,'STATUS_RECONCILIATION')
    return serialize_transaction(t)


class RefundRequest(Strict):
    reason:str=Field(min_length=5,max_length=500)


@router.post('/transactions/{transaction_id}/refund')
def refund(transaction_id:UUID,data:RefundRequest,user=Depends(current_user),db=Depends(get_db, scope='function')):
    lock_account(db,user)
    t=db.get(Transaction,str(transaction_id))
    if not t or t.user_id!=user.id:
        raise HTTPException(404,'Transaction not found')
    previous=db.scalar(select(Refund).where(Refund.transaction_id==t.id))
    if previous:
        return {'id':previous.id,'status':previous.status}
    if t.status!='PAID':
        raise HTTPException(409,'Only settled payments can be refunded')
    r=Refund(transaction_id=t.id,reason=data.reason,amount=t.amount)
    db.add(r);db.flush()
    audit(db,user.id,'REFUND_REQUESTED',t.inspection_id,{'refund_id':r.id})
    return {'id':r.id,'status':r.status}


@router.post('/admin/refunds/{refund_id}/approve')
def approve_refund(refund_id:UUID,admin=Depends(require_admin),db=Depends(get_db, scope='function')):
    r=db.get(Refund,str(refund_id))
    if not r:
        raise HTTPException(404,'Refund not found')
    t=db.get(Transaction,r.transaction_id)
    lock_account(db,db.get(User,t.user_id))
    db.refresh(r);db.refresh(t)
    from .payments import settle_refund
    settle_refund(db,r,t,admin.id)
    return {'status':r.status,'existing_reports_retained':True}


@router.post('/admin/refunds/{refund_id}/reject')
def reject_refund(refund_id:UUID,data:RefundRequest,admin=Depends(require_admin),db=Depends(get_db, scope='function')):
    r=db.get(Refund,str(refund_id))
    if not r:raise HTTPException(404,'Refund not found')
    t=db.get(Transaction,r.transaction_id);lock_account(db,db.get(User,t.user_id));db.refresh(r);db.refresh(t)
    if r.status!='REQUESTED':raise HTTPException(409,'Refund is already decided')
    r.status='REJECTED';r.completed_at=utcnow()
    audit(db,admin.id,'REFUND_REJECTED',t.inspection_id,{'refund_id':r.id,'reason':data.reason})
    return {'status':r.status}


class PromoInput(Strict):
    code:str=Field(pattern=r'^[A-Z0-9_-]{3,40}$')


@router.post('/promo/validate')
def validate_promo(data:PromoInput,user=Depends(current_user),db=Depends(get_db, scope='function')):
    p=db.get(Promo,data.code)
    count=db.scalar(select(func.count()).select_from(Redemption).where(Redemption.code==data.code))
    used=db.scalar(select(Redemption).where(Redemption.code==data.code,Redemption.user_id==user.id))
    return {'valid':bool(p and p.active and as_utc(p.expires_at)>utcnow() and count<p.max_redemptions and not used),'benefit':'One verified report'}


@router.post('/promo/redeem')
def redeem(data:PromoInput,user=Depends(current_user),db=Depends(get_db, scope='function')):
    lock_account(db,user)
    p=db.scalar(select(Promo).where(Promo.code==data.code).with_for_update())
    if not validate_promo(data,user,db)['valid']:
        raise HTTPException(409,'Promotion unavailable or already used')
    db.add(Redemption(user_id=user.id,code=p.code))
    audit(db,user.id,'PROMOTION_REDEEMED',data={'code':p.code})
    return {'redeemed':True,'benefit':'One verified report'}


class PlanInput(Strict):
    id:str=Field(pattern=r'^[a-z0-9_-]{2,50}$')
    name:str=Field(min_length=2,max_length=100)
    price:Decimal=Field(ge=0,max_digits=12,decimal_places=2)
    report_limit:int=Field(ge=1,le=100000)
    period_days:int=Field(ge=0,le=366)
    active:bool=True


@router.post('/admin/plans')
def configure_plan(data:PlanInput,user=Depends(require_admin),db=Depends(get_db, scope='function')):
    p=db.get(Plan,data.id)
    if not p:
        p=Plan(**data.model_dump());db.add(p)
    else:
        for k,v in data.model_dump().items():setattr(p,k,v)
    audit(db,user.id,'PLAN_CONFIGURED',data={'plan_id':p.id})
    return {'id':p.id}
