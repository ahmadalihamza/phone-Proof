"""Lost acknowledgements recover committed identities without editing history."""
import copy
import uuid

import pytest
from sqlalchemy import func, select

from app.db import SessionLocal
from app.models import Audit, Inspection, Result
from tests.test_platform import API, batch, create, results_for, cosmetic, image_bytes
from app.db import utcnow
from app.media import VIEWS


@pytest.mark.parametrize('closed_status', ['FINALIZED', 'CANCELLED'])
def test_closed_inspection_acknowledges_exact_retry_without_new_rows(client, user, closed_status):
    inspection_id, profile = create(client, user)
    row = results_for(client, user, inspection_id, profile)[0]
    batch(client, user, inspection_id, [row])
    with SessionLocal() as db:
        db.get(Inspection, inspection_id).status = closed_status
        db.commit()
        audit_count = db.scalar(select(func.count()).select_from(Audit))
    response = client.post(f'{API}/inspections/{inspection_id}/results/batch', headers=user, json={'results': [row]})
    assert response.status_code == 200, response.text
    assert response.json()['accepted_ids'] == [row['id']]
    with SessionLocal() as db:
        assert db.scalar(select(func.count()).select_from(Result)) == 1
        assert db.scalar(select(func.count()).select_from(Audit)) == audit_count
        assert db.get(Inspection, inspection_id).status == closed_status


def test_finalized_retry_cannot_change_payload_or_append_new_results(client, user):
    inspection_id, profile = create(client, user)
    row = results_for(client, user, inspection_id, profile)[0]
    batch(client, user, inspection_id, [row])
    with SessionLocal() as db:
        db.get(Inspection, inspection_id).status = 'FINALIZED'
        db.commit()
    changed = copy.deepcopy(row)
    changed['message'] = 'This mutation must never reach the historical result'
    for rows in ([changed], [row | {'id': str(uuid.uuid4())}], [row, row | {'id': str(uuid.uuid4())}]):
        response = client.post(f'{API}/inspections/{inspection_id}/results/batch', headers=user, json={'results': rows})
        assert response.status_code == 409, response.text
    with SessionLocal() as db:
        assert db.scalar(select(func.count()).select_from(Result)) == 1
        assert db.get(Result, row['id']).payload['message'] == row['message']


def test_finalized_capability_retry_only_accepts_identical_snapshot(client, user):
    inspection_id, profile = create(client, user)
    with SessionLocal() as db:
        db.get(Inspection, inspection_id).status = 'FINALIZED'
        db.commit()
    response = client.post(f'{API}/inspections/{inspection_id}/capabilities', headers=user, json=profile)
    assert response.status_code == 200, response.text
    changed = copy.deepcopy(profile)
    changed['api'] = 35
    response = client.post(f'{API}/inspections/{inspection_id}/capabilities', headers=user, json=changed)
    assert response.status_code == 409
    with SessionLocal() as db:
        assert db.get(Inspection, inspection_id).capabilities == profile


def test_finalized_photo_exact_retry_recovers_acknowledgement_only(client, user):
    inspection_id, _ = create(client, user)
    image_ids, nonce = cosmetic(client, user, inspection_id)
    with SessionLocal() as db:
        db.get(Inspection, inspection_id).status = 'FINALIZED'
        db.commit()
    fields = {'view': VIEWS[0], 'scanner_device_id': 'scanner-test-fixture', 'capture_nonce': nonce,
              'captured_at': utcnow().isoformat()}
    response = client.post(f'{API}/inspections/{inspection_id}/images', headers=user, data=fields,
                           files={'file': ('fixture.jpg', image_bytes(0), 'image/jpeg')})
    assert response.status_code == 201, response.text
    assert response.json()['id'] == image_ids[0]
    changed = client.post(f'{API}/inspections/{inspection_id}/images', headers=user, data=fields,
                         files={'file': ('fixture.jpg', image_bytes(100), 'image/jpeg')})
    assert changed.status_code == 409
    assert len(client.get(f'{API}/inspections/{inspection_id}/images', headers=user).json()) == 12
