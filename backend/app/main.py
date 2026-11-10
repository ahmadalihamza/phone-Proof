import hashlib
import asyncio
import json
import logging
import time
import uuid
from collections import defaultdict, deque
from contextlib import asynccontextmanager
from threading import Lock
from redis.asyncio import Redis
from redis.exceptions import RedisError
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from starlette.middleware.trustedhost import TrustedHostMiddleware
from sqlalchemy import text
from .config import settings
from .db import engine, SessionLocal
from . import auth, inspections, billing, billing_receipts, media, reports, admin, account_recovery
from .readiness import dependency_status, ReadinessDatabase

logger=logging.getLogger('phoneproof')
logging.basicConfig(level=logging.INFO,format='%(message)s')
rate_events=defaultdict(deque);rate_lock=Lock()
RATE_SCRIPT="local n=redis.call('INCR',KEYS[1]); if n==1 then redis.call('EXPIRE',KEYS[1],120) end; return n"
PROBE_PATHS={'/live','/ready','/health','/api/v1/health'}
AUTH_READ_PATHS={'/api/v1/auth/me','/api/v1/auth/capabilities'}


@asynccontextmanager
async def lifespan(app):
    # Migrations are explicit, including development. Startup never silently changes schema.
    with SessionLocal() as db:
        db.execute(text('SELECT 1'));billing.seed_plans(db);db.commit()
    reports.signing_key()
    database_probe=ReadinessDatabase(settings.database_url,settings.readiness_timeout_seconds)
    # Create each pool inside its serving event loop; TestClient lifespans get a
    # fresh pool, and no connection leaks into a later process or test loop.
    client=Redis.from_url(settings.redis_url,socket_timeout=1,socket_connect_timeout=1,
        max_connections=settings.redis_max_connections) if settings.redis_url else None
    app.state.rate_redis=client
    app.state.readiness_database=database_probe
    try:
        yield
    finally:
        app.state.rate_redis=None
        app.state.readiness_database=None
        try:
            if client is not None:
                await client.aclose(close_connection_pool=True)
        finally:
            await asyncio.to_thread(database_probe.close)


app=FastAPI(title='PhoneProof PK',version='1.0.0',lifespan=lifespan,docs_url='/docs' if settings.app_env!='production' else None)
if settings.app_env=='production':
    app.add_middleware(TrustedHostMiddleware,allowed_hosts=[host.strip() for host in settings.allowed_hosts.split(',') if host.strip()])
for router in (auth.router,account_recovery.router,inspections.router,billing.router,billing_receipts.router,media.router,reports.router):
    app.include_router(router,prefix='/api/v1')
app.include_router(reports.public_router)
app.include_router(admin.router)
app.include_router(admin.page_router)


@app.middleware('http')
async def guard(request:Request,call_next):
    request_id=str(uuid.uuid4());started=time.monotonic()
    length=request.headers.get('content-length')
    if request.method=='POST' and request.url.path.endswith('/images') and length is None:
        return JSONResponse({'detail':'Content-Length required for bounded image upload'},status_code=411)
    if length and (not length.isdigit() or int(length)>13*1024*1024):
        return JSONResponse({'detail':'Request too large'},status_code=413)
    auth_route=request.url.path.startswith('/api/v1/auth/') and request.url.path not in AUTH_READ_PATHS
    ip=request.client.host if request.client else 'unknown' # Trust proxy headers only with explicit uvicorn forwarded allowlist.
    bucket=hashlib.sha256((ip+('auth' if auth_route else 'api')).encode()).hexdigest()
    limit=20 if auth_route else 300
    now=time.time()
    if request.url.path in PROBE_PATHS:
        count=0 # Liveness must work when the rate-limit dependency is down.
    elif settings.redis_url:
        try:
            client=getattr(request.app.state,'rate_redis',None)
            if client is None:raise RedisError('Rate limit pool is unavailable')
            key='rate:'+bucket+':'+str(int(now//60))
            count=await client.eval(RATE_SCRIPT,1,key)
        except RedisError:
            if settings.app_env=='production':return JSONResponse({'detail':'Rate limit service unavailable'},status_code=503)
            count=local_rate_count(bucket,now)
    else:
        count=local_rate_count(bucket,now)
    if count>limit:return JSONResponse({'detail':'Too many requests'},status_code=429,headers={'Retry-After':'60'})
    response=await call_next(request)
    response.headers['X-Request-ID']=request_id
    response.headers['X-Content-Type-Options']='nosniff'
    if request.url.path in PROBE_PATHS:
        response.headers['Cache-Control']='no-store'
    # No request bodies, query strings, tokens, identifiers or diagnostic evidence are logged.
    logger.info(json.dumps({'request_id':request_id,'method':request.method,'status':response.status_code,'duration_ms':round((time.monotonic()-started)*1000)}))
    return response


def local_rate_count(bucket,now):
    with rate_lock:
        queue=rate_events[bucket]
        while queue and queue[0]<now-60:queue.popleft()
        queue.append(now)
        return len(queue)


@app.get('/live',include_in_schema=False)
async def live():
    return {'status':'alive','version':'1.0.0'}


@app.get('/ready',include_in_schema=False)
async def ready(request:Request):
    healthy,checks=await dependency_status(getattr(request.app.state,'rate_redis',None),
        getattr(request.app.state,'readiness_database',None))
    return JSONResponse({'status':'ready' if healthy else 'unavailable','checks':checks,'version':'1.0.0'},status_code=200 if healthy else 503)


@app.get('/health')
@app.get('/api/v1/health')
def health():
    try:
        with engine.connect() as c:c.execute(text('SELECT 1'))
        return {'status':'ok','database':'reachable','version':'1.0.0','cosmetic_ai':'configured' if settings.model_manifest else 'unavailable'}
    except Exception:
        return JSONResponse({'status':'unavailable'},status_code=503)
