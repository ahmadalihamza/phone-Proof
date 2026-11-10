"""Exercise the isolated Android QA package against a migrated live local backend.

Build/install verification and verificationAndroidTest APKs first. This command
never installs, uninstalls, clears, or force-stops the user's regular/test app.
Credentials and signing material are ephemeral and never printed.
"""
import argparse
from contextlib import closing
import json
import os
from pathlib import Path
import re
import secrets
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request

from smoke_backend import temporary_state

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = 'pk.phoneproof.app.verification'
RUNNER = PACKAGE + '.test/androidx.test.runner.AndroidJUnitRunner'
TEST_CLASS = 'pk.phoneproof.app.AuthBackendAcceptanceTest'
HIDDEN = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', type=Path, default=ROOT / '.tools/platform-tools/adb.exe')
    parser.add_argument('--serial', help='Authorized Android device serial; defaults to the sole connected device')
    parser.add_argument('--output', type=Path, default=ROOT / 'output/audit-20261027/android-backend')
    args = parser.parse_args(argv)
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    (output / 'result.json').unlink(missing_ok=True)
    adb = str(args.adb.resolve())
    devices = subprocess.check_output([adb, 'devices'], text=True, creationflags=HIDDEN)
    authorized = [line.split()[0] for line in devices.splitlines() if re.fullmatch(r'\S+\s+device', line.strip())]
    serial = args.serial or (authorized[0] if len(authorized) == 1 else None)
    if serial not in authorized:
        raise RuntimeError('Select one connected, authorized Android device with --serial.')
    prefix = [adb, '-s', serial]

    def device(*command, timeout=45):
        return subprocess.run(prefix + list(command), check=True, capture_output=True, text=True,
                              timeout=timeout, creationflags=HIDDEN).stdout

    installed = device('shell', 'pm', 'list', 'packages', '--user', '0')
    packages = {line.removeprefix('package:').strip() for line in installed.splitlines()}
    if not {PACKAGE, PACKAGE + '.test'} <= packages:
        raise RuntimeError('Install both verification APKs first. No existing app will be changed by this tool.')
    with temporary_state() as directory:
        temporary = Path(directory)
        with closing(socket.socket()) as available:
            available.bind(('127.0.0.1', 0))
            port = available.getsockname()[1]
        origin = f'http://127.0.0.1:{port}/api/v1/'
        environment = os.environ | {
            'APP_ENV': 'development', 'DATABASE_URL': 'sqlite:///' + (temporary / 'android.db').as_posix(),
            'JWT_SECRET': secrets.token_urlsafe(64), 'SIGNING_KEY_PATH': str(temporary / 'signing.pem'),
            'MEDIA_DIR': str(temporary / 'media'), 'PUBLIC_REPORT_BASE_URL': f'http://127.0.0.1:{port}',
            'DEVELOPMENT_PAYMENTS': 'false', 'REDIS_URL': '', 'MODEL_MANIFEST': '',
            'SMTP_HOST': '', 'SMTP_USERNAME': '', 'SMTP_PASSWORD': '', 'SMTP_FROM': '',
            'S3_BUCKET': '', 'S3_ACCESS_KEY': '', 'S3_SECRET_KEY': '', 'MFA_ENCRYPTION_KEY': secrets.token_hex(32),
            'ALLOWED_HOSTS': 'localhost,127.0.0.1',
        }
        with (output / 'migrations.log').open('w', encoding='utf-8') as log:
            subprocess.run([sys.executable, '-m', 'alembic', 'upgrade', 'head'], cwd=ROOT / 'backend',
                           env=environment, check=True, stdout=log, stderr=subprocess.STDOUT, timeout=120, creationflags=HIDDEN)
        with (output / 'server.log').open('w', encoding='utf-8') as log:
            process = subprocess.Popen([sys.executable, '-m', 'uvicorn', 'app.main:app', '--host', '127.0.0.1', '--port', str(port)],
                cwd=ROOT / 'backend', env=environment, stdout=log, stderr=subprocess.STDOUT, creationflags=HIDDEN)
            reversed_port = False
            completed = []
            try:
                for _ in range(100):
                    try:
                        with urllib.request.urlopen(f'http://127.0.0.1:{port}/health', timeout=2) as response:
                            assert json.load(response)['status'] == 'ok'
                        break
                    except (urllib.error.URLError, TimeoutError):
                        if process.poll() is not None:
                            raise RuntimeError('Backend exited before startup; inspect server.log')
                        time.sleep(0.2)
                else:
                    raise TimeoutError('Backend startup exceeded 20 seconds')
                device('reverse', f'tcp:{port}', f'tcp:{port}')
                reversed_port = True
                for phase in ('journey', 'restore'):
                    if phase == 'restore':
                        device('shell', 'am', 'force-stop', '--user', '0', PACKAGE)
                    result = device('shell', 'am', 'instrument', '--user', '0', '-w', '-r',
                        '-e', 'class', TEST_CLASS, '-e', 'phoneproof_test_origin', origin,
                        '-e', 'phoneproof_test_phase', phase, RUNNER, timeout=240)
                    (output / f'{phase}.log').write_text(result, encoding='utf-8')
                    skipped = re.search(r'INSTRUMENTATION_STATUS_CODE:\s*-[34]\b', result)
                    if not re.search(r'OK\s*\(1 test\)', result) or skipped or 'FAILURES!!!' in result or 'INSTRUMENTATION_FAILED' in result:
                        raise RuntimeError(f'Android {phase} failed; inspect {phase}.log')
                    completed.append(phase)
                    print(f'PASS: Android real-backend {phase}', flush=True)
                (output / 'result.json').write_text(json.dumps({
                    'backend': 'real migrated SQLite and live Uvicorn', 'package': PACKAGE,
                    'phases_passed': completed, 'authentication': 'real register, duplicate registration, invalid credentials, sign-in, rotating refresh, process restoration',
                    'sync': 'actual device storage observation, durable guest queue, exact acknowledgements, repeated sync without duplicate server results',
                    'physical_condition_certification': False,
                }, indent=2), encoding='utf-8')
            finally:
                if reversed_port:
                    try:
                        device('reverse', '--remove', f'tcp:{port}')
                    except (subprocess.SubprocessError, OSError):
                        # A disconnected phone must not leave the owned backend running.
                        print('ADB reverse removal unavailable after device disconnect.', file=sys.stderr)
                if os.name == 'nt':
                    subprocess.run(['taskkill', '/PID', str(process.pid), '/T', '/F'], capture_output=True, check=False, timeout=15, creationflags=HIDDEN)
                else:
                    process.terminate()
                try:
                    process.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)
    print('PASS: isolated Android/backend verification and owned temporary-state cleanup', flush=True)


if __name__ == '__main__':
    main()
