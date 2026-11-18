import hashlib
import hmac
import json
import re
import secrets
from datetime import datetime, timedelta
from uuid import UUID
from typing import Literal
from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field, ConfigDict, field_validator
from sqlalchemy import select
from .db import get_db, utcnow
from .models import Inspection, Result, User
from .auth import current_user, as_utc
from .audit import audit
from .config import settings
from .registry import expand, score, DEFINITIONS
from .scoring import CURRENT_RULE_VERSION, RULEBOOK

router = APIRouter(tags=['Inspections'])


def owned(db, user, inspection_id, mutable=False):
    # All inspection workflows share billing/finalization's account-first order.
    # An entitlement lookup must never hold the inspection while waiting for its
    # owner, since finalization already holds that owner's row lock.
    db.scalar(select(User).where(User.id==user.id).with_for_update())
    item = db.scalar(select(Inspection).where(Inspection.id==str(inspection_id)).with_for_update())
    if not item or item.owner_id != user.id:
        raise HTTPException(404, 'Inspection not found')
    if mutable and item.status in ('FINALIZED','CANCELLED'):
        raise HTTPException(409, 'Inspection is immutable or cancelled')
    return item


def latest(db, inspection):
    rows = db.scalars(select(Result).where(Result.inspection_id==inspection.id).order_by(Result.received_at, Result.id)).all()
    result = {}
    for row in rows:
        # Completed time selects local retry order, bounded by validated timestamps.
        old = result.get(row.check_id)
        if old is None or as_utc(datetime.fromisoformat(row.payload['completed_at'])) >= as_utc(datetime.fromisoformat(old['completed_at'])):
            result[row.check_id] = row.payload
    return sorted(result.values(), key=lambda r:r['check_id'])


class Strict(BaseModel):
    model_config = ConfigDict(extra='forbid')


class CreateInspection(Strict):
    id: UUID
    device_profile: dict[str,str] = Field(default_factory=dict, max_length=40)

    @field_validator('device_profile')
    @classmethod
    def profile_fields(cls,value):
        allowed={'manufacturer','brand','model','product','device','release','api','build_id','fingerprint','security_patch','abis'}
        if any(k not in allowed or len(v)>1000 for k,v in value.items()):
            raise ValueError('Only non-personal platform device fields are allowed')
        return value


class Capabilities(Strict):
    api: int = Field(ge=29,le=100)
    features: dict[str,bool] = Field(max_length=300)
    cameras: list[str] = Field(max_length=30)
    sensors: dict = Field(default_factory=dict,max_length=100)
    device: dict[str,str] = Field(default_factory=dict,max_length=40)
    limitations: list[str] = Field(default_factory=list,max_length=40)

    @field_validator('cameras')
    @classmethod
    def camera_ids(cls,value):
        if any(not re.fullmatch(r'[A-Za-z0-9_-]{1,30}', x) for x in value) or len(set(value))!=len(value):
            raise ValueError('Invalid camera identifiers')
        return value


class DiagnosticResult(Strict):
    id: UUID
    check_id: str = Field(max_length=150)
    definition_version: int
    status: Literal['PASS','FAIL','NOT_SUPPORTED','NOT_ACCESSIBLE','SKIPPED_BY_USER','INCONCLUSIVE']
    measured_value: dict[str,str] = Field(default_factory=dict,max_length=30)
    evidence: dict[str,str] = Field(default_factory=dict,max_length=30)
    message: str = Field(default='',max_length=2000)
    started_at: datetime
    completed_at: datetime
    app_version: str = Field(max_length=40)
    method: str = Field(default='',max_length=100)
    expected_condition: str = Field(default='',max_length=1000)
    confidence: float | None = Field(default=None,ge=0,le=1)

    @field_validator('measured_value','evidence')
    @classmethod
    def no_private_fields(cls,value):
        if any(re.search(r'latitude|longitude|imei|ssid|bssid|mac_address|phone_number',k,re.I) or len(v)>4000 for k,v in value.items()):
            raise ValueError('Private fields or oversized evidence are prohibited')
        return value


class Batch(Strict):
    results: list[DiagnosticResult] = Field(min_length=1,max_length=100)


@router.get('/diagnostics/definitions')
def definitions():
    return {'version':1,'definitions':DEFINITIONS}


@router.get('/scoring/rules')
def scoring_rules():
    return RULEBOOK


@router.post('/inspections',status_code=201)
def create(data:CreateInspection,user=Depends(current_user),db=Depends(get_db, scope='function')):
    existing=db.get(Inspection,str(data.id))
    if existing:
        if existing.owner_id!=user.id or existing.device_profile!=data.device_profile:
            raise HTTPException(409,'Inspection UUID already used')
        return {'id':existing.id,'status':existing.status,'score_rule_version':existing.score_rule_version}
    item=Inspection(id=str(data.id),owner_id=user.id,device_profile=data.device_profile,score_rule_version=CURRENT_RULE_VERSION)
    db.add(item)
    audit(db,user.id,'INSPECTION_CREATED',item.id)
    return {'id':item.id,'status':'CREATED','score_rule_version':item.score_rule_version}


