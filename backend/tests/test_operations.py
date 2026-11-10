import asyncio
import io
import json
import hashlib
from collections import defaultdict
from pathlib import Path
import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient
from redis.exceptions import RedisError
from starlette.middleware.trustedhost import TrustedHostMiddleware
from app import healthcheck, main, readiness
from app.config import Settings, settings
from app.db import Base, engine


class FakeRedis:
    def __init__(self,unavailable=False):
        self.counts=defaultdict(int)
        self.loops=[]
        self.closed=False
        self.unavailable=unavailable

    async def eval(self,script,number,key):
        self.loops.append(id(asyncio.get_running_loop()))
        if self.unavailable:
            raise RedisError('secret Redis connection failure')
        self.counts[key]+=1
        return self.counts[key]

    async def ping(self):
        if self.unavailable:
            raise RedisError('secret Redis connection failure')
        return True

    async def aclose(self,close_connection_pool=False):
        assert close_connection_pool
        self.loops.append(id(asyncio.get_running_loop()))
        self.closed=True


def test_loopback_probe_sends_specific_allowed_host(monkeypatch):
    # Reproduce the production deployment: localhost is deliberately not trusted.
    isolated=FastAPI()
    isolated.add_middleware(TrustedHostMiddleware,allowed_hosts=['api.example.com'])

    @isolated.get('/ready')
    def ready():
        return {'status':'ready'}

    host=healthcheck.select_probe_host('api.example.com','https://api.example.com')
    with TestClient(isolated) as client:
        assert client.get('/ready',headers={'Host':'localhost'}).status_code==400

        def loopback(request,timeout):
            assert request.full_url=='http://127.0.0.1:8000/ready'
            assert timeout==8
            response=client.get('/ready',headers={'Host':request.get_header('Host')})
            assert response.status_code==200
            body=io.BytesIO(response.content)
            body.status=response.status_code
            return body

        monkeypatch.setattr(healthcheck,'urlopen',loopback)
        assert healthcheck.probe('http://127.0.0.1:8000/ready',host)


def test_probe_host_selection_never_uses_blanket_wildcard():
    assert healthcheck.select_probe_host('api.example.com','https://reports.example.com')=='api.example.com'
    assert healthcheck.select_probe_host('*.example.com')=='healthcheck.example.com'
    assert healthcheck.select_probe_host('api.example.com,probe.internal','','probe.internal')=='probe.internal'
    for configured in ('attacker.example','api.example.com\r\nX-Injected: yes'):
        with pytest.raises(ValueError):
            healthcheck.select_probe_host('api.example.com','',configured)
    with pytest.raises(ValueError):
        healthcheck.select_probe_host('*')
    with pytest.raises(ValueError,match='blanket wildcard'):
        Settings(app_env='production',allowed_hosts='*')


@pytest.mark.parametrize('scheme,expected_scheme',[
    ('postgres','postgresql+psycopg'),('postgresql','postgresql+psycopg'),
    ('postgresql+psycopg','postgresql+psycopg'),('postgresql+psycopg2','postgresql+psycopg2'),
],ids=['render-short-scheme','native-scheme','explicit-psycopg3','explicit-driver-preserved'])
def test_database_native_urls_use_installed_driver_without_changing_connection_terms(scheme,expected_scheme):
    # Synthetic credentials stay encoded; assertion failures expose hashes only.
    tail='fixture:synthetic%40password%3Avalue@db.internal:5432/phoneproof?sslmode=require&application_name=phoneproof'
    actual=Settings(app_env='development',database_url=scheme+'://'+tail).database_url
    expected=expected_scheme+'://'+tail
    assert hashlib.sha256(actual.encode()).digest()==hashlib.sha256(expected.encode()).digest()


def test_sqlite_database_url_is_preserved():
    url='sqlite:///./runtime/local.db?timeout=7'
    assert Settings(app_env='development',database_url=url).database_url==url


def test_probe_requires_ready_payload(monkeypatch):
    def unavailable(request,timeout):
        body=io.BytesIO(json.dumps({'status':'unavailable'}).encode())
        body.status=200
        return body
    monkeypatch.setattr(healthcheck,'urlopen',unavailable)
    assert not healthcheck.probe('http://127.0.0.1:8000/ready','api.example.com')


