import hashlib
import io
import json
import math
import secrets
from pathlib import Path
from uuid import UUID
from typing import Literal
from datetime import datetime, timedelta
import cv2
import numpy as np
from PIL import Image, ImageOps, UnidentifiedImageError
from fastapi import APIRouter, Depends, HTTPException, UploadFile, File, Form
from fastapi.responses import Response
from sqlalchemy import String, ForeignKey, DateTime, JSON, select, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column
from pydantic import Field, model_validator
from .db import Base, get_db, utcnow
from .models import uid
from .auth import current_user, as_utc
from .inspections import owned, Strict
from .audit import audit
from .config import settings
from .scoring import cosmetic_score

router=APIRouter(tags=['Scanner and cosmetics'])
VIEWS=['front_off','front_on','back','left','right','top','bottom','top_left','top_right','bottom_left','bottom_right','camera_module']
DEFECTS=['front_glass_crack','back_glass_crack','scratch','deep_scratch','dent','chipped_corner','frame_deformation','paint_wear','camera_glass_damage','camera_ring_damage','back_panel_damage','screen_edge_damage']
MAX_BYTES=12*1024*1024
# Sanitizing a compressed upload can produce a larger JPEG. Keep a distinct
# bound for stored objects, including accepted images from earlier versions.
MAX_STORED_BYTES=32*1024*1024
Image.MAX_IMAGE_PIXELS=24_000_000


class InspectionImage(Base):
    __tablename__='inspection_images'
    id:Mapped[str]=mapped_column(String(36),primary_key=True,default=uid)
    inspection_id:Mapped[str]=mapped_column(ForeignKey('inspections.id'),index=True)
    view:Mapped[str]=mapped_column(String(30))
    object_key:Mapped[str]=mapped_column(String(200))
    sha256:Mapped[str]=mapped_column(String(64))
    phash:Mapped[str]=mapped_column(String(16))
    quality:Mapped[dict]=mapped_column(JSON)
    captured_at:Mapped[object]=mapped_column(DateTime(timezone=True))
    created_at:Mapped[object]=mapped_column(DateTime(timezone=True),default=utcnow)
    __table_args__=(UniqueConstraint('inspection_id','view'),)


class CosmeticReview(Base):
    __tablename__='cosmetic_reviews'
    id:Mapped[str]=mapped_column(String(36),primary_key=True,default=uid)
    inspection_id:Mapped[str]=mapped_column(ForeignKey('inspections.id'),index=True)
    image_ids:Mapped[list]=mapped_column(JSON)
    findings:Mapped[list]=mapped_column(JSON)
    actor_id:Mapped[str]=mapped_column(ForeignKey('users.id'))
    note:Mapped[str]=mapped_column(String(2000))
    created_at:Mapped[object]=mapped_column(DateTime(timezone=True),default=utcnow)


class MLJob(Base):
    __tablename__='ml_jobs'
    id:Mapped[str]=mapped_column(String(36),primary_key=True,default=uid)
    inspection_id:Mapped[str]=mapped_column(ForeignKey('inspections.id'),index=True)
    image_ids:Mapped[list]=mapped_column(JSON)
    status:Mapped[str]=mapped_column(String(30),default='QUEUED')
    result:Mapped[dict]=mapped_column(JSON,default=dict)
    created_at:Mapped[object]=mapped_column(DateTime(timezone=True),default=utcnow)