@router.get('/inspections')
def inspections(user=Depends(current_user),db=Depends(get_db, scope='function')):
    return [{'id':i.id,'status':i.status,'device_profile':i.device_profile,'created_at':i.created_at} for i in db.scalars(select(Inspection).where(Inspection.owner_id==user.id).order_by(Inspection.created_at.desc()).limit(200))]


@router.get('/inspections/{inspection_id}')
def inspection(inspection_id:UUID,user=Depends(current_user),db=Depends(get_db, scope='function')):
    i=owned(db,user,inspection_id)
    return {'id':i.id,'status':i.status,'commercial_status':i.commercial_status,'device_profile':i.device_profile,'capabilities':i.capabilities,'created_at':i.created_at,'score_rule_version':i.score_rule_version}


@router.post('/inspections/{inspection_id}/capabilities')
def capabilities(inspection_id:UUID,data:Capabilities,user=Depends(current_user),db=Depends(get_db, scope='function')):
    i=owned(db,user,inspection_id)
    profile=data.model_dump()
    if i.capabilities:
        if i.capabilities != profile:
            raise HTTPException(409,'Capability snapshot already bound; start a new inspection for a different device')
        # An idempotent retry must not re-expand definitions after a server update.
        return {'definition_count':len(i.definitions),'version':1,'score_rule_version':i.score_rule_version}
    if i.status in ('FINALIZED','CANCELLED'):
        raise HTTPException(409,'Inspection is immutable or cancelled')
    i.capabilities=profile
    i.definitions=expand(profile)
    i.status='DIAGNOSTICS_IN_PROGRESS'
    audit(db,user.id,'CAPABILITIES_RECORDED',i.id)
    return {'definition_count':len(i.definitions),'version':1,'score_rule_version':i.score_rule_version}


@router.get('/inspections/{inspection_id}/required-checks')
def required(inspection_id:UUID,user=Depends(current_user),db=Depends(get_db, scope='function')):
    return owned(db,user,inspection_id).definitions


@router.post('/inspections/{inspection_id}/results/batch')
def record(inspection_id:UUID,data:Batch,user=Depends(current_user),db=Depends(get_db, scope='function')):
    i=owned(db,user,inspection_id)
    defs={d['id']:d for d in i.definitions}
    accepted=[]
    for r in data.results:
        payload=r.model_dump(mode='json',exclude={'id'})
        ph=hashlib.sha256(json.dumps(payload,sort_keys=True,separators=(',',':')).encode()).hexdigest()
        old=db.get(Result,str(r.id))
        if old:
            if old.inspection_id!=i.id or old.payload_hash!=ph:
                raise HTTPException(409,'Result idempotency key reused for different content')
            # A lost acknowledgement must remain recoverable after finalization.
            # This path returns the already committed identity without writing it.
            accepted.append(str(r.id))
            continue
        if i.status in ('FINALIZED','CANCELLED'):
            raise HTTPException(409,'Inspection is immutable or cancelled')
        d=defs.get(r.check_id)
        if not d or r.definition_version != d['version']:
            raise HTTPException(422,'Unknown, disabled or mismatched diagnostic definition')
        if as_utc(r.completed_at)<as_utc(r.started_at) or as_utc(r.completed_at)>utcnow()+timedelta(minutes=10):
            raise HTTPException(422,'Invalid diagnostic timestamps; correct device clock')
        unavailable=(i.capabilities['api']<d['min_api'] or any(i.capabilities['features'].get(c) is False for c in d['capabilities']))
        if unavailable and r.status in ('PASS','FAIL'):
            raise HTTPException(422,'Cannot claim a test on absent hardware/API')
        if r.status in ('PASS','FAIL') and not (r.evidence or r.measured_value):
            raise HTTPException(422,'Measured results require evidence')
        if r.status not in ('PASS','FAIL') and not r.message:
            raise HTTPException(422,'Unperformed/inconclusive results require a reason')
        db.add(Result(id=str(r.id),inspection_id=i.id,check_id=r.check_id,payload=payload,payload_hash=ph))
        db.flush()
        audit(db,user.id,'DIAGNOSTIC_RESULT_RECORDED',i.id,{'check_id':r.check_id,'status':r.status,'result_id':str(r.id)})
        accepted.append(str(r.id))
    return {'accepted_ids':accepted}


@router.get('/inspections/{inspection_id}/results')
def results(inspection_id:UUID,user=Depends(current_user),db=Depends(get_db, scope='function')):
    return latest(db,owned(db,user,inspection_id))


