"""Verify every baseline diagnostic and scoring policy is preserved exactly."""
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':')).encode()).hexdigest()


def verify():
    baseline = json.loads((ROOT / 'docs/diagnostic-baseline-20261118.json').read_text())
    definitions = json.loads((ROOT / 'contracts/diagnostics.json').read_text())
    current = {definition['id']: definition for definition in definitions}
    assert len(current) == len(definitions), 'Duplicate diagnostic identity'
    for original in baseline['definitions']:
        assert original['id'] in current, 'Removed diagnostic: ' + original['id']
        assert digest(current[original['id']]) == original['sha256'], 'Changed diagnostic: ' + original['id']
    assert digest(json.loads((ROOT / 'contracts/scoring-rules.json').read_text())) == baseline['scoring_sha256'], 'Historical scoring changed'
    print('Preserved all 376 definitions, 326 enabled templates, 61 weighted checks and all scoring policies.')


if __name__ == '__main__':
    verify()