def quality_check(raw):
    if len(raw)>MAX_BYTES:
        raise ValueError('Image exceeds 12 MiB')
    try:
        with Image.open(io.BytesIO(raw)) as source:
            if source.format not in ('JPEG','PNG','WEBP'):
                raise ValueError('Unsupported image format')
            if source.width*source.height>24_000_000:
                raise ValueError('Image exceeds 24 megapixels')
            source.load()
            image=ImageOps.exif_transpose(source).convert('RGB')
    except (UnidentifiedImageError, Image.DecompressionBombError, OSError) as error:
        raise ValueError('Invalid or unsafe image') from error
    sample=image.copy()
    sample.thumbnail((1280,1280))
    gray=cv2.cvtColor(np.asarray(sample),cv2.COLOR_RGB2GRAY)
    scale=640/max(gray.shape)
    scaled=cv2.resize(gray,(max(1,int(gray.shape[1]*scale)),max(1,int(gray.shape[0]*scale))))
    sharpness=float(cv2.Laplacian(scaled,cv2.CV_64F).var())
    dark=float(np.mean(gray<12)); bright=float(np.mean(gray>248))
    reasons=[]
    if min(image.size)<720:reasons.append('insufficient_resolution')
    if sharpness<65:reasons.append('blur_or_insufficient_texture')
    if dark>0.75:reasons.append('underexposure')
    if bright>0.65:reasons.append('overexposure_or_glare')
    small=cv2.resize(gray,(9,8)); bits=(small[:,1:]>small[:,:-1]).flatten()
    phash=f'{sum(int(b)<<i for i,b in enumerate(bits)):016x}'
    out=io.BytesIO(); image.save(out,format='JPEG',quality=92) # Removes EXIF/GPS.
    if out.tell()>MAX_STORED_BYTES:
        raise ValueError('Sanitized image exceeds 32 MiB; recapture at a lower resolution')
    quality={'accepted':not reasons,'reasons':reasons,'width':image.width,'height':image.height,'laplacian_variance':round(sharpness,2),'dark_fraction':round(dark,4),'bright_fraction':round(bright,4),'method_version':1,'framing':'REQUIRES_HUMAN_REVIEW','limits':'Image texture/exposure indicators are not defect detection. Phone visibility and angle require inspector review.'}
    return out.getvalue(),quality,phash


def storage_client():
    import boto3
    return boto3.client('s3',endpoint_url=settings.s3_endpoint or None,region_name=settings.s3_region,aws_access_key_id=settings.s3_access_key or None,aws_secret_access_key=settings.s3_secret_key or None)


def store_object(key,data):
    if len(data)>MAX_STORED_BYTES:
        raise ValueError('Stored image exceeds 32 MiB')
    if settings.s3_bucket:
        storage_client().put_object(Bucket=settings.s3_bucket,Key=key,Body=data,ContentType='image/jpeg',ServerSideEncryption='AES256')
    else:
        path=Path(settings.media_dir)/key
        path.parent.mkdir(parents=True,exist_ok=True)
        path.write_bytes(data)


def read_object(key):
    if settings.s3_bucket:
        result=storage_client().get_object(Bucket=settings.s3_bucket,Key=key)
        body=result['Body']
        try:
            if result.get('ContentLength',0)>MAX_STORED_BYTES:
                raise ValueError('Stored image exceeds 32 MiB')
            data=body.read(MAX_STORED_BYTES+1)
        finally:
            body.close()
    else:
        with (Path(settings.media_dir)/key).open('rb') as body:
            data=body.read(MAX_STORED_BYTES+1)
    if len(data)>MAX_STORED_BYTES:
        raise ValueError('Stored image exceeds 32 MiB')
    return data


@router.get('/scanner/views')
def views():
    return {'views':VIEWS,'maximum_bytes':MAX_BYTES,'minimum_short_edge':720}