@router.get('/inspections/{inspection_id}/score')
@router.post('/inspections/{inspection_id}/score/recalculate')
def calculate(inspection_id:UUID,user=Depends(current_user),db=Depends(get_db, scope='function')):
    i=owned(db,user,inspection_id)
    return score(i.definitions,latest(db,i),i.score_rule_version)


@router.get('/inspections/{inspection_id}/analysis')
def analysis(inspection_id:UUID,user=Depends(current_user),db=Depends(get_db, scope='function')):
    from .expert_analysis import analyze
    return analyze(latest(db,owned(db,user,inspection_id)))


@router.post('/inspections/{inspection_id}/pause')
def pause(inspection_id:UUID,user=Depends(current_user),db=Depends(get_db, scope='function')):
    i=owned(db,user,inspection_id,True); i.status='PAUSED'
    audit(db,user.id,'INSPECTION_PAUSED',i.id)
    return {'status':i.status}


@router.post('/inspections/{inspection_id}/resume')
def resume(inspection_id:UUID,user=Depends(current_user),db=Depends(get_db, scope='function')):
    i=owned(db,user,inspection_id,True); i.status='DIAGNOSTICS_IN_PROGRESS'
    audit(db,user.id,'INSPECTION_RESUMED',i.id)
    return {'status':i.status}


@router.post('/inspections/{inspection_id}/cancel')
def cancel(inspection_id:UUID,user=Depends(current_user),db=Depends(get_db, scope='function')):
    i=owned(db,user,inspection_id,True); i.status='CANCELLED'
    audit(db,user.id,'INSPECTION_CANCELLED',i.id)
    return {'status':i.status}


@router.post('/inspections/{inspection_id}/pairing-token')
def pairing(inspection_id:UUID,user=Depends(current_user),db=Depends(get_db, scope='function')):
    i=owned(db,user,inspection_id,True)
    token=secrets.token_urlsafe(32)
    i.pairing_hash=hashlib.sha256(token.encode()).hexdigest()
    i.pairing_expiry=utcnow()+timedelta(minutes=5)
    audit(db,user.id,'PAIRING_TOKEN_ISSUED',i.id)
    return {'type':'phoneproof_pair','inspection_id':i.id,'token':token,'expires_at':i.pairing_expiry}


class Join(Strict):
    inspection_id:UUID
    token:str=Field(min_length=32,max_length=100)
    scanner_device_id:str=Field(min_length=8,max_length=100)


@router.post('/pairing/join')
def join(data:Join,user=Depends(current_user),db=Depends(get_db, scope='function')):
    i=owned(db,user,data.inspection_id,True)
    if not i.pairing_hash or not i.pairing_expiry or as_utc(i.pairing_expiry)<utcnow() or not hmac.compare_digest(i.pairing_hash,hashlib.sha256(data.token.encode()).hexdigest()):
        raise HTTPException(409,'Pairing token invalid, used or expired')
    i.pairing_hash=None
    i.scanner_device_id=data.scanner_device_id
    i.capture_nonce=secrets.token_urlsafe(32)
    audit(db,user.id,'SCANNER_PAIRED',i.id)
    return {'inspection_id':i.id,'capture_nonce':i.capture_nonce,'scanner_device_id':i.scanner_device_id}


@router.get('/inspections/{inspection_id}/pairing-status')
def pairing_status(inspection_id:UUID,user=Depends(current_user),db=Depends(get_db, scope='function')):
    i=owned(db,user,inspection_id)
    return {'paired':bool(i.scanner_device_id),'scanner_device_id':i.scanner_device_id}


class ImeiInput(Strict):
    imei:str=Field(pattern=r'^\d{15}$')
    pta_status:Literal['NOT_VERIFIED','INSPECTOR_CONFIRMED_APPROVED','INSPECTOR_CONFIRMED_NOT_APPROVED']='NOT_VERIFIED'


@router.post('/inspections/{inspection_id}/identity')
def identity(inspection_id:UUID,data:ImeiInput,user=Depends(current_user),db=Depends(get_db, scope='function')):
    digits=[int(c) for c in data.imei]
    if sum((n*2//10+n*2%10) if index%2 else n for index,n in enumerate(digits))%10:
        raise HTTPException(422,'IMEI checksum invalid')
    i=owned(db,user,inspection_id,True)
    i.imei_last4=data.imei[-4:]
    i.imei_hash=hmac.new(settings.jwt_secret.encode(),data.imei.encode(),hashlib.sha256).hexdigest()
    i.pta_status=data.pta_status
    audit(db,user.id,'IDENTITY_EVIDENCE_RECORDED',i.id,{'pta_status':i.pta_status})
    return {'masked_imei':'***********'+i.imei_last4,'pta_status':i.pta_status,'source':'Inspector evidence, not a live PTA query'}
