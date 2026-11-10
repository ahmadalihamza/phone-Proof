"""A client may discard a queued attempt only after the server commits it."""
import uuid

from fastapi.testclient import TestClient
from sqlalchemy import event

from app.db import SessionLocal
from app.main import app
from app.models import Inspection


def test_database_commit_failure_never_acknowledges_success(client,user):
    inspection_id=str(uuid.uuid4())

    def fail_inspection_commit(session):
        if any(isinstance(item,Inspection) for item in session.new):
            raise RuntimeError('Injected commit failure')

    with TestClient(app,raise_server_exceptions=False) as transport:
        event.listen(SessionLocal.class_,'before_commit',fail_inspection_commit)
        try:
            response=transport.post('/api/v1/inspections',headers=user,json={
                'id':inspection_id,'device_profile':{'model':'Commit failure fixture'}
            })
            assert response.status_code==500
        finally:
            event.remove(SessionLocal.class_,'before_commit',fail_inspection_commit)
    with SessionLocal() as session:
        assert session.get(Inspection,inspection_id) is None
