"""Acceptance examples are also executed by the Android JVM suite."""
import copy
import base64
import hashlib
import io
import json
import uuid
from pathlib import Path

import pytest
from pypdf import PdfReader
from sqlalchemy import select

from app.db import SessionLocal
from app.models import Inspection
from app.scoring import CURRENT_RULE_VERSION, cosmetic_score, incomplete_for_report, overall_score, policy, score
from .test_platform import API, batch, cosmetic, create, results_for


EXAMPLES = json.loads((Path(__file__).resolve().parents[2] / 'contracts/scoring-examples.json').read_text())


@pytest.mark.parametrize('example', EXAMPLES, ids=[example['name'] for example in EXAMPLES])
def test_shared_scoring_examples(example):
    definitions = [{'implementation': 'ENABLED', 'critical': False, **definition} for definition in example['definitions']]
    result = score(definitions, example['results'], 2)
    expected = example['expected']
    assert result['functional_score'] == expected['score']
    assert result['coverage_percent'] == expected['coverage']
    assert result['grade'] == expected['grade']
    assert result['critical_failures'] == expected['critical_failures']
    assert result == score(list(reversed(definitions)), list(reversed(example['results'])), 2)


def test_scoring_explains_losses_and_preserves_the_policy_snapshot():
    example = EXAMPLES[0]
    definitions = [{'implementation': 'ENABLED', 'critical': False, **definition} for definition in example['definitions']]
    result = score(definitions, example['results'], 2)
    assert result['point_losses'] == [{'check_id': 'storage.checksum', 'weight': 100, 'points_lost': 28.57}]
    assert sum(category['weight'] for category in policy(2)['categories']) == 100
    result['policy']['categories'][0]['weight'] = 1000
    assert policy(2)['categories'][0]['weight'] == 25
    assert CURRENT_RULE_VERSION == 2
    with pytest.raises(ValueError, match='Unknown scoring'):
        score([], [], 999)
    with pytest.raises(ValueError, match='not covered'):
        score([{'id': 'unknown', 'category': 'unconfigured', 'weight': 1, 'critical': False, 'implementation': 'ENABLED'}], [], 2)


def test_cosmetic_and_overall_grades_do_not_hide_a_bad_functional_score():
    finding = {'defect': 'front_glass_crack', 'severity': 'severe'}
    assert cosmetic_score([finding], True, 2) == {'score': 65, 'grade': 'D', 'rule_version': 2}
    assert cosmetic_score([], False, 2)['score'] is None
    assert cosmetic_score([], True, 2)['score'] == 100
    assert overall_score(40, 100, 2)['score'] == 40
    assert overall_score(100, 65, 2)['grade'] == 'D'
    assert overall_score(100, None, 2)['score'] is None
    assert overall_score(100, 100, 1)['score'] is None


def test_critical_publication_checks_follow_the_pinned_policy():
    definitions = [{'id': check_id, 'weight': 1, 'critical': False, 'implementation': 'ENABLED'}
                   for check_id in ('charging.connect', 'audio.mic.capture', 'camera.0.capture')]
    results = [{'check_id': definition['id'], 'status': 'INCONCLUSIVE'} for definition in definitions]
    assert incomplete_for_report(definitions, results, 1) == []
    assert incomplete_for_report(definitions, results, 2) == sorted(definition['id'] for definition in definitions)
    for status in ('NOT_SUPPORTED', 'NOT_ACCESSIBLE', 'FAIL', 'PASS'):
        assert incomplete_for_report(definitions, [result | {'status': status} for result in results], 2) == []


def test_unresolved_critical_check_blocks_publication_without_spending_trial(client, user):
    from app.billing import Usage
    from sqlalchemy import func
    inspection_id, profile = create(client, user)
    rows = results_for(client, user, inspection_id, profile)
    for result in rows:
        if result['check_id'] == 'charging.connect':
            result['status'] = 'INCONCLUSIVE'
    batch(client, user, inspection_id, rows)
    response = client.post(f'{API}/inspections/{inspection_id}/finalize', headers=user)
    assert response.status_code == 409
    assert 'charging.connect' in response.json()['detail']['checks']
    with SessionLocal() as db:
        assert db.scalar(select(func.count()).select_from(Usage)) == 0


def test_signed_report_shows_charging_cap_and_overall_grade(client, user):
    inspection_id, profile = create(client, user)
    rows = results_for(client, user, inspection_id, profile)
    for result in rows:
        if result['check_id'] == 'charging.connect':
            result['status'] = 'FAIL'
    batch(client, user, inspection_id, rows)
    cosmetic(client, user, inspection_id)
    response = client.post(f'{API}/inspections/{inspection_id}/finalize', headers=user)
    assert response.status_code == 200, response.text
    report = response.json()
    assert report['payload']['score']['functional_score'] == 69
    assert report['payload']['overall']['grade'] == 'D'
    page = client.get('/r/' + report['public_token'])
    assert 'Charging connection failure' in page.text
    assert 'maximum functional score 69' in page.text
    pdf = client.get(API + '/reports/' + report['id'] + '/pdf', headers=user)
    output = Path(__file__).resolve().parents[2] / 'output/pdf/scoring-v2-cap-test-fixture.pdf'
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_bytes(pdf.content)
    text = ''.join(page.extract_text() for page in PdfReader(io.BytesIO(pdf.content)).pages)
    assert 'Charging connection failure' in text
    assert 'maximum functional score 69' in text


