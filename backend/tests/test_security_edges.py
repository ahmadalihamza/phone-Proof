import copy
import io
import json
import uuid
import time
from datetime import timedelta
from concurrent.futures import ThreadPoolExecutor
import pytest
from sqlalchemy import select,func
from app.db import SessionLocal,utcnow
from app.models import User,Inspection
from app.auth import hasher
from app.mfa import encrypt_secret,code_at,validate
from app.billing import Usage,Promo,Redemption,consume_report_entitlement
from app.config import settings
from .test_platform import create,results_for,batch,cosmetic,image_bytes,API


@pytest.mark.parametrize('claim', ['exp', 'iat', 'nbf'])
def test_malformed_signed_time_claim_is_rejected_without_server_error(client,user,claim):
    import jwt
    token=user['Authorization'].removeprefix('Bearer ')
    claims=jwt.decode(token,settings.jwt_secret,algorithms=['HS256'],audience='phoneproof-app',issuer='phoneproof')
    claims[claim]=[]
    malformed=jwt.encode(claims,settings.jwt_secret,algorithm='HS256')
    assert client.get(API+'/auth/me',headers={'Authorization':'Bearer '+malformed}).status_code==401


def test_admin_totp_required_replay_blocked_and_role_enforced(client,user):
    secret='GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ'
    assert code_at(secret,1)=='287082'
    assert validate(secret,'287082',now=59)==1
    assert validate(secret,'287082',last_step=1,now=59) is None
    with SessionLocal() as db:
        db.add(User(email='admin@example.com',password_hash=hasher.hash('Admin-test-password-123'),role='system_admin',mfa_secret=encrypt_secret(secret)));db.commit()
    credentials={'email':'admin@example.com','password':'Admin-test-password-123'}
    assert client.post(API+'/auth/login',json=credentials).status_code==401
    credentials['otp']=code_at(secret,int(time.time()//30))
    response=client.post(API+'/auth/login',json=credentials)
    assert response.status_code==200,response.text
    admin={'Authorization':'Bearer '+response.json()['access_token']}
    assert client.get(API+'/admin/overview',headers=admin).status_code==200
    assert client.get(API+'/admin/overview',headers=user).status_code==403
    assert client.post(API+'/auth/login',json=credentials).status_code==401
    refreshed=client.post(API+'/auth/refresh',json={'refresh_token':response.json()['refresh_token']})
    assert refreshed.status_code==200
    assert client.get(API+'/admin/overview',headers={'Authorization':'Bearer '+refreshed.json()['access_token']}).status_code==200


def test_duplicate_capture_replacement_invalidates_review(client,user):
    id,profile=create(client,user);ids,nonce=cosmetic(client,user,id)
    args={'scanner_device_id':'scanner-test-fixture','capture_nonce':nonce,'captured_at':utcnow().isoformat()}
    duplicate=client.post(f'{API}/inspections/{id}/images',headers=user,data=args|{'view':'back'},files={'file':('test.jpg',image_bytes(0),'image/jpeg')})
    assert duplicate.status_code==409
    replaced=client.post(f'{API}/inspections/{id}/images',headers=user,data=args|{'view':'front_off'},files={'file':('test.jpg',image_bytes(99),'image/jpeg')})
    assert replaced.status_code==201,replaced.text
    assert replaced.json()['id']!=ids[0]
    assert not client.get(f'{API}/inspections/{id}/cosmetic',headers=user).json()['complete']


def test_private_evidence_is_signed_but_not_public(client,user):
    id,p=create(client,user)
    rows=results_for(client,user,id,p)
    rows[0]['evidence']={'inspector_note':'PRIVATE_MARKER_NEVER_PUBLIC'}
    batch(client,user,id,rows);cosmetic(client,user,id)
    response=client.post(f'{API}/inspections/{id}/finalize',headers=user)
    assert response.status_code==200,response.text
    report=response.json()
    assert 'PRIVATE_MARKER_NEVER_PUBLIC' in json.dumps(report)
    public=client.get(API+'/verify/'+report['public_token'])
    assert 'PRIVATE_MARKER_NEVER_PUBLIC' not in public.text
    assert public.json()['payload_is_privacy_projection']


def test_trial_is_account_bound_atomic_and_never_negative(client,user):
    one,_=create(client,user);two,_=create(client,user)
    # Exercise the transaction service directly; HTTP SQLite writes use BEGIN IMMEDIATE.
    from sqlalchemy import text
    def consume(id):
        with SessionLocal() as db:
            try:
                db.execute(text('BEGIN IMMEDIATE'))
                account=db.scalar(select(User).where(User.email=='inspector@example.com'))
                consume_report_entitlement(db,account,db.get(Inspection,id));db.commit();return True
            except Exception:
                db.rollback();return False
    with ThreadPoolExecutor(max_workers=2) as pool:result=list(pool.map(consume,[one,two]))
    assert sorted(result)==[False,True]
    with SessionLocal() as db:assert db.scalar(select(func.count()).select_from(Usage))==1
    assert client.get(API+'/billing/entitlements',headers=user).json()['remaining']==0
    # Re-login is equivalent to reinstalling with the same account, and cannot reset usage.
    new=client.post(API+'/auth/login',json={'email':'inspector@example.com','password':'PhoneProof-test-2026'}).json()
    assert client.get(API+'/billing/entitlements',headers={'Authorization':'Bearer '+new['access_token']}).json()['remaining']==0


def test_promotion_redemption_cannot_be_repeated(client,user):
    with SessionLocal() as db:db.add(Promo(code='ONEFREE',max_redemptions=1,expires_at=utcnow()+timedelta(days=1)));db.commit()
    assert client.post(API+'/promo/redeem',headers=user,json={'code':'ONEFREE'}).status_code==200
    assert client.post(API+'/promo/redeem',headers=user,json={'code':'ONEFREE'}).status_code==409
    with SessionLocal() as db:assert db.scalar(select(func.count()).select_from(Redemption))==1


def test_evidence_timestamp_validation_and_cancel_state(client,user):
    id,p=create(client,user);row=results_for(client,user,id,p)[0]
    bad=row|{'completed_at':(utcnow()+timedelta(days=2)).isoformat()}
    assert client.post(f'{API}/inspections/{id}/results/batch',headers=user,json={'results':[bad]}).status_code==422
    assert client.post(f'{API}/inspections/{id}/cancel',headers=user).status_code==200
    assert client.post(f'{API}/inspections/{id}/results/batch',headers=user,json={'results':[row]}).status_code==409
    assert client.post(f'{API}/inspections/{id}/finalize',headers=user).status_code==409


def test_retry_order_uses_actual_instant_across_timezones(client,user):
    from datetime import timezone
    import uuid
    id,p=create(client,user)
    row=next(r for r in results_for(client,user,id,p) if r['status']=='PASS')
    first_time=utcnow()-timedelta(minutes=3)
    newer_time=first_time+timedelta(minutes=1)
    first=row|{'started_at':first_time.astimezone(timezone(timedelta(hours=12))).isoformat(),
               'completed_at':first_time.astimezone(timezone(timedelta(hours=12))).isoformat()}
    newer=row|{'id':str(uuid.uuid4()),'status':'FAIL','started_at':newer_time.isoformat(),
               'completed_at':newer_time.isoformat()}
    # The older record has a lexically greater local timestamp and arrives last.
    assert client.post(f'{API}/inspections/{id}/results/batch',headers=user,json={'results':[newer,first]}).status_code==200
    records=client.get(f'{API}/inspections/{id}/results',headers=user).json()
    assert next(result for result in records if result['check_id']==row['check_id'])['status']=='FAIL'