def test_readiness_and_legacy_health_work_with_isolated_database(client,monkeypatch):
    monkeypatch.setattr(settings,'redis_url','')
    ready=client.get('/ready')
    assert ready.status_code==200,ready.text
    assert ready.json()['checks']=={'database':'reachable','redis':'not_configured','storage':'reachable','signing':'reachable'}
    assert client.get('/live').json()['status']=='alive'
    assert client.get('/health').json()['database']=='reachable'
    assert client.get('/api/v1/health').json()['database']=='reachable'


@pytest.mark.parametrize('dependency',['database','storage','signing'])
def test_readiness_fails_closed_without_leaking_errors(client,monkeypatch,dependency):
    def unavailable():
        raise RuntimeError('secret service credentials must not be exposed')
    if dependency=='database':
        monkeypatch.setattr(main.app.state.readiness_database,'check',unavailable)
    else:
        monkeypatch.setattr(readiness,'check_'+dependency,unavailable)
    response=client.get('/ready')
    assert response.status_code==503
    assert response.json()['checks'][dependency]=='unavailable'
    assert 'secret' not in response.text
    assert client.get('/live').status_code==200


def test_async_redis_pool_is_reused_and_closed_for_each_serving_loop(monkeypatch):
    monkeypatch.setattr(settings,'redis_url','redis://fixture.invalid/0')
    monkeypatch.setattr(main.time,'time',lambda:1_800_000_000)
    pools=[]

    def pool(url,**kwargs):
        assert url==settings.redis_url
        assert kwargs['socket_timeout']==kwargs['socket_connect_timeout']==1
        assert kwargs['max_connections']==settings.redis_max_connections
        client=FakeRedis();pools.append(client)
        return client

    monkeypatch.setattr(main.Redis,'from_url',pool)
    Base.metadata.create_all(engine)
    for _ in range(2):
        with TestClient(main.app) as client:
            current=pools[-1]
            # Routine account reads must not consume the credential-action cap.
            for _ in range(25):
                assert client.get('/api/v1/auth/me').status_code==401
            for _ in range(20):
                assert client.post('/api/v1/auth/login',json={}).status_code==422
            assert client.post('/api/v1/auth/login',json={}).status_code==429
            count=sum(current.counts.values())
            assert client.get('/ready').status_code==200
            assert client.get('/live').status_code==200
            assert sum(current.counts.values())==count
        assert current.closed
        assert len(set(current.loops))==1
        assert main.app.state.rate_redis is None
        assert main.app.state.readiness_database is None
    assert len(pools)==2 and pools[0] is not pools[1]


def test_redis_outage_blocks_production_requests_but_preserves_liveness(client,monkeypatch):
    monkeypatch.setattr(settings,'app_env','production')
    monkeypatch.setattr(settings,'redis_url','redis://fixture.invalid/0')
    monkeypatch.setattr(main.app.state,'rate_redis',FakeRedis(unavailable=True))
    monkeypatch.setattr(readiness,'check_storage',lambda:None)
    response=client.get('/api/v1/plans')
    assert response.status_code==503
    assert 'secret' not in response.text
    assert client.get('/live').status_code==200
    ready=client.get('/ready')
    assert ready.status_code==503
    assert ready.json()['checks']['redis']=='unavailable'


def test_development_redis_outage_preserves_local_login_limit(client,monkeypatch):
    monkeypatch.setattr(settings,'redis_url','redis://fixture.invalid/0')
    monkeypatch.setattr(main.app.state,'rate_redis',FakeRedis(unavailable=True))
    for _ in range(20):
        assert client.post('/api/v1/auth/login',json={}).status_code==422
    assert client.post('/api/v1/auth/login',json={}).status_code==429


def test_production_readiness_rejects_missing_required_redis(monkeypatch):
    monkeypatch.setattr(settings,'app_env','production')
    monkeypatch.setattr(settings,'redis_url','')
    for check in ('database','storage','signing'):
        monkeypatch.setattr(readiness,'check_'+check,lambda:None)
    healthy,checks=asyncio.run(readiness.dependency_status(None))
    assert not healthy and checks['redis']=='unavailable'


