"""Optional genuine model execution, isolated from HTTP request workers."""
import hashlib
import io
import json
from pathlib import Path
from PIL import Image
from celery import Celery
from .config import settings
from .db import SessionLocal
from .media import MLJob, InspectionImage, read_object, DEFECTS

celery=Celery('phoneproof',broker=settings.redis_url or 'redis://localhost:6379/0')
celery.conf.update(task_acks_late=True,worker_prefetch_multiplier=1,task_soft_time_limit=100,task_time_limit=120,task_serializer='json',accept_content=['json'])


def load_model():
    manifest=json.loads(Path(settings.model_manifest).read_text())
    weights=Path(settings.model_manifest).parent/manifest['weights']
    if not manifest.get('validated') or set(manifest['classes'])!=set(DEFECTS):
        raise ValueError('Validated PhoneProof defect class manifest required')
    if hashlib.sha256(weights.read_bytes()).hexdigest()!=manifest['sha256']:
        raise ValueError('Model checksum mismatch')
    from ultralytics import YOLO
    model=YOLO(str(weights))
    if set(model.names.values())!=set(DEFECTS):raise ValueError('Model classes do not match manifest')
    return model,manifest


@celery.task(bind=True,max_retries=3)
def run_inference(self,job_id):
    with SessionLocal() as db:
        job=db.get(MLJob,job_id)
        if not job or job.status=='COMPLETED':return
        try:
            model,manifest=load_model()
            job.status='PROCESSING';db.commit()
            findings=[]
            for image_id in job.image_ids:
                image=db.get(InspectionImage,image_id)
                if not image:raise ValueError('Image was replaced; request analysis again')
                result=model.predict(Image.open(io.BytesIO(read_object(image.object_key))),verbose=False)[0]
                for box in result.boxes:
                    confidence=float(box.conf[0])
                    if confidence<float(manifest.get('threshold',0.55)):continue
                    findings.append({'image_id':image_id,'defect':model.names[int(box.cls[0])],'confidence':confidence,'bbox':[float(x) for x in box.xyxyn[0]],'review_state':'REQUIRES_REVIEW'})
            job.result={'model_version':manifest['version'],'model_sha256':manifest['sha256'],'findings':findings,'advisory':True}
            job.status='COMPLETED';db.commit()
        except Exception as error:
            job.status='FAILED' if self.request.retries>=3 else 'RETRYING'
            job.result={'error_type':type(error).__name__,'reason':'Inference failed; manual review is available'};db.commit()
            if self.request.retries<3:raise self.retry(exc=error,countdown=2**self.request.retries*10)
