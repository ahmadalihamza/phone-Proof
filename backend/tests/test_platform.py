import copy
import hashlib
import io
import json
import uuid
from datetime import timedelta
from pathlib import Path
import pytest
import numpy as np
from PIL import Image
from sqlalchemy import select
from pypdf import PdfReader
from app.db import SessionLocal,utcnow
from app.models import Inspection,User,Report,Result,Audit
from app.registry import DEFINITIONS,score
from app.media import VIEWS,quality_check
from app.reports import verify_signature,generate_pdf,canonical
from app.billing import Usage,Subscription,Transaction

API='/api/v1'

def create(client,user):
    id=str(uuid.uuid4())
    assert client.post(API+'/inspections',headers=user,json={'id':id,'device_profile':{'model':'TEST FIXTURE - NOT A PHONE INSPECTION','manufacturer':'Test harness','release':'16','api':'36'}}).status_code==201
    features={cap:False for d in DEFINITIONS for cap in d['capabilities']}
    features['touchscreen']=True
    profile={'api':36,'features':features,'cameras':[],'sensors':{},'device':{},'limitations':[]}
    r=client.post(f'{API}/inspections/{id}/capabilities',headers=user,json=profile)
    assert r.status_code==200,r.text
    return id,profile

def results_for(client,user,id,profile):
    defs=client.get(f'{API}/inspections/{id}/required-checks',headers=user).json()
    timestamp=utcnow().isoformat()
    return [{'id':str(uuid.uuid4()),'check_id':d['id'],'definition_version':1,
        'status':'NOT_SUPPORTED' if any(not profile['features'].get(c,True) for c in d['capabilities']) else 'PASS',
        'measured_value':{'test_fixture':'synthetic API validation only'},'evidence':{'fixture':'not a real device measurement'},
        'message':'Test fixture for transport validation; no physical phone tested','started_at':timestamp,'completed_at':timestamp,
        'app_version':'test-harness','method':d['handler']} for d in defs if d['weight']>0]

def batch(client,user,id,rows):
    for start in range(0,len(rows),100):
        r=client.post(f'{API}/inspections/{id}/results/batch',headers=user,json={'results':rows[start:start+100]})
        assert r.status_code==200,r.text

def image_bytes(seed):
    image=Image.fromarray(np.random.default_rng(seed).integers(25,225,(760,800,3),dtype=np.uint8))
    buffer=io.BytesIO();image.save(buffer,format='JPEG',quality=95);return buffer.getvalue()

def cosmetic(client,user,id):
    token=client.post(f'{API}/inspections/{id}/pairing-token',headers=user).json()['token']
    joined=client.post(API+'/pairing/join',headers=user,json={'inspection_id':id,'token':token,'scanner_device_id':'scanner-test-fixture'})
    assert joined.status_code==200,joined.text
    nonce=joined.json()['capture_nonce']
    ids=[]
    for index,view in enumerate(VIEWS):
        r=client.post(f'{API}/inspections/{id}/images',headers=user,data={'view':view,'scanner_device_id':'scanner-test-fixture','capture_nonce':nonce,'captured_at':utcnow().isoformat()},files={'file':('fixture.jpg',image_bytes(index),'image/jpeg')})
        assert r.status_code==201,r.text;ids.append(r.json()['id'])
    reviewed=client.post(f'{API}/inspections/{id}/cosmetic/review',headers=user,json={'image_ids':ids,'framing_and_angles_confirmed':True,'all_views_reviewed':True,'findings':[],'note':'Synthetic image fixtures; not an inspection'})
    assert reviewed.status_code==200,reviewed.text
    return ids,nonce

def test_health_and_registry(client):
    assert client.get('/health').json()['database']=='reachable'
    assert len(DEFINITIONS)>=250
    assert len({d['id'] for d in DEFINITIONS})==len(DEFINITIONS)
    assert all(d['weight']==0 for d in DEFINITIONS if d['scoring_type']=='INFORMATION')

def test_auth_role_injection_login_rotation_and_replay(client,user):
    assert client.post(API+'/auth/register',json={'email':'admin@example.com','password':'a sufficiently long password','role':'system_admin'}).status_code==422
    tokens=client.post(API+'/auth/login',json={'email':'inspector@example.com','password':'PhoneProof-test-2026'}).json()
    refreshed=client.post(API+'/auth/refresh',json={'refresh_token':tokens['refresh_token']})
    assert refreshed.status_code==200
    assert client.post(API+'/auth/refresh',json={'refresh_token':tokens['refresh_token']}).status_code==401
    assert client.post(API+'/auth/refresh',json={'refresh_token':refreshed.json()['refresh_token']}).status_code==401
    assert client.get(API+'/auth/me').status_code==401

