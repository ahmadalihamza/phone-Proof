"""Versioned deterministic advisory rules shared with the Android client."""
import json
import math
from pathlib import Path

POLICY = json.loads((Path(__file__).resolve().parents[2] / 'contracts/analysis-policy.json').read_text())


def fields(result):
    values = dict(result.get('measured_value', {}))
    try:
        extra = json.loads(result.get('evidence', {}).get('advanced_v1', '{}'))
        if isinstance(extra, dict) and all(isinstance(k, str) and isinstance(v, str) for k, v in extra.items()):
            values.update(extra)
    except (ValueError, TypeError):
        pass
    return values


def matches(actual, condition):
    if actual is None:
        return False
    expected, operation = condition['value'], condition['op']
    if operation == 'eq':
        return actual == expected
    if operation == 'in':
        return actual in expected.split(',')
    try:
        number, threshold = float(actual), float(expected)
    except (TypeError, ValueError):
        return False
    if not math.isfinite(number):
        return False
    return number >= threshold if operation == 'gte' else number < threshold if operation == 'lt' else False


def analyze(results):
    latest = {}
    for result in results:
        old = latest.get(result['check_id'])
        if old is None or result.get('completed_at', '') >= old.get('completed_at', ''):
            latest[result['check_id']] = result
    findings = []
    for rule in POLICY['rules']:
        result = latest.get(rule['source'])
        if result is None:
            continue
        values = fields(result)
        if not all(matches(values.get(condition['key']), condition) for condition in rule['conditions']):
            continue
        finding = {key: rule[key] for key in ('id', 'category', 'title', 'severity', 'kind', 'interpretation',
                                            'confidence_basis', 'possible_explanations', 'further_inspection', 'limitations')}
        finding.update(diagnostic_ids=[rule['source']], policy_version=POLICY['version'],
                       measurements={c['key']: values[c['key']] for c in rule['conditions']})
        findings.append(finding)
    for result in sorted(latest.values(), key=lambda row: row['check_id']):
        if result['status'] != 'FAIL':
            continue
        findings.append(dict(id='functional.' + result['check_id'], category=result['check_id'].split('.')[0],
                             title='Recorded problem · ' + result['check_id'], severity='WARNING', kind='FUNCTIONAL_PROBLEM',
                             diagnostic_ids=[result['check_id']], measurements=result.get('measured_value', {}),
                             interpretation=result.get('message', ''), confidence_basis='Stored ' + result.get('method', '') + ' diagnostic outcome',
                             possible_explanations=['Observed operation or inspector-confirmed issue'],
                             further_inspection='Review the original evidence and repeat the relevant inspection.',
                             limitations='Scope is limited to the recorded operation and its declared method.', policy_version=POLICY['version']))
    for category in ('battery', 'performance', 'storage', 'system', 'thermal', 'sensors', 'connectivity', 'camera'):
        prefix = {'sensors': 'sensor', 'connectivity': 'network'}.get(category, category) + '.'
        entries = [row for key, row in latest.items() if key.startswith(prefix)]
        unverified = sum(row['status'] not in ('PASS', 'FAIL') for row in entries)
        incomplete = not entries or any(row['status'] in ('INCONCLUSIVE', 'SKIPPED_BY_USER') for row in entries)
        kind = 'INCONCLUSIVE' if incomplete else 'UNAVAILABLE' if unverified == len(entries) else 'OBSERVATION'
        findings.append(dict(id='coverage.' + category, category=category, title=category + ' observations', severity='INFO', kind=kind,
                             diagnostic_ids=sorted(row['check_id'] for row in entries),
                             measurements={'recorded_count': str(len(entries)), 'unverified_count': str(unverified)},
                             interpretation=f'{unverified} of {len(entries)} recorded observations are unavailable or inconclusive.' if entries else 'No observations recorded.',
                             confidence_basis='Recorded evidence availability', possible_explanations=['Device capability, permission and inspection prerequisites vary'],
                             further_inspection='Review individual results and unverified areas.',
                             limitations='Recorded metadata alone does not establish hardware condition.', policy_version=POLICY['version']))
    return dict(policy_version=POLICY['version'], method='Deterministic expert rules', findings=findings)