@router.post('/inspections/{inspection_id}/images',status_code=201)
def upload(inspection_id:UUID, view:str=Form(...), scanner_device_id:str=Form(...), capture_nonce:str=Form(...), captured_at:datetime=Form(...), file:UploadFile=File(...), user=Depends(current_user),db=Depends(get_db, scope='function')):
    i=owned(db,user,inspection_id)
    if view not in VIEWS:raise HTTPException(422,'Unknown capture view')
    if not i.capture_nonce or not secrets.compare_digest(i.capture_nonce,capture_nonce) or i.scanner_device_id!=scanner_device_id:
        raise HTTPException(403,'Scanner pairing required')
    if as_utc(captured_at)>utcnow()+timedelta(minutes=10) or as_utc(captured_at)<as_utc(i.created_at)-timedelta(minutes=10):
        raise HTTPException(422,'Capture time outside inspection window')
    # Run bounded decoding, database and object-store I/O in FastAPI's worker pool.
    raw=file.file.read(MAX_BYTES+1)
    try:data,qc,phash=quality_check(raw)
    except ValueError as error:raise HTTPException(422,str(error))
    if not qc['accepted']:raise HTTPException(422,{'quality':qc,'action':'Recapture this view'})
    sha=hashlib.sha256(data).hexdigest()
    images=db.scalars(select(InspectionImage).where(InspectionImage.inspection_id==i.id)).all()
    for other in images:
        if other.view==view and other.sha256==sha:
            return {'id':other.id,'view':view,'quality':other.quality}
    if i.status in ('FINALIZED','CANCELLED'):
        raise HTTPException(409,'Inspection is immutable or cancelled')
    for other in images:
        if other.view!=view and (other.sha256==sha or (int(other.phash,16)^int(phash,16)).bit_count()<=3):
            raise HTTPException(409,'Duplicate or near-duplicate image; capture the requested distinct view')
    image=next((x for x in images if x.view==view),None)
    if not image:
        image=InspectionImage(id=uid(),inspection_id=i.id,view=view)
        db.add(image)
    image.object_key=f'{i.id}/{image.id}/{sha}.jpg'
    store_object(image.object_key,data)
    image.sha256=sha; image.phash=phash; image.quality=qc; image.captured_at=captured_at
    # Replacing any image invalidates reviews by creating a new image identity.
    if any(x.id==image.id for x in images):
        image.id=uid()
    audit(db,user.id,'SCANNER_IMAGE_ACCEPTED',i.id,{'image_id':image.id,'view':view,'hash':sha})
    return {'id':image.id,'view':view,'quality':qc}


@router.get('/inspections/{inspection_id}/images')
def images(inspection_id:UUID,user=Depends(current_user),db=Depends(get_db, scope='function')):
    i=owned(db,user,inspection_id)
    return [{'id':x.id,'view':x.view,'quality':x.quality,'sha256':x.sha256,'captured_at':x.captured_at} for x in db.scalars(select(InspectionImage).where(InspectionImage.inspection_id==i.id))]


@router.get('/images/{image_id}')
def image_content(image_id:UUID,user=Depends(current_user),db=Depends(get_db, scope='function')):
    img=db.get(InspectionImage,str(image_id))
    if not img:raise HTTPException(404,'Image not found')
    owned(db,user,img.inspection_id)
    try:data=read_object(img.object_key)
    except ValueError:raise HTTPException(502,'Stored image is unavailable') from None
    return Response(data,media_type='image/jpeg',headers={'Cache-Control':'private, no-store'})


class Finding(Strict):
    image_id:UUID
    defect:str=Field(max_length=50)
    severity:Literal['minor','moderate','severe']
    bbox:list[float]=Field(min_length=4,max_length=4)
    note:str=Field(default='',max_length=500)

    @model_validator(mode='after')
    def validate_box(self):
        if self.defect not in DEFECTS or not all(math.isfinite(v) and 0<=v<=1 for v in self.bbox) or not (self.bbox[0]<self.bbox[2] and self.bbox[1]<self.bbox[3]):
            raise ValueError('Invalid defect class or normalized bounding box')
        return self


class ReviewInput(Strict):
    image_ids:list[UUID]=Field(min_length=12,max_length=12)
    framing_and_angles_confirmed:Literal[True]
    all_views_reviewed:Literal[True]
    findings:list[Finding]=Field(default_factory=list,max_length=100)
    note:str=Field(default='',max_length=2000)


