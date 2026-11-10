"""Generate the complete, non-destructive diagnostic-to-workflow inventory."""
import argparse
from collections import Counter
import csv
import io
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
AUTOMATIC_HANDLERS = {
    'system', 'feature', 'display', 'battery', 'thermal', 'sensor_metadata', 'camera_metadata',
    'audio_info', 'network', 'storage', 'sqlite', 'cpu', 'memory', 'keystore',
}
OPTIONAL_TOUCH = {'three_finger', 'long_press', 'rapid_tap', 'interval', 'jitter'}


def placement(d):
    handler = d['handler']
    field = d['parameters'].get('field')
    if d['implementation'] != 'ENABLED':
        return 'DISABLED', 'Disabled', 'Retain disabled; no performed result', d['description']
    if handler == 'backend':
        return 'ADVANCED_CONNECTION', 'Server connection', 'Real configured health request', 'Service reachability is not radio hardware health'
    if d['mode'] == 'AUTOMATIC' and handler in AUTOMATIC_HANDLERS:
        reuse = 'One completed private I/O round trip for six verified operations' if handler == 'storage' else 'Collect permitted platform evidence in the background'
        return 'AUTOMATIC', 'Automatic checks', reuse, 'Keep metadata unscored and distinguish a reported field from working hardware'
    if handler == 'display_guided':
        return 'GROUPED_MANUAL', 'Display', 'One immersive sequence; final per-pattern and defect assessment', 'Pattern rendering cannot prove physical pixel health; only explicit attended observations can pass'
    if handler == 'touch':
        test = d['parameters']['test']
        if test in OPTIONAL_TOUCH:
            return 'GROUPED_OPTIONAL', 'Touchscreen', 'Optional criterion within the same touch workflow', 'Keep its distinct event evidence; unobserved criteria remain skipped or inconclusive'
        return 'GROUPED_GUIDED', 'Touchscreen', 'One immersive trace with separately verified event/coverage criteria', 'A visited grid does not prove a continuous swipe or simultaneous fingers; do not interpolate missing observations'
    if handler in {'camera_capture', 'torch'}:
        return 'GROUPED_GUIDED', 'Camera {camera_id}', 'One workflow per exposed lens, retaining preview/capture/flash', 'Only a real stream, decoded photo or explicit visible flash confirmation supports the corresponding result'
    if handler == 'microphone':
        return 'GROUPED_MEASURED', 'Sound & microphone', 'One actual built-in PCM capture window for four criteria', 'Capture and signal statistics are different; quiet/clipped input does not prove a microphone defect'
    if handler == 'speaker':
        return 'GROUPED_MANUAL', 'Sound & microphone', 'One tone sequence, retaining each frequency observation', 'Each tone requires actual stable built-in output and human confirmation; keep per-tone outcomes'
    if handler == 'buttons':
        return 'GROUPED_GUIDED', 'Buttons & vibration', 'One controls workflow with distinct physical interactions', 'Do not infer power, vibration or a different button from a single observed key'
    if handler == 'sensor_sample':
        detail = {'response': 'Fresh finite events', 'continuity': 'Multiple monotonic events', 'variation': 'Actual nonzero variation'}[field]
        return 'GROUPED_MEASURED', 'Motion & sensors', 'One real window per physical sensor; ' + detail, 'Constant or absent events may be correct; no calibrated accuracy or motion-response claim'
    if handler == 'charging':
        return 'GROUPED_MEASURED', 'Charging', 'One plug/unplug workflow with two actual state transitions', 'Initial connection metadata is not evidence of a transition or charging wattage'
    if handler == 'biometric':
        return 'GROUPED_GUIDED', 'Face or fingerprint', 'Real BiometricPrompt authentication', 'Missing enrollment or a rejected authentication does not prove a broken sensor'
    if handler in {'location', 'bluetooth_scan'}:
        reuse = 'One fresh fix for fix/accuracy; actual GNSS callback for satellites' if handler == 'location' else 'Actual nearby advertisement observation'
        return 'ADVANCED_OBSERVATION', 'Nearby devices & location', reuse, 'Permission/environment-dependent observation; absence of a fix/advertisement is not radio failure'
    return 'ADVANCED_UNREVIEWED', 'Advanced: ' + handler, 'Retain for explicit implementation review', 'Unknown criteria must never be silently dropped or reported passed'


