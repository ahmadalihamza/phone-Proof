"""Opt-in acceptance against a real, disposable S3-compatible service.

tools/verify_services.py starts a loopback MinIO instance and supplies temporary
credentials. A random private bucket is created and removed for every test.
"""
import io
import os
from urllib.error import HTTPError
from urllib.request import urlopen
from uuid import uuid4

import numpy as np
import pytest
from PIL import Image

from app import media, readiness
from app.config import settings


pytestmark = pytest.mark.skipif(not os.environ.get('TEST_S3_ENDPOINT'), reason='TEST_S3_ENDPOINT is not configured')


@pytest.fixture
def object_store(monkeypatch):
    monkeypatch.setattr(settings, 's3_endpoint', os.environ['TEST_S3_ENDPOINT'])
    monkeypatch.setattr(settings, 's3_access_key', os.environ['TEST_S3_ACCESS_KEY'])
    monkeypatch.setattr(settings, 's3_secret_key', os.environ['TEST_S3_SECRET_KEY'])
    monkeypatch.setattr(settings, 's3_region', 'auto')
    bucket = 'phoneproof-acceptance-' + uuid4().hex
    monkeypatch.setattr(settings, 's3_bucket', bucket)
    client = media.storage_client()
    client.create_bucket(Bucket=bucket)
    try:
        yield client, bucket
    finally:
        objects = client.list_objects_v2(Bucket=bucket).get('Contents', [])
        for obj in objects:
            client.delete_object(Bucket=bucket, Key=obj['Key'])
        client.delete_bucket(Bucket=bucket)
        client.close()


def test_expanded_image_is_private_encrypted_metadata_free_and_readable(object_store):
    client, bucket = object_store
    image = Image.fromarray(np.random.default_rng(17).integers(0, 256, (4800, 4900, 3), dtype=np.uint8))
    exif = image.getexif()
    exif[0x010E] = 'Synthetic private metadata that must be removed'
    incoming = io.BytesIO()
    image.save(incoming, 'JPEG', quality=40, exif=exif)
    assert len(incoming.getvalue()) <= media.MAX_BYTES
    sanitized, quality, _ = media.quality_check(incoming.getvalue())
    assert quality['accepted'], quality
    assert media.MAX_BYTES < len(sanitized) <= media.MAX_STORED_BYTES
    key = 'synthetic/expanded.jpg'
    media.store_object(key, sanitized)
    metadata = client.head_object(Bucket=bucket, Key=key)
    assert metadata['ServerSideEncryption'] == 'AES256'
    assert metadata['ContentType'] == 'image/jpeg'
    assert metadata['ContentLength'] == len(sanitized)
    assert media.read_object(key) == sanitized
    with Image.open(io.BytesIO(media.read_object(key))) as restored:
        restored.load()
        assert restored.size == (4900, 4800)
        assert not restored.getexif()
    with pytest.raises(HTTPError) as denied:
        urlopen(f'{settings.s3_endpoint}/{bucket}/{key}', timeout=5)
    assert denied.value.code == 403
    assert b'<Code>AccessDenied</Code>' in denied.value.read()
    denied.value.close()


def test_real_object_read_rejects_overflow_instead_of_returning_a_prefix(object_store, monkeypatch):
    client, bucket = object_store
    client.put_object(Bucket=bucket, Key='synthetic/oversized.jpg', Body=b'x' * 129,
                      ContentType='image/jpeg', ServerSideEncryption='AES256')
    monkeypatch.setattr(media, 'MAX_STORED_BYTES', 128)
    with pytest.raises(ValueError, match='Stored image exceeds'):
        media.read_object('synthetic/oversized.jpg')


def test_production_storage_readiness_probe_removes_its_marker(object_store, monkeypatch):
    client, bucket = object_store
    monkeypatch.setattr(settings, 'app_env', 'production')
    readiness.check_storage()
    assert client.list_objects_v2(Bucket=bucket).get('KeyCount', 0) == 0
