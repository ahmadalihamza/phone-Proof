"""Sanitized media uses one bounded object policy on local disk and S3."""
import io

import numpy as np
import pytest
from PIL import Image

from app import media
from app.config import settings


def test_expanded_sanitized_image_round_trips_s3_without_truncation(monkeypatch):
    image = Image.fromarray(np.random.default_rng(17).integers(0, 256, (4800, 4900, 3), dtype=np.uint8))
    incoming = io.BytesIO()
    image.save(incoming, format='JPEG', quality=40)
    assert len(incoming.getvalue()) <= media.MAX_BYTES
    sanitized, quality, _ = media.quality_check(incoming.getvalue())
    assert quality['accepted'], quality
    assert media.MAX_BYTES < len(sanitized) <= media.MAX_STORED_BYTES
    objects = {}
    bodies = []
    class ObjectStore:
        def put_object(self, **kwargs):
            objects[kwargs['Key']] = kwargs['Body']
        def get_object(self, **kwargs):
            body = io.BytesIO(objects[kwargs['Key']])
            bodies.append(body)
            return {'Body': body, 'ContentLength': len(objects[kwargs['Key']])}
    monkeypatch.setattr(settings, 's3_bucket', 'synthetic-private-bucket')
    monkeypatch.setattr(media, 'storage_client', ObjectStore)
    media.store_object('fixture.jpg', sanitized)
    restored = media.read_object('fixture.jpg')
    assert restored == sanitized
    assert bodies[0].closed
    with Image.open(io.BytesIO(restored)) as decoded:
        decoded.load()
        assert decoded.size == (4900, 4800)
        assert not decoded.getexif()


@pytest.mark.parametrize('declared_length', [None, 129])
def test_oversized_s3_object_is_rejected_and_body_closed(monkeypatch, declared_length):
    body = io.BytesIO(b'x' * 129)
    class ObjectStore:
        def get_object(self, **kwargs):
            return {'Body': body, **({'ContentLength': declared_length} if declared_length else {})}
    monkeypatch.setattr(settings, 's3_bucket', 'synthetic-private-bucket')
    monkeypatch.setattr(media, 'MAX_STORED_BYTES', 128)
    monkeypatch.setattr(media, 'storage_client', ObjectStore)
    with pytest.raises(ValueError, match='Stored image exceeds'):
        media.read_object('fixture.jpg')
    assert body.closed


def test_s3_body_closes_on_read_failure(monkeypatch):
    class FailedBody(io.BytesIO):
        def read(self, *args):
            raise OSError('Synthetic storage failure')
    body = FailedBody()
    class ObjectStore:
        def get_object(self, **kwargs):
            return {'Body': body}
    monkeypatch.setattr(settings, 's3_bucket', 'synthetic-private-bucket')
    monkeypatch.setattr(media, 'storage_client', ObjectStore)
    with pytest.raises(OSError):
        media.read_object('fixture.jpg')
    assert body.closed


def test_local_reads_and_sanitization_obey_stored_object_bound(monkeypatch, tmp_path):
    monkeypatch.setattr(settings, 's3_bucket', '')
    monkeypatch.setattr(settings, 'media_dir', str(tmp_path))
    monkeypatch.setattr(media, 'MAX_STORED_BYTES', 128)
    with pytest.raises(ValueError, match='Stored image exceeds'):
        media.store_object('oversized.jpg', b'x' * 129)
    (tmp_path / 'legacy.jpg').write_bytes(b'x' * 129)
    with pytest.raises(ValueError, match='Stored image exceeds'):
        media.read_object('legacy.jpg')
    raw = io.BytesIO()
    Image.new('RGB', (800, 800), 'gray').save(raw, 'JPEG')
    with pytest.raises(ValueError, match='Sanitized image exceeds'):
        media.quality_check(raw.getvalue())
