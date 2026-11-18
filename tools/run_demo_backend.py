"""Run a persistent, real development backend reachable through authorized USB debugging."""
import argparse
import json
import os
from pathlib import Path
import secrets
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True, help='Authorized USB Android device serial')
    parser.add_argument('--port', type=int, default=8765)
    args = parser.parse_args()
    if not 1024 <= args.port <= 65535:
        parser.error('Use a local development port between 1024 and 65535')
    state = ROOT / 'runtime/demo'
    state.mkdir(parents=True, exist_ok=True)
    secret_file = state / 'secrets.json'
    if not secret_file.exists():
        with secret_file.open('x', encoding='utf-8') as stream:
            json.dump({'jwt': secrets.token_urlsafe(64), 'mfa': secrets.token_hex(32)}, stream)
    credentials = json.loads(secret_file.read_text())
    environment = os.environ | {
        'APP_ENV': 'development', 'DATABASE_URL': 'sqlite:///' + (state / 'demo.db').as_posix(),
        'JWT_SECRET': credentials['jwt'], 'MFA_ENCRYPTION_KEY': credentials['mfa'],
        'SIGNING_KEY_PATH': str(state / 'signing.pem'), 'MEDIA_DIR': str(state / 'media'),
        'PUBLIC_REPORT_BASE_URL': f'http://127.0.0.1:{args.port}',
        'DEVELOPMENT_PAYMENTS': 'false', 'REDIS_URL': '', 'MODEL_MANIFEST': '',
        'SMTP_HOST': '', 'SMTP_USERNAME': '', 'SMTP_PASSWORD': '', 'SMTP_FROM': '',
        'S3_BUCKET': '', 'S3_ACCESS_KEY': '', 'S3_SECRET_KEY': '', 'ALLOWED_HOSTS': 'localhost,127.0.0.1',
    }
    subprocess.run([sys.executable, '-m', 'alembic', 'upgrade', 'head'], cwd=ROOT / 'backend', env=environment, check=True)
    adb = ROOT / '.tools/platform-tools/adb.exe'
    command = [str(adb) if adb.exists() else 'adb', '-s', args.serial]
    subprocess.run(command + ['reverse', f'tcp:{args.port}', f'tcp:{args.port}'], check=True)
    print(f'Demo API http://127.0.0.1:{args.port}/api/v1/ · keep this process and USB connection active', flush=True)
    try:
        subprocess.run([sys.executable, '-m', 'uvicorn', 'app.main:app', '--host', '127.0.0.1', '--port', str(args.port)],
                       cwd=ROOT / 'backend', env=environment, check=True)
    finally:
        subprocess.run(command + ['reverse', '--remove', f'tcp:{args.port}'], check=False, capture_output=True)


if __name__ == '__main__':
    main()
