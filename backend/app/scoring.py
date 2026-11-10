"""Versioned scoring shared with Android. Signed reports retain the full policy."""
import copy
import json
from datetime import datetime, timezone
from decimal import Decimal, ROUND_HALF_UP, localcontext
from pathlib import Path


RULEBOOK = json.loads((Path(__file__).resolve().parents[2] / 'contracts/scoring-rules.json').read_text())
CURRENT_RULE_VERSION = RULEBOOK['current_version']


def policy(version):
    for rule in RULEBOOK['rules']:
        if rule['version'] == version:
            return copy.deepcopy(rule)
    raise ValueError(f'Unknown scoring rule version: {version}')


def rounded(value):
    return float(value.quantize(Decimal('0.01'), rounding=ROUND_HALF_UP))


def grade(value, rule):
    if value is None:
        return None
    return next(band['grade'] for band in rule['grade_bands'] if value >= band['minimum'])


def failure_cap(definition, rule):
    matches = [cap for cap in rule['failure_caps']
               if (definition['id'] == cap['prefix'] if not cap['suffix'] else
                   definition['id'].startswith(cap['prefix']) and definition['id'].endswith(cap['suffix']))]
    if matches:
        cap = min(matches, key=lambda item: item['maximum'])
        return {'check_id': definition['id'], 'maximum_score': cap['maximum'], 'reason': cap['reason']}
    if definition['critical']:
        return {'check_id': definition['id'], 'maximum_score': rule['default_critical_cap'],
                'reason': 'Critical functional failure'}
    return None


def latest_attempts(results):
    selected = {}
    times = {}
    for result in results:
        timestamp = result.get('completed_at')
        instant = datetime.fromisoformat(timestamp.replace('Z', '+00:00')) if timestamp else datetime.min
        if instant.tzinfo is None:
            instant = instant.replace(tzinfo=timezone.utc)
        check_id = result['check_id']
        if check_id not in times or instant >= times[check_id]:
            selected[check_id] = result
            times[check_id] = instant
    return selected


def incomplete_for_report(definitions, results, version):
    rule = policy(version)
    by_id = latest_attempts(results)
    missing = []
    for definition in sorted(definitions, key=lambda item: item['id']):
        if definition['weight'] <= 0 or definition['implementation'] != 'ENABLED':
            continue
        result = by_id.get(definition['id'])
        critical = failure_cap(definition, rule) is not None
        if not result or (critical and result['status'] in ('INCONCLUSIVE', 'SKIPPED_BY_USER')):
            missing.append(definition['id'])
    return missing


def score(definitions, results, version=1):
    rule = policy(version)
    by_id = latest_attempts(results)
    weighted = [definition for definition in sorted(definitions, key=lambda item: item['id'])
                if definition['weight'] > 0 and definition['implementation'] == 'ENABLED']
    applicable = [definition for definition in weighted
                  if by_id.get(definition['id'], {}).get('status') != 'NOT_SUPPORTED']
    measured = [definition for definition in applicable
                if by_id.get(definition['id'], {}).get('status') in ('PASS', 'FAIL')]
    failed = [definition for definition in measured if by_id[definition['id']]['status'] == 'FAIL']
    measured_ids = {definition['id'] for definition in measured}
    missing = [definition['id'] for definition in applicable if definition['id'] not in measured_ids]
    total = sum(definition['weight'] for definition in measured)
    possible = sum(definition['weight'] for definition in applicable)
    earned = sum(definition['weight'] for definition in measured if by_id[definition['id']]['status'] == 'PASS')

    # This algorithm is retained byte-for-byte in meaning for pre-v2 inspections.
    if rule['algorithm'] == 'check_weighted':
        critical = [definition['id'] for definition in failed if definition['critical']]
        value = round(earned * 100 / total, 2) if total else None
        if critical and value is not None:
            value = min(value, rule['default_critical_cap'])
        return {'functional_score': value, 'rule_version': version, 'measured_weight': total,
                'applicable_weight': possible, 'coverage_percent': round(100 * total / possible, 2) if possible else 0,
                'incomplete_checks': missing, 'critical_failures': critical,
                'point_losses': [{'check_id': definition['id'], 'weight': definition['weight']} for definition in failed],
                'grade': grade(value, rule)}

    members = {member for category in rule['categories'] for member in category['members']}
    if any(definition.get('category') not in members for definition in weighted):
        raise ValueError('Scored category is not covered by the scoring policy')
    categories = []
    losses = []
    caps = [cap for definition in failed if (cap := failure_cap(definition, rule))]
    with localcontext() as context:
        context.prec = 34
        decimal = lambda value: Decimal(str(value))
        measured_share = applicable_share = earned_share = coverage_share = Decimal(0)
        for category in rule['categories']:
            checks = [definition for definition in applicable if definition['category'] in category['members']]
            completed = [definition for definition in checks if definition['id'] in measured_ids]
            available_weight = sum((decimal(definition['weight']) for definition in checks), Decimal(0))
            tested_weight = sum((decimal(definition['weight']) for definition in completed), Decimal(0))
            passed_weight = sum((decimal(definition['weight']) for definition in completed
                                 if by_id[definition['id']]['status'] == 'PASS'), Decimal(0))
            share = decimal(category['weight'])
            if available_weight:
                applicable_share += share
                coverage_share += share * tested_weight / available_weight
            if tested_weight:
                measured_share += share
                earned_share += share * passed_weight / tested_weight
            categories.append({'id': category['id'], 'title': category['title'], 'weight_percent': category['weight'],
                               'applicable_weight': float(available_weight), 'measured_weight': float(tested_weight),
                               'score': rounded(100 * passed_weight / tested_weight) if tested_weight else None,
                               'coverage_percent': rounded(100 * tested_weight / available_weight) if available_weight else None})
            for definition in completed:
                if by_id[definition['id']]['status'] == 'FAIL':
                    losses.append((definition, share * decimal(definition['weight']) / tested_weight))
        uncapped = rounded(100 * earned_share / measured_share) if measured_share else None
        value = min([uncapped] + [cap['maximum_score'] for cap in caps]) if uncapped is not None else None
        point_losses = [{'check_id': definition['id'], 'weight': definition['weight'],
                         'points_lost': rounded(100 * loss / measured_share)} for definition, loss in losses]
        coverage = rounded(100 * coverage_share / applicable_share) if applicable_share else 0
    return {'functional_score': value, 'uncapped_score': uncapped, 'rule_version': version, 'policy': rule,
            'measured_weight': total, 'applicable_weight': possible, 'coverage_percent': coverage,
            'incomplete_checks': missing, 'critical_failures': [cap['check_id'] for cap in caps],
            'point_losses': sorted(point_losses, key=lambda item: item['check_id']), 'categories': categories,
            'caps': caps, 'grade': grade(value, rule)}


def cosmetic_score(findings, reviewed, version):
    rule = policy(version)
    value = max(0, 100 - sum(rule['cosmetic_deductions'][finding['severity']] for finding in findings)) if reviewed else None
    if value is not None and any(finding['severity'] == 'severe' and 'crack' in finding['defect'] for finding in findings):
        value = min(value, rule['severe_crack_cap'])
    return {'score': value, 'grade': grade(value, rule), 'rule_version': version}


def overall_score(functional, cosmetic, version):
    rule = policy(version)
    value = min(functional, cosmetic) if rule['overall_method'] == 'minimum' and functional is not None and cosmetic is not None else None
    return {'score': value, 'grade': grade(value, rule), 'rule_version': version,
            'method': 'Lower of functional and cosmetic scores' if rule['overall_method'] == 'minimum' else 'Not combined'}
