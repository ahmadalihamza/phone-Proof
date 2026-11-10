import time
from concurrent.futures import ThreadPoolExecutor
from datetime import timedelta

import pytest
from fastapi import HTTPException
from sqlalchemy import func, select, text

from app import account_recovery, mail_delivery
from app.auth import digest, hasher
from app.config import settings
from app.db import SessionLocal, utcnow
from app.mfa import code_at, encrypt_secret
from app.models import AccountAction, MailOutbox, RefreshToken, User

API='/api/v1/auth'
PASSWORD='PhoneProof-test-password-2026'
NEW_PASSWORD='PhoneProof-recovered-password-2026'


@pytest.fixture(autouse=True)
def isolated_mail(monkeypatch):
    monkeypatch.setattr(settings,'smtp_host','smtp.example.invalid')
    monkeypatch.setattr(settings,'smtp_from','accounts@example.invalid')
    monkeypatch.setattr(settings,'mfa_encryption_key','11'*32)
    # Any overlooked SMTP path must fail before opening a network connection.
    def forbidden_network(*args,**kwargs):
        raise AssertionError('Tests must explicitly mock SMTP')
    monkeypatch.setattr(mail_delivery.smtplib,'SMTP',forbidden_network)
    monkeypatch.setattr(mail_delivery.smtplib,'SMTP_SSL',forbidden_network)


def register(client,email='owner@example.com'):
    response=client.post(API+'/register',json={'email':email,'password':PASSWORD})
    assert response.status_code==201,response.text
    tokens=response.json()
    return tokens,{'Authorization':'Bearer '+tokens['access_token']}


def queued_code(email='owner@example.com'):
    with SessionLocal() as db:
        user=db.scalar(select(User).where(User.email==email))
        item=db.scalar(select(MailOutbox).where(MailOutbox.user_id==user.id,MailOutbox.payload.is_not(None))
                       .order_by(MailOutbox.created_at.desc()))
        payload=mail_delivery.decrypt_payload(item.payload)
        token=next(line for line in payload['body'].splitlines() if digest(line)==payload['action_hash'])
        assert token not in item.payload
        return {'token':token,'hash':payload['action_hash'],'mail_id':item.id,'user_id':user.id,'payload':payload}


def request_reset(client,email='owner@example.com'):
    response=client.post(API+'/recovery/request',json={'email':email})
    assert response.status_code==202,response.text
    return queued_code(email)


def test_recovery_request_hides_account_existence_and_has_bounded_cooldown(client):
    register(client)
    known=client.post(API+'/recovery/request',json={'email':'OWNER@example.com'})
    unknown=client.post(API+'/recovery/request',json={'email':'missing@example.com'})
    repeated=client.post(API+'/recovery/request',json={'email':'owner@example.com'})
    assert known.status_code==unknown.status_code==repeated.status_code==202
    assert known.json()==unknown.json()==repeated.json()
    first=queued_code()
    with SessionLocal() as db:
        assert db.scalar(select(func.count()).select_from(AccountAction))==1
        assert db.scalar(select(func.count()).select_from(MailOutbox))==1
        db.get(AccountAction,first['hash']).created_at=utcnow()-timedelta(seconds=61)
        db.commit()
    second=request_reset(client)
    assert second['token']!=first['token']
    with SessionLocal() as db:
        assert db.get(AccountAction,first['hash']).used==1
        cancelled=db.get(MailOutbox,first['mail_id'])
        assert cancelled.state=='CANCELLED' and cancelled.payload is None
        assert db.scalar(select(func.count()).select_from(MailOutbox))==2
    assert client.post(API+'/recovery/complete',json={'token':first['token'],'password':NEW_PASSWORD}).status_code==400