@router.post('/inspections/{inspection_id}/cosmetic/review')
def review(inspection_id:UUID,data:ReviewInput,user=Depends(current_user),db=Depends(get_db, scope='function')):
    i=owned(db,user,inspection_id,True)
    actual=list(db.scalars(select(InspectionImage).where(InspectionImage.inspection_id==i.id)))
    ids={x.id for x in actual}
    if len(ids)!=12 or ids!={str(x) for x in data.image_ids} or any(str(f.image_id) not in ids for f in data.findings):
        raise HTTPException(409,'Review must match all current twelve capture views')
    r=CosmeticReview(inspection_id=i.id,image_ids=sorted(ids),findings=[f.model_dump(mode='json')|{'source':'USER_CONFIRMED','confidence':None} for f in data.findings],actor_id=user.id,note=data.note)
    db.add(r);db.flush()
    audit(db,user.id,'COSMETIC_REVIEW_RECORDED',i.id,{'review_id':r.id,'finding_count':len(data.findings)})
    return evidence_summary(db,i)


def evidence_summary(db,i):
    images=list(db.scalars(select(InspectionImage).where(InspectionImage.inspection_id==i.id)))
    review=db.scalar(select(CosmeticReview).where(CosmeticReview.inspection_id==i.id).order_by(CosmeticReview.created_at.desc()))
    missing=sorted(set(VIEWS)-{x.view for x in images})
    reviewed=review and set(review.image_ids)=={x.id for x in images}
    findings=review.findings if reviewed else []
    scoring=cosmetic_score(findings,bool(reviewed),i.score_rule_version)
    return {'complete':not missing and bool(reviewed),'missing':missing+([] if reviewed else ['current inspector review']),
            'cosmetic_score':scoring['score'],'cosmetic_grade':scoring['grade'],'rule_version':scoring['rule_version'],'findings':findings,'method':'Manual inspector review',
            'images':[{'id':x.id,'view':x.view,'sha256':x.sha256,'quality':x.quality} for x in images],
            'review_id':review.id if reviewed else None,'ai_status':'UNAVAILABLE' if not settings.model_manifest else 'CONFIGURED'}


@router.get('/inspections/{inspection_id}/cosmetic')
def cosmetic(inspection_id:UUID,user=Depends(current_user),db=Depends(get_db, scope='function')):
    return evidence_summary(db,owned(db,user,inspection_id))


@router.post('/inspections/{inspection_id}/cv/analyze')
def analyze(inspection_id:UUID,user=Depends(current_user),db=Depends(get_db, scope='function')):
    i=owned(db,user,inspection_id,True)
    if not settings.model_manifest:
        return {'status':'UNAVAILABLE','reason':'Trained and validated PhoneProof model weights are not installed. Manual cosmetic review remains available.'}
    if not settings.redis_url:raise HTTPException(503,'ML worker queue is not configured')
    images=list(db.scalars(select(InspectionImage.id).where(InspectionImage.inspection_id==i.id)))
    if len(images)!=12:raise HTTPException(409,'All twelve accepted views are required')
    job=MLJob(inspection_id=i.id,image_ids=images)
    db.add(job);db.flush();db.commit()
    from .worker import run_inference
    try:run_inference.delay(job.id)
    except Exception:
        job.status='QUEUE_FAILED';db.commit()
        raise HTTPException(503,'ML enqueue failed; retry safely')
    return {'job_id':job.id,'status':'QUEUED'}


@router.get('/inspections/{inspection_id}/cv/status')
@router.get('/inspections/{inspection_id}/cv/results')
def cv_status(inspection_id:UUID,user=Depends(current_user),db=Depends(get_db, scope='function')):
    i=owned(db,user,inspection_id)
    job=db.scalar(select(MLJob).where(MLJob.inspection_id==i.id).order_by(MLJob.created_at.desc()))
    return {'status':job.status,'result':job.result,'job_id':job.id} if job else {'status':'UNAVAILABLE' if not settings.model_manifest else 'NOT_REQUESTED','result':None}