def current_actions(d):
    """Minimum app control actions from the baseline's Continue path, excluding stimuli/permissions."""
    if d['implementation'] != 'ENABLED':
        return 0, 'Not runnable'
    if d['handler'] == 'backend':
        return 2, 'Device info details -> run; absent from automatic engine'
    if d['mode'] == 'AUTOMATIC':
        return 0, 'Background operation; optional manual rerun is separate'
    handler = d['handler']
    if handler in {'display_guided', 'touch'}:
        return (2 if d['weight'] > 0 else 3), 'Start -> observe/gesture -> explicit result'
    if handler == 'speaker':
        return 3, 'Start -> play actual tone -> confirm'
    if handler in {'camera_capture', 'torch'}:
        extra = d['parameters']['test'] in {'capture', 'torch'}
        return (3 if extra else 2), 'Start -> actual lens operation -> confirm'
    if handler == 'buttons':
        return (3 if d['parameters']['test'] in {'vibration', 'power'} else 2), 'Start -> physical interaction -> confirm'
    return (1 if d['weight'] > 0 else 2), 'Start actual probe; permission/system prompts and physical stimuli are additional'


def cap_for(d, rule):
    matches = [c for c in rule['failure_caps'] if
        (d['id'] == c['prefix'] if not c['suffix'] else d['id'].startswith(c['prefix']) and d['id'].endswith(c['suffix']))]
    if matches:
        return min(c['maximum'] for c in matches)
    return rule['default_critical_cap'] if d['critical'] else ''


