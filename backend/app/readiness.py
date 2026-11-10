"""Bounded dependency checks; public responses never disclose connection errors."""
import asyncio
import secrets
import tempfile
from pathlib import Path
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives.serialization import load_pem_private_key
from sqlalchemy import create_engine, text
from sqlalchemy.engine import make_url
from sqlalchemy.pool import QueuePool
from .config import settings
from .db import engine


def check_database():
    with engine.connect() as connection:
        connection.execute(text('SELECT 1'))


class ReadinessDatabase:
    """A tiny, bounded probe pool; normal API transactions keep their settings."""
    def __init__(self,database_url,timeout):
        url=make_url(database_url)
        seconds=max(1,min(2,int(timeout/2)))
        arguments={'poolclass':QueuePool,'pool_size':1,'max_overflow':0,
            'pool_timeout':min(1,timeout/4),'pool_pre_ping':True}
        if url.get_backend_name()=='postgresql':
            milliseconds=max(100,int(timeout*200))
            options=url.query.get('options','')
            arguments['connect_args']={'connect_timeout':seconds,
                'options':f'{options} -c statement_timeout={milliseconds} -c lock_timeout={milliseconds}'.strip()}
        elif url.get_backend_name()=='sqlite':
            arguments['connect_args']={'check_same_thread':False,'timeout':min(1,timeout/4)}
        self.engine=create_engine(database_url,**arguments)

    def check(self):
        with self.engine.connect() as connection:
            connection.execute(text('SELECT 1'))

    def close(self):
        self.engine.dispose()


def s3_client():
    import boto3
    from botocore.config import Config
    return boto3.client('s3',endpoint_url=settings.s3_endpoint or None,region_name=settings.s3_region,
        aws_access_key_id=settings.s3_access_key or None,aws_secret_access_key=settings.s3_secret_key or None,
        config=Config(connect_timeout=1,read_timeout=1,retries={'max_attempts':0}))


def check_storage():
    marker=b'phoneproof-readiness-v1'
    if settings.s3_bucket:
        client=s3_client()
        key='healthcheck/'+secrets.token_hex(16)
        stored=False
        try:
            client.put_object(Bucket=settings.s3_bucket,Key=key,Body=marker,ContentType='text/plain',ServerSideEncryption='AES256')
            stored=True
            result=client.get_object(Bucket=settings.s3_bucket,Key=key)
            body=result['Body']
            try:
                if body.read(len(marker)+1)!=marker:
                    raise RuntimeError('Object storage round trip failed')
            finally:
                body.close()
        finally:
            try:
                if stored:
                    client.delete_object(Bucket=settings.s3_bucket,Key=key)
            finally:
                client.close()
    elif settings.app_env=='production':
        raise RuntimeError('Private object storage is required')
    else:
        directory=Path(settings.media_dir)
        directory.mkdir(parents=True,exist_ok=True)
        with tempfile.TemporaryFile(dir=directory) as file:
            file.write(marker);file.seek(0)
            if file.read()!=marker:
                raise RuntimeError('Local storage round trip failed')


def check_signing():
    key=load_pem_private_key(Path(settings.signing_key_path).read_bytes(),password=None)
    if not isinstance(key,Ed25519PrivateKey):
        raise RuntimeError('An Ed25519 report signing key is required')
    marker=b'phoneproof-readiness-v1'
    key.public_key().verify(key.sign(marker),marker)


async def dependency_status(redis_client,database_probe=None):
    timeout=settings.readiness_timeout_seconds

    async def checked(call,thread=False):
        try:
            operation=asyncio.to_thread(call) if thread else call()
            await asyncio.wait_for(operation,timeout=timeout)
            return 'reachable'
        except Exception:
            return 'unavailable'

    async def redis_status():
        if not settings.redis_url:
            return 'unavailable' if settings.app_env=='production' else 'not_configured'
        if redis_client is None:
            return 'unavailable'
        async def ping():
            if not await redis_client.ping():
                raise RuntimeError('Redis did not acknowledge ping')
        return await checked(ping)

    database_check=database_probe.check if database_probe is not None else check_database
    values=await asyncio.gather(checked(database_check,True),redis_status(),checked(check_storage,True),checked(check_signing,True))
    checks=dict(zip(('database','redis','storage','signing'),values))
    return all(value in ('reachable','not_configured') for value in values),checks
