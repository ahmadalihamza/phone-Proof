"""Run migrations and an actual loopback HTTP smoke test against isolated state."""
from contextlib import closing, contextmanager
import argparse
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]


def cleanup_temporary_state(state):
    target = Path(state.name).resolve()
    base = Path(tempfile.gettempdir()).resolve()
    if target == base or not target.is_relative_to(base) or not target.name.startswith('phoneproof-smoke-'):
        raise ValueError('Cleanup is restricted to an owned smoke-test temporary directory')
    # A killed Windows child can briefly retain SQLite WAL/SHM mappings after
    # its launcher has exited. Retry that bounded race without hiding failure.
    for attempt in range(8):
        try:
            state.cleanup()
            return
        except PermissionError:
            if attempt == 7:
                raise
            time.sleep(min(0.1 * 2 ** attempt, 1))


@contextmanager
def temporary_state():
    state = tempfile.TemporaryDirectory(prefix='phoneproof-smoke-')
    try:
        yield state.name
    finally:
        cleanup_temporary_state(state)


def main(argv=None):
    parser=argparse.ArgumentParser(description='Verify an isolated PhoneProof service over real loopback HTTP')
    parser.add_argument('--development-payments',action='store_true',help='Also exercise explicitly simulated payment/receipt flows')
    args=parser.parse_args(argv)
    with temporary_state() as directory:
        temporary = Path(directory)
        with closing(socket.socket()) as available:
            available.bind(('127.0.0.1', 0))
            port = available.getsockname()[1]
        environment = os.environ | {
            'APP_ENV': 'development', 'DATABASE_URL': 'sqlite:///' + (temporary / 'smoke.db').as_posix(),
            'JWT_SECRET': secrets.token_urlsafe(64), 'SIGNING_KEY_PATH': str(temporary / 'signing.pem'),
            'MEDIA_DIR': str(temporary / 'media'), 'PUBLIC_REPORT_BASE_URL': f'http://127.0.0.1:{port}',
            'DEVELOPMENT_PAYMENTS': 'true' if args.development_payments else 'false', 'REDIS_URL': '', 'MODEL_MANIFEST': '',
        }
        subprocess.run([sys.executable, '-m', 'alembic', 'upgrade', 'head'], cwd=ROOT / 'backend',
                       env=environment, check=True, timeout=120)
        with (temporary / 'server.log').open('w') as log:
            process = subprocess.Popen([sys.executable, '-m', 'uvicorn', 'app.main:app', '--host', '127.0.0.1',
                                        '--port', str(port)], cwd=ROOT / 'backend', env=environment,
                                       stdout=log, stderr=log,
                                       creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
            try:
                def request(path, body=None, token=None):
                    headers = {'Content-Type': 'application/json'}
                    if token:
                        headers['Authorization'] = 'Bearer ' + token
                    req = urllib.request.Request(f'http://127.0.0.1:{port}' + path,
                                                 data=json.dumps(body).encode() if body is not None else None,
                                                 headers=headers)
                    with urllib.request.urlopen(req, timeout=10) as response:
                        assert response.headers.get('X-Request-ID')
                        return json.load(response)

                for _ in range(100):
                    try:
                        assert request('/health')['status'] == 'ok'
                        break
                    except urllib.error.URLError:
                        if process.poll() is not None:
                            raise RuntimeError('Server exited before becoming healthy')
                        time.sleep(0.2)
                else:
                    raise TimeoutError('Server startup exceeded 20 seconds')
                account = {'email': 'smoke@example.com', 'password': secrets.token_urlsafe(24)}
                request('/api/v1/auth/register', account)
                token = request('/api/v1/auth/login', account)['access_token']
                assert request('/api/v1/auth/me', token=token)['email'] == account['email']
                inspection = str(uuid.uuid4())
                created = request('/api/v1/inspections', {'id': inspection, 'device_profile': {
                    'manufacturer': 'Smoke fixture', 'model': 'NOT A PHONE INSPECTION', 'release': '16', 'api': '36'
                }}, token)
                assert created['id'] == inspection
                assert created['score_rule_version'] == 2
                assert request('/api/v1/scoring/rules')['current_version'] == 2
                assert request('/api/v1/inspections', token=token)[0]['id'] == inspection
                assert request('/api/v1/plans')['providers'] == (['development'] if args.development_payments else [])
                if args.development_payments:
                    checkout=request('/api/v1/payments/create',{'provider':'development','plan_id':'dealer',
                        'idempotency_key':str(uuid.uuid4())},token)
                    transaction_id=checkout['id']
                    assert checkout['plan_snapshot']['report_limit']==30 and checkout['status']=='PENDING'
                    for _ in range(2):
                        paid=request(f'/api/v1/payments/{transaction_id}/development-settle',{'outcome':'PAID'},token)
                        assert paid['status']=='PAID' and paid['fulfillment_status']=='FULFILLED'
                    assert request('/api/v1/subscriptions/current',token=token)['report_limit']==30
                    assert request(f'/api/v1/transactions/{transaction_id}',token=token)['receipt_available']
                    receipt=request(f'/api/v1/transactions/{transaction_id}/receipt',token=token)
                    assert receipt['document']['development'] and receipt['document']['amount']=='2999.00'
                    pdf_request=urllib.request.Request(f'http://127.0.0.1:{port}/api/v1/transactions/{transaction_id}/receipt/pdf',
                        headers={'Authorization':'Bearer '+token})
                    with urllib.request.urlopen(pdf_request,timeout=10) as response:
                        assert response.headers['Cache-Control']=='no-store'
                        assert response.read().startswith(b'%PDF')
                    refund=request(f'/api/v1/transactions/{transaction_id}/refund',{'reason':'Isolated development smoke fixture'},token)
                    assert refund['status']=='REQUESTED'
            finally:
                if os.name == 'nt':
                    # Windows venv launchers spawn a child interpreter; terminate only
                    # this owned process tree so it cannot retain the temporary DB/log.
                    subprocess.run(['taskkill', '/PID', str(process.pid), '/T', '/F'],
                                   capture_output=True, check=False, timeout=15)
                else:
                    process.terminate()
                try:
                    process.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)
    payment_check='development-only checkout, idempotent settlement, subscription, private receipt/PDF and refund request' if args.development_payments else 'payment-provider exclusion'
    print('PASS: migrated database, live HTTP startup, health, register/login, authenticated ownership, inspection persistence, pinned scoring policy, '+payment_check+', temporary-state cleanup')


if __name__ == '__main__':
    main()
