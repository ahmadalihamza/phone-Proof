import os
import tempfile
from pathlib import Path
TEST_ROOT=Path(tempfile.mkdtemp(prefix='phoneproof-tests-'))
os.environ['DATABASE_URL']='sqlite:///'+str(TEST_ROOT/'test.db').replace('\\','/')
os.environ['SIGNING_KEY_PATH']=str(TEST_ROOT/'signing.pem')
os.environ['MEDIA_DIR']=str(TEST_ROOT/'media')
os.environ['JWT_SECRET']='test-only-secret-never-use-in-production-'*2
os.environ['DEVELOPMENT_PAYMENTS']='true'

import pytest
from fastapi.testclient import TestClient
from app.db import Base,engine
from app.main import app,rate_events

@pytest.fixture
def client():
    Base.metadata.drop_all(engine)
    Base.metadata.create_all(engine)
    rate_events.clear()
    with TestClient(app) as client:
        yield client

@pytest.fixture
def user(client):
    response=client.post('/api/v1/auth/register',json={'email':'inspector@example.com','password':'PhoneProof-test-2026'})
    assert response.status_code==201,response.text
    return {'Authorization':'Bearer '+response.json()['access_token']}