def test_reset_revokes_every_session_and_is_single_use(client):
    first,headers=register(client)
    second=client.post(API+'/login',json={'email':'owner@example.com','password':PASSWORD}).json()
    code=request_reset(client)
    assert client.post(API+'/recovery/complete',json={'token':code['token'],'password':NEW_PASSWORD}).status_code==200
    assert client.post(API+'/recovery/complete',json={'token':code['token'],'password':NEW_PASSWORD}).status_code==400
    for tokens in (first,second):
        assert client.get(API+'/me',headers={'Authorization':'Bearer '+tokens['access_token']}).status_code==401
        assert client.post(API+'/refresh',json={'refresh_token':tokens['refresh_token']}).status_code==401
    assert client.post(API+'/login',json={'email':'owner@example.com','password':PASSWORD}).status_code==401
    logged_in=client.post(API+'/login',json={'email':'owner@example.com','password':NEW_PASSWORD})
    assert logged_in.status_code==200
    assert client.get(API+'/me',headers={'Authorization':'Bearer '+logged_in.json()['access_token']}).status_code==200
    with SessionLocal() as db:
        assert db.get(User,code['user_id']).session_version==1
        assert all(token.revoked for token in db.scalars(select(RefreshToken).where(RefreshToken.user_id==code['user_id']))
                   if token.token_hash!=digest(logged_in.json()['refresh_token']))
        item=db.get(MailOutbox,code['mail_id'])
        assert item.state=='CANCELLED' and item.payload is None


def test_reset_rejects_expired_and_wrong_purpose_codes_without_consuming_them(client):
    _,headers=register(client)
    expired=request_reset(client)
    with SessionLocal() as db:
        db.get(AccountAction,expired['hash']).expires_at=utcnow()-timedelta(seconds=1)
        db.commit()
    assert client.post(API+'/recovery/complete',json={'token':expired['token'],'password':NEW_PASSWORD}).status_code==400
    assert client.post(API+'/email/request-verification',headers=headers).status_code==202
    verification=queued_code()
    assert client.post(API+'/recovery/complete',json={'token':verification['token'],'password':NEW_PASSWORD}).status_code==400
    assert client.post(API+'/email/verify',headers=headers,json={'token':verification['token']}).status_code==200
    with SessionLocal() as db:
        assert hasher.verify(db.get(User,expired['user_id']).password_hash,PASSWORD)
        assert db.get(AccountAction,expired['hash']).used==0


def test_verification_is_account_bound_single_use_and_does_not_revoke_login(client):
    _,owner=register(client)
    _,other=register(client,'other@example.com')
    assert client.post(API+'/email/request-verification',headers=owner).status_code==202
    code=queued_code()
    assert client.post(API+'/email/verify',headers=other,json={'token':code['token']}).status_code==400
    assert client.get(API+'/me',headers=other).json()['email_verified'] is False
    assert client.post(API+'/email/verify',headers=owner,json={'token':code['token']}).json()=={'email_verified':True}
    assert client.post(API+'/email/verify',headers=owner,json={'token':code['token']}).status_code==400
    assert client.get(API+'/me',headers=owner).json()['email_verified'] is True
    assert client.post(API+'/email/request-verification',headers=owner).status_code==202
    with SessionLocal() as db:
        assert db.scalar(select(func.count()).select_from(MailOutbox))==1
        assert db.get(MailOutbox,code['mail_id']).payload is None


def test_expired_verification_can_be_replaced_after_cooldown(client):
    _,headers=register(client)
    assert client.post(API+'/email/request-verification',headers=headers).status_code==202
    code=queued_code()
    with SessionLocal() as db:
        action=db.get(AccountAction,code['hash'])
        action.expires_at=utcnow()-timedelta(seconds=1)
        action.created_at=utcnow()-timedelta(seconds=61)
        db.commit()
    assert client.post(API+'/email/verify',headers=headers,json={'token':code['token']}).status_code==400
    assert client.get(API+'/me',headers=headers).json()['email_verified'] is False
    assert client.post(API+'/email/request-verification',headers=headers).status_code==202
    replacement=queued_code()
    assert replacement['token']!=code['token']
    assert client.post(API+'/email/verify',headers=headers,json={'token':replacement['token']}).status_code==200
    with SessionLocal() as db:
        assert db.get(MailOutbox,code['mail_id']).state=='CANCELLED'


def test_unconfigured_mail_is_reported_and_does_not_queue(client,monkeypatch):
    _,headers=register(client)
    monkeypatch.setattr(settings,'smtp_host','')
    assert client.get(API+'/capabilities').json()=={'email_recovery_available':False,'email_verification_available':False}
    assert client.post(API+'/recovery/request',json={'email':'owner@example.com'}).status_code==503
    assert client.post(API+'/email/request-verification',headers=headers).status_code==503
    with SessionLocal() as db:
        assert db.scalar(select(func.count()).select_from(AccountAction))==0
        assert db.scalar(select(func.count()).select_from(MailOutbox))==0