def test_ownership_and_idempotent_results(client,user):
    id,p=create(client,user);rows=results_for(client,user,id,p)
    batch(client,user,id,rows);batch(client,user,id,rows)
    assert len(client.get(f'{API}/inspections/{id}/results',headers=user).json())==len(rows)
    changed=copy.deepcopy(rows[0]);changed['message']='different payload'
    assert client.post(f'{API}/inspections/{id}/results/batch',headers=user,json={'results':[changed]}).status_code==409
    stranger=client.post(API+'/auth/register',json={'email':'stranger@example.com','password':'PhoneProof-test-2026'}).json()
    other={'Authorization':'Bearer '+stranger['access_token']}
    assert client.get(f'{API}/inspections/{id}',headers=other).status_code==404
    assert client.post(f'{API}/inspections/{id}/results/batch',headers=other,json={'results':[rows[0]]}).status_code==404

def test_disabled_unknown_hardware_permission_and_missing_evidence(client,user):
    id,p=create(client,user);r=results_for(client,user,id,p)[0]
    for key in ('nonexistent','security.play_integrity'):
        bad=r|{'id':str(uuid.uuid4()),'check_id':key}
        assert client.post(f'{API}/inspections/{id}/results/batch',headers=user,json={'results':[bad]}).status_code==422
    absent=next(x for x in results_for(client,user,id,p) if x['status']=='NOT_SUPPORTED')
    assert client.post(f'{API}/inspections/{id}/results/batch',headers=user,json={'results':[absent|{'status':'PASS'}]}).status_code==422
    accessible=r|{'status':'NOT_ACCESSIBLE','message':'Microphone permission denied','measured_value':{},'evidence':{}}
    assert client.post(f'{API}/inspections/{id}/results/batch',headers=user,json={'results':[accessible]}).status_code==200
    assert client.post(f'{API}/inspections/{id}/results/batch',headers=user,json={'results':[r|{'id':str(uuid.uuid4()),'measured_value':{},'evidence':{}}]}).status_code==422
    assert client.post(f'{API}/inspections/{id}/results/batch',headers=user,json={'results':[r|{'id':str(uuid.uuid4()),'measured_value':{'latitude':'1'}}]}).status_code==422

def test_pairing_single_use_and_expiry(client,user):
    id,_=create(client,user)
    token=client.post(f'{API}/inspections/{id}/pairing-token',headers=user).json()['token']
    body={'inspection_id':id,'token':token,'scanner_device_id':'scanner-123'}
    assert client.post(API+'/pairing/join',headers=user,json=body).status_code==200
    assert client.post(API+'/pairing/join',headers=user,json=body).status_code==409
    token=client.post(f'{API}/inspections/{id}/pairing-token',headers=user).json()['token']
    with SessionLocal() as db:
        db.get(Inspection,id).pairing_expiry=utcnow()-timedelta(seconds=1);db.commit()
    assert client.post(API+'/pairing/join',headers=user,json=body|{'token':token}).status_code==409

def test_quality_rejects_blank_small_and_invalid_images():
    out=io.BytesIO();Image.new('RGB',(800,800),'white').save(out,format='JPEG')
    _,quality,_=quality_check(out.getvalue());assert not quality['accepted'];assert 'blur_or_insufficient_texture' in quality['reasons']
    with pytest.raises(ValueError):quality_check(b'not an image')
    _,good,_=quality_check(image_bytes(30));assert good['accepted']

def test_scoring_is_order_independent_and_caps_critical_failure():
    d=[{'id':'a','weight':5,'implementation':'ENABLED','critical':True},{'id':'b','weight':10,'implementation':'ENABLED','critical':False},{'id':'info','weight':0,'implementation':'ENABLED','critical':False}]
    r=[{'check_id':'a','status':'FAIL'},{'check_id':'b','status':'PASS'},{'check_id':'info','status':'PASS'}]
    assert score(d,r)==score(list(reversed(d)),list(reversed(r)))
    assert score(d,r)['functional_score']==66.67
    assert score(d,[{'check_id':'a','status':'NOT_SUPPORTED'},{'check_id':'b','status':'PASS'}])['functional_score']==100
    assert score(d,[{'check_id':'a','status':'INCONCLUSIVE'}])['functional_score'] is None

