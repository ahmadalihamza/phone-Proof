import copy
import json
from pathlib import Path
from .scoring import score  # Backwards-compatible import for existing callers.

DEFINITIONS = json.loads((Path(__file__).resolve().parents[2] / 'contracts/diagnostics.json').read_text()) if (Path(__file__).resolve().parents[2] / 'contracts/diagnostics.json').exists() else json.loads((Path(__file__).resolve().parents[2].parent / 'contracts/diagnostics.json').read_text())


def expand(profile):
    result = []
    for definition in DEFINITIONS:
        if definition['implementation'] != 'ENABLED':
            continue
        if '{camera_id}' not in definition['id']:
            result.append(copy.deepcopy(definition))
        else:
            for camera in profile.get('cameras', []):
                d = copy.deepcopy(definition)
                d['id'] = d['id'].replace('{camera_id}', camera)
                d['title'] = d['title'].replace('{camera_id}', camera)
                d['parameters']['camera_id'] = camera
                d['capabilities'] = [cap.replace('{camera_id}', camera) for cap in d['capabilities']]
                flash = f'camera.{camera}.flash'
                if d['handler'] == 'torch' and flash not in profile.get('features', {}):
                    # Old clients never recorded flash discovery. Keep their v1
                    # definition/attempt contract; absence of the key is unknown.
                    d['capabilities'] = [cap for cap in d['capabilities'] if cap != flash]
                    d['version'] = 1
                result.append(d)
    return result