def test_administrator_recovery_requires_unused_totp_and_preserves_mfa(client,monkeypatch):
    secret='GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ'
    step=int(time.time()//30)
    monkeypatch.setattr('app.mfa.time.time',lambda:step*30+10)
    with SessionLocal() as db:
        account=User(email='admin@example.com',password_hash=hasher.hash(PASSWORD),role='system_admin',
                     mfa_secret=encrypt_secret(secret),mfa_last_step=step-1)
        db.add(account);db.commit()
        original_secret=account.mfa_secret
    code=request_reset(client,'admin@example.com')
    data={'token':code['token'],'password':NEW_PASSWORD}
    assert client.post(API+'/recovery/complete',json=data).status_code==401
    assert client.post(API+'/recovery/complete',json=data|{'otp':code_at(secret,step-1)}).status_code==401
    with SessionLocal() as db:
        assert db.get(AccountAction,code['hash']).used==0
        assert db.get(User,code['user_id']).session_version==0
    assert client.post(API+'/recovery/complete',json=data|{'otp':code_at(secret,step)}).status_code==200
    assert client.post(API+'/login',json={'email':'admin@example.com','password':NEW_PASSWORD,'otp':code_at(secret,step)}).status_code==401
    assert client.post(API+'/login',json={'email':'admin@example.com','password':NEW_PASSWORD,'otp':code_at(secret,step+1)}).status_code==200
    with SessionLocal() as db:
        assert db.get(User,code['user_id']).mfa_secret==original_secret


def test_administrator_without_mfa_cannot_bypass_enrollment_by_recovery(client):
    with SessionLocal() as db:
        db.add(User(email='admin@example.com',password_hash=hasher.hash(PASSWORD),role='system_admin'));db.commit()
    code=request_reset(client,'admin@example.com')
    assert client.post(API+'/recovery/complete',json={'token':code['token'],'password':NEW_PASSWORD,'otp':'123456'}).status_code==401
    with SessionLocal() as db:
        assert db.get(AccountAction,code['hash']).used==0
        assert hasher.verify(db.get(User,code['user_id']).password_hash,PASSWORD)


def test_failed_reset_transaction_rolls_back_revocation_and_can_retry(client,monkeypatch):
    tokens,headers=register(client)
    code=request_reset(client)
    original_audit=account_recovery.audit
    def failed_audit(*args,**kwargs):raise RuntimeError('Injected transaction failure')
    monkeypatch.setattr(account_recovery,'audit',failed_audit)
    with pytest.raises(RuntimeError,match='Injected transaction failure'):
        client.post(API+'/recovery/complete',json={'token':code['token'],'password':NEW_PASSWORD})
    with SessionLocal() as db:
        assert db.get(AccountAction,code['hash']).used==0
        assert db.get(User,code['user_id']).session_version==0
        assert hasher.verify(db.get(User,code['user_id']).password_hash,PASSWORD)
        assert db.get(RefreshToken,digest(tokens['refresh_token'])).revoked==0
        assert db.get(MailOutbox,code['mail_id']).state=='PENDING'
        assert db.get(MailOutbox,code['mail_id']).payload is not None
    assert client.get(API+'/me',headers=headers).status_code==200
    monkeypatch.setattr(account_recovery,'audit',original_audit)
    assert client.post(API+'/recovery/complete',json={'token':code['token'],'password':NEW_PASSWORD}).status_code==200


def test_failed_enqueue_transaction_never_leaves_a_deliverable_message(client,monkeypatch):
    register(client)
    original_audit=account_recovery.audit
    def failed_audit(*args,**kwargs):raise RuntimeError('Injected queue failure')
    monkeypatch.setattr(account_recovery,'audit',failed_audit)
    with pytest.raises(RuntimeError,match='Injected queue failure'):
        client.post(API+'/recovery/request',json={'email':'owner@example.com'})
    with SessionLocal() as db:
        assert db.scalar(select(func.count()).select_from(AccountAction))==0
        assert db.scalar(select(func.count()).select_from(MailOutbox))==0
    monkeypatch.setattr(account_recovery,'audit',original_audit)
    request_reset(client)


def test_concurrent_resets_only_consume_code_once(client):
    register(client)
    code=request_reset(client)
    def reset(_):
        with SessionLocal() as db:
            try:
                db.execute(text('BEGIN IMMEDIATE'))
                account_recovery.complete_recovery(account_recovery.ResetPassword(token=code['token'],password=NEW_PASSWORD),db)
                db.commit();return 200
            except HTTPException as exception:
                db.rollback();return exception.status_code
    with ThreadPoolExecutor(max_workers=2) as pool:
        assert sorted(pool.map(reset,range(2)))==[200,400]
    with SessionLocal() as db:
        assert db.get(User,code['user_id']).session_version==1


def test_delivery_retries_without_disclosing_smtp_errors_then_discards_payload(client,monkeypatch):
    register(client)
    code=request_reset(client)
    calls=[]
    def send(payload):
        calls.append(payload)
        if len(calls)==1:raise RuntimeError('SENSITIVE SMTP server response')
    monkeypatch.setattr(mail_delivery,'send_smtp',send)
    assert mail_delivery.deliver_batch()=={'sent':0,'failed':1}
    with SessionLocal() as db:
        item=db.get(MailOutbox,code['mail_id'])
        assert item.state=='RETRY' and item.attempts==1 and item.payload
        assert item.last_error=='RuntimeError'
    assert mail_delivery.deliver_batch()=={'sent':0,'failed':0}
    with SessionLocal() as db:
        db.get(MailOutbox,code['mail_id']).available_at=utcnow()-timedelta(seconds=1);db.commit()
    assert mail_delivery.deliver_batch()=={'sent':1,'failed':0}
    assert len(calls)==2 and calls[1]['action_hash']==code['hash']
    with SessionLocal() as db:
        item=db.get(MailOutbox,code['mail_id'])
        assert item.state=='SENT' and item.attempts==2 and item.sent_at
        assert item.payload is None and item.last_error is None
    assert mail_delivery.deliver_batch()=={'sent':0,'failed':0}


def test_fifth_delivery_failure_is_terminal_and_erases_security_code(client,monkeypatch):
    register(client)
    code=request_reset(client)
    with SessionLocal() as db:
        db.get(MailOutbox,code['mail_id']).attempts=4;db.commit()
    def fail(payload):raise TimeoutError('Fixture timeout')
    monkeypatch.setattr(mail_delivery,'send_smtp',fail)
    assert mail_delivery.deliver_batch(1)=={'sent':0,'failed':1}
    with SessionLocal() as db:
        item=db.get(MailOutbox,code['mail_id'])
        assert item.state=='FAILED' and item.attempts==5 and item.payload is None
        assert item.last_error=='TimeoutError'
    assert mail_delivery.deliver_batch(1)=={'sent':0,'failed':0}


@pytest.mark.parametrize('invalid',['used','expired','missing','wrong_account','corrupt','old_message','attempt_limit'])
def test_delivery_discards_unusable_codes_without_contacting_smtp(client,monkeypatch,invalid):
    register(client)
    code=request_reset(client)
    _,_=register(client,'other@example.com')
    with SessionLocal() as db:
        action=db.get(AccountAction,code['hash']);item=db.get(MailOutbox,code['mail_id'])
        expected='CANCELLED'
        if invalid=='used':action.used=1
        elif invalid=='expired':action.expires_at=utcnow()-timedelta(seconds=1)
        elif invalid=='missing':db.delete(action)
        elif invalid=='wrong_account':item.user_id=db.scalar(select(User.id).where(User.email=='other@example.com'))
        elif invalid=='corrupt':item.payload='invalid encrypted payload';expected='FAILED'
        elif invalid=='old_message':item.created_at=utcnow()-timedelta(minutes=31);expected='EXPIRED'
        elif invalid=='attempt_limit':item.attempts=5;expected='FAILED'
        db.commit()
    def forbidden_send(payload):raise AssertionError('Unusable code must never be sent')
    monkeypatch.setattr(mail_delivery,'send_smtp',forbidden_send)
    result=mail_delivery.deliver_batch()
    assert result=={'sent':0,'failed':int(invalid=='corrupt')}
    with SessionLocal() as db:
        item=db.get(MailOutbox,code['mail_id'])
        assert item.state==expected and item.payload is None


def test_stale_worker_cannot_overwrite_reclaimed_delivery(client,monkeypatch):
    register(client)
    code=request_reset(client)
    calls=[]
    def send(payload):
        calls.append(payload)
        if len(calls)==1:
            # A stalled worker's five-minute lease expires; a second worker sends.
            with SessionLocal() as db:
                item=db.get(MailOutbox,code['mail_id'])
                assert item.state=='SENDING'
                item.available_at=utcnow()-timedelta(seconds=1);db.commit()
            assert mail_delivery.deliver_batch(1)=={'sent':1,'failed':0}
            raise RuntimeError('Old worker failed after its lease was reclaimed')
    monkeypatch.setattr(mail_delivery,'send_smtp',send)
    assert mail_delivery.deliver_batch(1)=={'sent':0,'failed':0}
    with SessionLocal() as db:
        item=db.get(MailOutbox,code['mail_id'])
        assert item.state=='SENT' and item.attempts==2
        assert item.payload is None and item.last_error is None


def test_cancelled_in_flight_mail_cannot_be_restored_by_worker(client,monkeypatch):
    register(client)
    code=request_reset(client)
    def send(payload):
        # Recovery completes while the message has already reached SMTP.
        response=client.post(API+'/recovery/complete',json={'token':code['token'],'password':NEW_PASSWORD})
        assert response.status_code==200
    monkeypatch.setattr(mail_delivery,'send_smtp',send)
    assert mail_delivery.deliver_batch(1)=={'sent':0,'failed':0}
    with SessionLocal() as db:
        item=db.get(MailOutbox,code['mail_id'])
        assert item.state=='CANCELLED' and item.payload is None and item.sent_at is None


@pytest.mark.parametrize('implicit_tls',[False,True])
def test_smtp_requires_verified_tls_and_checks_recipient_acceptance(monkeypatch,implicit_tls):
    events=[]
    class SMTP:
        def __init__(self,host,port,**kwargs):
            events.append(('connect',kwargs));assert host=='smtp.example.invalid'
        def __enter__(self):return self
        def __exit__(self,*args):pass
        def ehlo(self):events.append(('ehlo',))
        def starttls(self,**kwargs):events.append(('starttls',kwargs))
        def login(self,username,password):events.append(('login',username,password))
        def send_message(self,message):
            events.append(('send',message));return {'owner@example.com':(550,b'rejected')}
    monkeypatch.setattr(mail_delivery.smtplib,'SMTP',SMTP)
    monkeypatch.setattr(mail_delivery.smtplib,'SMTP_SSL',SMTP)
    monkeypatch.setattr(settings,'smtp_implicit_tls',implicit_tls)
    monkeypatch.setattr(settings,'smtp_username','fixture-user')
    monkeypatch.setattr(settings,'smtp_password','fixture-password')
    with pytest.raises(RuntimeError,match='SMTP recipient rejected'):
        mail_delivery.send_smtp({'to':'owner@example.com','subject':'Fixture','body':'Fixture only'})
    tls=events[0][1]['context'] if implicit_tls else next(event[1]['context'] for event in events if event[0]=='starttls')
    assert tls.check_hostname and tls.verify_mode==mail_delivery.ssl.CERT_REQUIRED
    assert [event[0] for event in events]==(['connect','login','send'] if implicit_tls else ['connect','ehlo','starttls','ehlo','login','send'])
    assert events[-1][1]['To']=='owner@example.com'


def test_operator_worker_defaults_to_one_batch_and_watch_stops_cleanly(monkeypatch,capsys):
    batches=[]
    monkeypatch.setattr(mail_delivery,'deliver_batch',lambda limit:batches.append(limit) or {'sent':0,'failed':0})
    def stop(interval):
        assert interval==2
        raise KeyboardInterrupt
    monkeypatch.setattr(mail_delivery.time,'sleep',stop)
    mail_delivery.main([])
    assert batches==[20]
    mail_delivery.main(['--watch','--interval','2','--limit','5'])
    assert batches==[20,5]
    assert capsys.readouterr().out.count('"sent": 0')==2


@pytest.mark.parametrize('args',[['--interval','0'],['--interval','3601'],['--limit','0'],['--limit','101']])
def test_operator_worker_rejects_unbounded_configuration(args,monkeypatch):
    monkeypatch.setattr(mail_delivery,'deliver_batch',lambda limit:pytest.fail('Invalid settings must not deliver'))
    with pytest.raises(SystemExit) as error:mail_delivery.main(args)
    assert error.value.code==2