def test_complete_server_journey_pdf_signature_immutability_and_revocation(client,user,tmp_path):
    id,p=create(client,user)
    assert client.post(f'{API}/inspections/{id}/finalize',headers=user).status_code==409
    batch(client,user,id,results_for(client,user,id,p))
    assert client.post(f'{API}/inspections/{id}/finalize',headers=user).status_code==409
    cosmetic(client,user,id)
    unavailable=client.post(f'{API}/inspections/{id}/cv/analyze',headers=user).json()
    assert unavailable['status']=='UNAVAILABLE'
    r=client.post(f'{API}/inspections/{id}/finalize',headers=user)
    assert r.status_code==200,r.text
    report=r.json();report_id=report['id'];token=report['public_token']
    assert client.post(f'{API}/inspections/{id}/finalize',headers=user).json()['id']==report_id
    assert client.post(f'{API}/inspections/{id}/results/batch',headers=user,json={'results':results_for(client,user,id,p)[:1]}).status_code==409
    verified=client.get(API+'/verify/'+token).json()
    assert verified['status']=='VALID'
    assert 'password' not in json.dumps(verified) and 'latitude' not in json.dumps(verified)
    assert client.get('/r/'+token).status_code==200
    pdf=client.get(API+'/reports/'+report_id+'/pdf',headers=user)
    assert pdf.status_code==200 and pdf.content.startswith(b'%PDF')
    reader=PdfReader(io.BytesIO(pdf.content));text=''.join(p.extract_text() for p in reader.pages)
    assert 'PhoneProof' in text
    assert report['hash'] in text
    assert 'Single touch' in text
    import cv2
    qr_values=[cv2.QRCodeDetector().detectAndDecode(np.array(item.image.convert('RGB')))[0]
               for page in reader.pages for item in page.images]
    assert any(value.endswith('/r/'+token) for value in qr_values)
    out=Path(__file__).resolve().parents[2]/'output/pdf'
    out.mkdir(parents=True,exist_ok=True);(out/'test-fixture-report.pdf').write_bytes(pdf.content)
    with SessionLocal() as db:
        stored=db.get(Report,report_id)
        assert verify_signature(stored)
        stored.payload=copy.deepcopy(stored.payload)|{'registry_version':999}
        assert not verify_signature(stored)
        db.rollback()
        stored=db.get(Report,report_id);stored.hash='0'*64
        with pytest.raises(ValueError):db.commit()
        db.rollback()
        assert db.scalar(select(Usage).where(Usage.inspection_id==id)) is not None
        account=db.scalar(select(User).where(User.email=='inspector@example.com'));account.role='system_admin';db.commit()
    assert client.post(API+'/admin/reports/'+report_id+'/revoke',headers=user,json={'reason':'Test fixture revocation'}).status_code==200
    assert client.get(API+'/verify/'+token).json()['status']=='REVOKED'

def test_development_payment_entitlements_and_cancellation(client,user):
    id,_=create(client,user)
    body={'idempotency_key':str(uuid.uuid4()),'plan_id':'dealer','provider':'development'}
    r=client.post(API+'/payments/create',headers=user,json=body)
    assert r.status_code==200,r.text
    tx=r.json();assert tx['status']=='PENDING'
    assert client.post(API+'/payments/create',headers=user,json=body).json()['id']==tx['id']
    assert client.post(API+'/payments/create',headers=user,json=body|{'amount':0}).status_code==422
    for _ in range(2):assert client.post(f'{API}/payments/{tx["id"]}/development-settle',headers=user,json={'outcome':'PAID'}).status_code==200
    assert client.get(API+'/billing/entitlements',headers=user).json()['source']=='subscription'
    sub=client.get(API+'/subscriptions/current',headers=user).json()
    assert client.post(f'{API}/subscriptions/{sub["id"]}/cancel',headers=user).status_code==200
    assert client.get(API+'/subscriptions/current',headers=user).json()['cancel_at_period_end']
    with SessionLocal() as db:assert len(list(db.scalars(select(Subscription))))==1

def test_failed_payment_never_unlocks_and_production_guard(client,user,monkeypatch):
    from app.config import settings,Settings
    id,_=create(client,user)
    monkeypatch.setattr(settings,'trial_report_limit',0)
    body={'idempotency_key':str(uuid.uuid4()),'plan_id':'report','provider':'development','inspection_id':id}
    tx=client.post(API+'/payments/create',headers=user,json=body).json()
    assert client.post(f'{API}/payments/{tx["id"]}/development-settle',headers=user,json={'outcome':'FAILED'}).status_code==200
    assert not client.get(f'{API}/inspections/{id}/entitlement',headers=user).json()['can_generate_verified_report']
    monkeypatch.setattr(settings,'app_env','production')
    assert client.post(API+'/payments/create',headers=user,json=body).status_code==503
    assert client.post(f'{API}/payments/{tx["id"]}/development-settle',headers=user,json={'outcome':'PAID'}).status_code==404
    with pytest.raises(ValueError):Settings(app_env='production',jwt_secret='short',database_url='sqlite:///x')

def test_login_rate_limit(client):
    for _ in range(20):client.post(API+'/auth/login',json={'email':'nobody@example.com','password':'incorrect-password'})
    assert client.post(API+'/auth/login',json={'email':'nobody@example.com','password':'incorrect-password'}).status_code==429
