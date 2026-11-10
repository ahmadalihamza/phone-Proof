"""Flash gates are bound to the capability snapshot, with old-client compatibility."""
import copy
import uuid

from app.registry import DEFINITIONS, expand
from app.db import utcnow


def torch(profile, camera='front'):
    return next(d for d in expand(profile) if d['id'] == f'camera.{camera}.torch')


def test_per_lens_flash_is_expanded_and_independent():
    profile = {'cameras': ['rear', 'front'], 'features': {
        'camera': True, 'camera.rear.flash': True, 'camera.front.flash': False,
    }}
    before = copy.deepcopy(DEFINITIONS)
    assert torch(profile)['capabilities'] == ['camera', 'camera.front.flash']
    assert torch(profile)['version'] == 2
    assert torch(profile, 'rear')['capabilities'] == ['camera', 'camera.rear.flash']
    assert next(d for d in expand(profile) if d['id'] == 'camera.front.capture')['capabilities'] == ['camera']
    assert DEFINITIONS == before


def test_missing_flash_discovery_preserves_old_client_definition():
    for profile in ({'cameras': ['front']}, {'cameras': ['front'], 'features': {'camera': True}}):
        definition = torch(profile)
        assert definition['capabilities'] == ['camera']
        assert definition['version'] == 1


def test_absent_lens_flash_cannot_be_reported_as_working(client, user):
    inspection_id = str(uuid.uuid4())
    base = f'/api/v1/inspections/{inspection_id}'
    assert client.post('/api/v1/inspections', headers=user, json={'id': inspection_id}).status_code == 201
    profile = {'api': 36, 'cameras': ['front'], 'features': {'camera': True, 'camera.front.flash': False}}
    assert client.post(base + '/capabilities', headers=user, json=profile).status_code == 200
    timestamp = utcnow().isoformat()
    row = {'id': str(uuid.uuid4()), 'check_id': 'camera.front.torch', 'definition_version': 2,
           'status': 'PASS', 'evidence': {'fixture': 'synthetic test, no hardware'},
           'started_at': timestamp, 'completed_at': timestamp, 'app_version': 'test-harness'}
    response = client.post(base + '/results/batch', headers=user, json={'results': [row]})
    assert response.status_code == 422
    assert response.json()['detail'] == 'Cannot claim a test on absent hardware/API'
    row['status'] = 'NOT_SUPPORTED'
    row['message'] = 'This lens has no flash unit'
    response = client.post(base + '/results/batch', headers=user, json={'results': [row]})
    assert response.status_code == 200, response.text