def render():
    definitions = json.loads((ROOT / 'contracts/diagnostics.json').read_text(encoding='utf-8'))
    rulebook = json.loads((ROOT / 'contracts/scoring-rules.json').read_text(encoding='utf-8'))
    rule = next(r for r in rulebook['rules'] if r['version'] == rulebook['current_version'])
    rows = []
    for d in sorted(definitions, key=lambda x: x['id']):
        disposition, group, replacement, reason = placement(d)
        actions, action_detail = current_actions(d)
        cap = cap_for(d, rule) if d['weight'] > 0 else ''
        rows.append({'id': d['id'], 'name': d['title'], 'category': d['category'], 'method': d['handler'],
            'mode': d['mode'], 'implementation': d['implementation'], 'definition_version': d['version'],
            'purpose': d['description'], 'current_app_actions_min': actions, 'current_interaction': action_detail,
            'min_android_api': d['min_api'], 'permissions': '; '.join(d['permissions']),
            'capabilities': '; '.join(d['capabilities']), 'evidence_source': d['evidence_type'],
            'pass_criterion': d['pass_criteria'], 'fail_criterion': d['fail_criteria'], 'weight': d['weight'],
            'v2_failure_cap': cap, 'publication_requires_conclusive_or_unavailable': bool(cap),
            'disposition': disposition, 'workflow': group, 'replacement': replacement, 'justification': reason,
            'contract_preservation': 'Retain ID, definition version, weight and historical scoring; no deletion'})
    if any(r['disposition'] == 'ADVANCED_UNREVIEWED' for r in rows):
        raise ValueError('An enabled diagnostic needs an explicit inventory review')
    stream = io.StringIO(newline='')
    writer = csv.DictWriter(stream, fieldnames=list(rows[0]), lineterminator='\n')
    writer.writeheader()
    writer.writerows(rows)
    enabled = [d for d in definitions if d['implementation'] == 'ENABLED']
    dispositions = Counter(r['disposition'] for r in rows)
    summary = '\n'.join(f'| {key} | {value} |' for key, value in sorted(dispositions.items()))
    lines = [
        '# Diagnostic workflow inventory — 27 October 2026', '',
        'Generated by `python tools/generate_workflow_inventory.py`. Baseline: `23b1a9b` before the workflow repairs. '
        'Every registry template is retained below and in [the full CSV](diagnostic-workflow-inventory.csv). '
        'Grouping changes navigation and probe reuse, not stable diagnostic identities or scoring weights.', '',
        f'**{len(definitions)} templates: {len(enabled)} enabled, {len(definitions)-len(enabled)} disabled; '
        f'{sum(d["weight"] > 0 for d in enabled)} weighted and {sum(d["weight"] == 0 for d in enabled)} unscored.** '
        'Camera templates expand for actual exposed IDs; counts are definitions, not certified hardware results.', '',
        '| Disposition | Templates |', '|---|---:|', summary, '',
        '## Progress units and preserved scoring', '',
        '- Automatic templates: 228. Two lenses add 16 camera metadata entries; the backend-only handler is excluded from the Android engine: **243 actual automatic-definition invocations** in the former UI.',
        '- Weighted templates: 61. A second lens adds preview/capture/torch: **64 functional criteria**. These are not 64 independent user workflows.',
        '- The old pending-sync number counts stored result attempts, including retries and repeated unavailable observations; it does not count reports or remaining manual stages.',
        '- New presentation groups: one Display, one Touchscreen, one per exposed Camera, Sound & microphone, Buttons & vibration, Motion & sensors, Charging and Face/fingerprint. Automatic metadata and advanced connection/radio observations remain separate.',
        '- A recorded/skipped/unavailable criterion is not a measured pass. Preserve policy v1/v2, critical publication guards and measured coverage. Optional touch gestures not actually observed remain unmeasured and can reduce historical weighted coverage.',
        '- The action counts are minimum app-control actions in the former UI, not event counts or time estimates. Permission prompts, finger movements, cable changes, biometric prompts and physical observations are additional.', '',
        '## Evidence rules for shared probes', '',
        '| Probe | Safe reuse | Missing evidence |', '|---|---|---|',
        '| Private storage | Six original results after the same complete create/write/flush/read/checksum/overwrite/delete round trip | Partial/failing/unknown evidence is not projected as success; execute the remaining criteria |',
        '| Microphone | One real stable built-in PCM window; capture and signal indicators evaluated separately | Quiet/clipped input can prove capture while leaving indicators inconclusive; route loss/interruption cannot pass |',
        '| Sensor | One real window per sensor type, independently evaluated response/continuity/variation | Stale/future samples do not establish freshness; one event does not prove continuity; constant values do not prove variation |',
        '| GNSS | One actual fresh fix for fix/accuracy | Missing accuracy is unavailable; satellites still need their real callback |', '',
        'Display outcome mapping remains human-confirmed and scoped to attended patterns/selected defects. '
        'Touch outcomes require actual observed events for each criterion, with physical/excluded geometry in evidence. '
        'A completed stage never automatically means all constituent checks passed. '
        'The 50 disabled templates remain disabled; no new physical-health inference is introduced.', '',
        '## Every registry template', '',
        'The CSV also includes original purpose, permissions/capabilities, pass/fail criteria, interaction detail and pinned-v2 cap implications. '
        'No template is removed. A camera placeholder below means one independent set per exposed lens.', '',
        '| ID | Name | Method | App actions min | Weight | Workflow / disposition |', '|---|---|---|---:|---:|---|',
    ]
    def cell(value):
        return str(value).replace('|', '/').replace('\n', ' ')
    for r in rows:
        lines.append('| ' + ' | '.join(cell(v) for v in (r['id'], r['name'], r['method'], r['current_app_actions_min'],
            r['weight'], r['workflow'] + ' / ' + r['disposition'])) + ' |')
    lines.append('')
    return stream.getvalue(), '\n'.join(lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true', help='Fail when generated inventory differs from committed files')
    args = parser.parse_args()
    csv_text, markdown = render()
    outputs = {'diagnostic-workflow-inventory.csv': csv_text, 'diagnostic-workflow-inventory.md': markdown}
    for name, content in outputs.items():
        path = ROOT / 'docs' / name
        if args.check:
            if not path.is_file() or path.read_text(encoding='utf-8') != content:
                raise SystemExit('Workflow inventory is stale: ' + name)
        else:
            path.write_text(content, encoding='utf-8', newline='\n')
    print('Workflow inventory: 376 templates reviewed; no diagnostic identity, version or weight changed')


if __name__ == '__main__':
    main()
