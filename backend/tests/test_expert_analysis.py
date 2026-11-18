import copy
import json
import subprocess
import sys
from pathlib import Path

import pytest

from app.expert_analysis import analyze
from app.inspections import DiagnosticResult

ROOT = Path(__file__).resolve().parents[2]
CASES = json.loads((ROOT / 'contracts/analysis-examples.json').read_text())


@pytest.mark.parametrize('case', CASES, ids=[case['name'] for case in CASES])
def test_shared_expert_policy_examples(case):
    result = analyze(case['results'])
    ids = [finding['id'] for finding in result['findings'] if not finding['id'].startswith('coverage.')]
    assert ids == case['expected_rule_ids']
    assert result['policy_version'] == 1
    assert all('confidence' not in finding for finding in result['findings'])


def test_enriched_evidence_survives_validation_and_unknown_fields_do_not_fake_health():
    from datetime import datetime, timezone
    from uuid import uuid4
    row = dict(id=str(uuid4()), check_id='battery.health', definition_version=1, status='PASS',
               started_at=datetime.now(timezone.utc), completed_at=datetime.now(timezone.utc), app_version='test',
               measured_value={'value': '2'}, evidence={'advanced_v1': json.dumps({'temperature_c': '46'})})
    parsed = DiagnosticResult(**row).model_dump(mode='json')
    assert any(item['id'] == 'battery.hot' for item in analyze([parsed])['findings'])
    parsed['evidence']['advanced_v1'] = '{malformed'
    assert not any(item['id'] == 'battery.hot' for item in analyze([parsed])['findings'])


def test_analysis_is_pure_and_latest_attempt_controls_findings():
    row = copy.deepcopy(CASES[1]['results'][0])
    old = copy.deepcopy(row)
    new = row | {'completed_at': '2026-11-18T02:00:00Z', 'measured_value': {'health_code': '2'}}
    assert 'battery.reported_health' not in [finding['id'] for finding in analyze([new, row])['findings']]
    assert row == old


def test_baseline_definitions_and_scoring_are_unchanged():
    subprocess.run([sys.executable, str(ROOT / 'tools/check_diagnostic_preservation.py')], check=True, capture_output=True)