def test_readiness_bounds_and_cancels_slow_redis_probe(monkeypatch):
    monkeypatch.setattr(settings,'redis_url','redis://fixture.invalid/0')
    monkeypatch.setattr(settings,'readiness_timeout_seconds',0.1)
    for check in ('database','storage','signing'):
        monkeypatch.setattr(readiness,'check_'+check,lambda:None)

    class SlowRedis:
        cancelled=False
        async def ping(self):
            try:
                await asyncio.sleep(30)
            finally:
                self.cancelled=True

    redis=SlowRedis()
    healthy,checks=asyncio.run(readiness.dependency_status(redis))
    assert not healthy and checks['redis']=='unavailable' and redis.cancelled


def test_storage_probe_checks_write_read_delete_and_closes_client(monkeypatch):
    monkeypatch.setattr(settings,'s3_bucket','private-fixture')
    class Storage:
        def put_object(self,**kwargs):
            self.key=kwargs['Key'];self.payload=kwargs['Body']
            assert self.key.startswith('healthcheck/')
            assert kwargs['ServerSideEncryption']=='AES256'
        def get_object(self,**kwargs):
            assert kwargs['Key']==self.key
            self.body=io.BytesIO(self.payload)
            return {'Body':self.body}
        def delete_object(self,**kwargs):
            assert kwargs['Key']==self.key
            self.deleted=True
        def close(self):
            self.closed=True
    storage=Storage()
    monkeypatch.setattr(readiness,'s3_client',lambda:storage)
    readiness.check_storage()
    assert storage.deleted and storage.closed and storage.body.closed


def test_production_storage_probe_requires_private_bucket(monkeypatch):
    monkeypatch.setattr(settings,'app_env','production')
    monkeypatch.setattr(settings,'s3_bucket','')
    with pytest.raises(RuntimeError,match='object storage'):
        readiness.check_storage()


def test_signing_probe_rejects_wrong_key_type_and_missing_file(monkeypatch,tmp_path):
    path=tmp_path/'not-a-signing-key.pem'
    monkeypatch.setattr(settings,'signing_key_path',str(path))
    with pytest.raises(FileNotFoundError):
        readiness.check_signing()
    path.write_bytes(b'fixture')
    monkeypatch.setattr(readiness,'load_pem_private_key',lambda *args,**kwargs:object())
    with pytest.raises(RuntimeError,match='Ed25519'):
        readiness.check_signing()


def test_postgres_probe_has_bounded_driver_pool_and_statement_timeouts(monkeypatch):
    class Engine:
        disposed=False
        def dispose(self):
            self.disposed=True
    engine=Engine()
    def create(url,**arguments):
        assert url=='postgresql+psycopg://fixture@localhost/fixture?options=-c%20search_path%3Dpublic'
        assert arguments['pool_size']==1 and arguments['max_overflow']==0
        assert arguments['pool_timeout']==1
        assert arguments['connect_args']=={'connect_timeout':2,
            'options':'-c search_path=public -c statement_timeout=1000 -c lock_timeout=1000'}
        return engine
    monkeypatch.setattr(readiness,'create_engine',create)
    probe=readiness.ReadinessDatabase('postgresql+psycopg://fixture@localhost/fixture?options=-c%20search_path%3Dpublic',5)
    probe.close()
    assert engine.disposed


def test_sqlite_probe_uses_its_own_small_pool_and_releases_connections(tmp_path):
    probe=readiness.ReadinessDatabase('sqlite:///'+(tmp_path/'probe.db').as_posix(),5)
    try:
        probe.check()
        assert probe.engine.pool.size()==1
        assert probe.engine.pool.checkedout()==0
        assert probe.engine.pool.timeout()==1
    finally:
        probe.close()


def test_compose_healthcheck_uses_host_aware_module():
    # Guard the operational regression: the old plain localhost URL failed the
    # production Host allowlist before it could reach a health endpoint.
    compose=(Path(__file__).parents[2]/'infra'/'docker-compose.yml').read_text()
    assert '"python", "-m", "app.healthcheck"' in compose
    assert 'urllib.request.urlopen' not in compose