def test_capability_retry_preserves_definitions_and_scoring_policy(client, user, monkeypatch):
    inspection_id, profile = create(client, user)
    path = f'{API}/inspections/{inspection_id}'
    before = client.get(path + '/required-checks', headers=user).json()
    assert client.get(path, headers=user).json()['score_rule_version'] == 2
    monkeypatch.setattr('app.inspections.expand', lambda _: [])
    response = client.post(path + '/capabilities', headers=user, json=profile)
    assert response.status_code == 200
    assert response.json()['definition_count'] == len(before)
    assert client.get(path + '/required-checks', headers=user).json() == before
    assert client.get(API + '/scoring/rules').json()['current_version'] == 2
    # The caller cannot select more favorable rules during create or rebind.
    assert client.post(API + '/inspections', headers=user, json={
        'id': str(uuid.uuid4()), 'score_rule_version': 1
    }).status_code == 422


@pytest.mark.parametrize('version', [1, 2])
def test_report_freezes_pinned_scoring_and_renders_both_versions(client, user, version):
    inspection_id, profile = create(client, user)
    if version == 1:
        with SessionLocal() as db:
            item = db.scalar(select(Inspection).where(Inspection.id == inspection_id))
            item.score_rule_version = 1
            db.commit()
    batch(client, user, inspection_id, results_for(client, user, inspection_id, profile))
    cosmetic(client, user, inspection_id)
    response = client.post(f'{API}/inspections/{inspection_id}/finalize', headers=user)
    assert response.status_code == 200, response.text
    report = response.json()
    payload = report['payload']
    assert payload['score']['rule_version'] == version
    assert payload['cosmetic']['grade'] == 'A'
    if version == 2:
        assert payload['score']['policy'] == policy(2)
        assert payload['overall']['score'] == 100
        assert payload['overall']['grade'] == 'A'
    else:
        assert 'overall' not in payload
        assert 'categories' not in payload['score']
    public = client.get(API + '/verify/' + report['public_token']).json()
    assert public['status'] == 'VALID'
    assert public['payload']['score']['rule_version'] == version
    page = client.get('/r/' + report['public_token'])
    assert page.status_code == 200
    pdf = client.get(API + '/reports/' + report['id'] + '/pdf', headers=user)
    assert pdf.status_code == 200
    text = ''.join(page.extract_text() for page in PdfReader(io.BytesIO(pdf.content)).pages)
    assert ('Category weighting' in text) == (version == 2)
    assert ('Overall score' in text) == (version == 2)
    again = client.post(f'{API}/inspections/{inspection_id}/finalize', headers=user).json()
    assert again['hash'] == report['hash']
    assert again['payload'] == payload


@pytest.mark.parametrize('long_metadata', [False, True])
def test_old_report_template_remains_renderable(long_metadata):
    from types import SimpleNamespace
    from cryptography.hazmat.primitives import serialization
    from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
    from app.reports import canonical, generate_pdf, verify_signature
    payload = {'device': {'manufacturer': 'Synthetic fixture', 'model': 'NOT A PHONE INSPECTION', 'release': '16', 'api': '36'},
               'finalized_at': '2026-10-10T00:00:00Z', 'checks': [], 'masked_imei': None, 'pta_status': 'NOT_VERIFIED',
               'score': {'functional_score': 100, 'grade': 'A', 'coverage_percent': 100, 'critical_failures': []},
               'cosmetic': {'score': 100, 'method': 'Manual inspector review', 'findings': [], 'ai_status': 'UNAVAILABLE'},
               'disclaimer': 'SYNTHETIC LEGACY REPORT FIXTURE'}
    if long_metadata:
        payload['checks'] = [{'check_id': 'camera.0.sizes', 'category': 'camera', 'title': 'Long camera metadata fixture',
                              'status': 'PASS', 'method': 'AUTOMATIC', 'definition_version': 1,
                              'completed_at': '2026-10-10T00:00:00Z',
                              'measured_value': {'sizes_first': 'METADATA_START ' + '1920x1080; ' * 350,
                                                 'sizes_last': '1280x720; ' * 350 + ' METADATA_END'}}]
    key = Ed25519PrivateKey.generate()
    body = canonical(payload)
    digest = hashlib.sha256(body.encode()).hexdigest()
    report = SimpleNamespace(id=str(uuid.uuid4()), public_token='synthetic-legacy-token', payload=copy.deepcopy(payload),
                             canonical_json=body, hash=digest, key_version='test-fixture',
                             public_key=base64.b64encode(key.public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw)).decode(),
                             signature=base64.b64encode(key.sign(bytes.fromhex(digest))).decode())
    assert verify_signature(report)
    text = ''.join(page.extract_text() for page in PdfReader(io.BytesIO(generate_pdf(report))).pages)
    assert 'Server signature verified' in text
    assert 'SYNTHETIC LEGACY REPORT FIXTURE' in text
    assert 'Overall score' not in text
    if long_metadata:
        assert 'METADATA_START' in text and 'METADATA_END' in text
