"""Run PostgreSQL and/or object-store acceptance with temporary loopback services.

Example (Windows, using EDB's portable archive linked from postgresql.org):
  python tools/verify_services.py --postgres-bin .tools/postgresql-16.15/pgsql/bin
  python tools/verify_services.py --object-store .tools/minio/minio.exe

No service is installed and no existing database is contacted. The generated
password is ephemeral; it is never printed. Logs and JUnit evidence stay under
output; owned database/object data is removed after shutdown.
"""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import subprocess
import sys
import tempfile
import time
from urllib.error import URLError
from urllib.request import urlopen
import xml.etree.ElementTree as ET

import psycopg
from sqlalchemy.engine import URL


ROOT = Path(__file__).resolve().parents[1]
HIDDEN = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0


def free_port():
    with socket.socket() as listener:
        listener.bind(('127.0.0.1', 0))
        return listener.getsockname()[1]


def run_postgresql(binary_directory, output, backup_restore=False):
    binary_directory = binary_directory.resolve()
    suffix = '.exe' if os.name == 'nt' else ''
    def executable(name):
        return str(binary_directory / (name + suffix))
    for name in ('initdb', 'postgres', 'pg_ctl'):
        if not Path(executable(name)).is_file():
            raise ValueError(f'Missing portable PostgreSQL executable: {name}')
    cluster = output / 'postgres-data'
    password_file = output / 'postgres-password.tmp'
    password = secrets.token_urlsafe(32)
    password_file.write_text(password, encoding='ascii')
    password_file.chmod(0o600)
    port = free_port()
    process = None
    report = {'engine': 'PostgreSQL', 'host': '127.0.0.1', 'port': port, 'stopped': False}
    try:
        with (output / 'postgres-init.log').open('w', encoding='utf-8') as log:
            subprocess.run([executable('initdb'), '-D', str(cluster), '-U', 'phoneproof',
                '-A', 'scram-sha-256', '--pwfile', str(password_file), '--encoding=UTF8', '--locale=C'],
                check=True, stdout=log, stderr=subprocess.STDOUT, timeout=90, creationflags=HIDDEN)
        password_file.unlink()
        with (output / 'postgres-server.log').open('w', encoding='utf-8') as log:
            process = subprocess.Popen([executable('postgres'), '-D', str(cluster), '-h', '127.0.0.1',
                '-p', str(port), '-c', 'max_connections=20', '-c', 'shared_buffers=32MB'],
                stdout=log, stderr=subprocess.STDOUT, creationflags=HIDDEN)
            deadline = time.monotonic() + 40
            while True:
                if process.poll() is not None:
                    raise RuntimeError('Portable PostgreSQL exited; inspect postgres-server.log')
                try:
                    with psycopg.connect(host='127.0.0.1', port=port, user='phoneproof', password=password,
                                         dbname='postgres', autocommit=True, connect_timeout=1) as db:
                        db.execute('CREATE DATABASE phoneproof_acceptance')
                        report['version'] = db.execute('SELECT version()').fetchone()[0]
                    break
                except psycopg.OperationalError:
                    if time.monotonic() >= deadline:
                        raise RuntimeError('Portable PostgreSQL startup timed out') from None
                    time.sleep(0.2)
            url = URL.create('postgresql+psycopg', username='phoneproof', password=password,
                host='127.0.0.1', port=port, database='phoneproof_acceptance')
            environment = os.environ | {'TEST_POSTGRES_URL': url.render_as_string(hide_password=False)}
            junit = output / 'postgres-acceptance.xml'
            with (output / 'postgres-acceptance.log').open('w', encoding='utf-8') as test_log:
                result = subprocess.run([sys.executable, '-m', 'pytest', '-q', 'tests/test_postgresql.py',
                    '-p', 'no:cacheprovider', '--junitxml', str(junit)], cwd=ROOT / 'backend', env=environment,
                    stdout=test_log, stderr=subprocess.STDOUT, timeout=300, creationflags=HIDDEN)
            report['returncode'] = result.returncode
            if junit.exists():
                suite = ET.parse(junit).getroot().find('testsuite')
                report['tests'] = {key: int(suite.get(key, '0')) for key in ('tests', 'failures', 'errors', 'skipped')}
            print((output / 'postgres-acceptance.log').read_text(encoding='utf-8'), flush=True)
            if backup_restore and result.returncode == 0:
                report['backup_restore'] = run_backup_restore(binary_directory, output, port, password, url)
    finally:
        password_file.unlink(missing_ok=True)
        if process is not None:
            if process.poll() is None:
                with (output / 'postgres-stop.log').open('w', encoding='utf-8') as log:
                    try:
                        subprocess.run([executable('pg_ctl'), '-D', str(cluster), '-m', 'fast', '-w', 'stop'],
                            stdout=log, stderr=subprocess.STDOUT, timeout=45, creationflags=HIDDEN)
                    except subprocess.TimeoutExpired:
                        pass  # The owned process is terminated below if shutdown stalls.
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.terminate()
                    process.wait(timeout=10)
            report['stopped'] = process.poll() is not None
        # Delete only the cluster we created beneath this fresh output directory.
        if cluster.exists() and (process is None or report['stopped']):
            resolved = cluster.resolve()
            if resolved.parent != output.resolve() or not resolved.is_relative_to((ROOT / 'output').resolve()):
                raise ValueError('Refusing to remove a cluster outside the owned output directory')
            shutil.rmtree(resolved)
        (output / 'postgres-result.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
    return report


def run_backup_restore(binary_directory, output, port, password, source_url):
    """Only called for the disposable server this helper has just created."""
    suffix = '.exe' if os.name == 'nt' else ''
    key = output / 'synthetic-signing.pem'
    key_copy = output / 'synthetic-signing-restored.pem'
    manifest = output / 'synthetic-backup-manifest.json'
    environment = os.environ | {'APP_ENV': 'development', 'DATABASE_URL': source_url.render_as_string(hide_password=False),
        'JWT_SECRET': secrets.token_urlsafe(64), 'SIGNING_KEY_PATH': str(key), 'REQUIRE_COSMETIC': 'false',
        'PGHOST': '127.0.0.1', 'PGPORT': str(port), 'PGUSER': 'phoneproof', 'PGPASSWORD': password}
    fixture = str(ROOT / 'tools' / 'backup_restore_fixture.py')
    dump = output / 'synthetic-postgres-backup.dump'
    try:
        with (output / 'backup-restore.log').open('w', encoding='utf-8') as log:
            def run(arguments, env=environment):
                subprocess.run(arguments, cwd=ROOT / 'backend', env=env, check=True, stdout=log,
                    stderr=subprocess.STDOUT, timeout=120, creationflags=HIDDEN)
            run([sys.executable, '-m', 'alembic', 'upgrade', 'head'])
            run([sys.executable, fixture, 'seed', str(manifest)])
            shutil.copyfile(key, key_copy)
            run([str(binary_directory / ('pg_dump' + suffix)), '--format=custom', '--file', str(dump),
                 '--dbname', 'phoneproof_acceptance'])
            with psycopg.connect(host='127.0.0.1', port=port, user='phoneproof', password=password,
                                 dbname='postgres', autocommit=True) as db:
                db.execute('CREATE DATABASE phoneproof_restore')
            run([str(binary_directory / ('pg_restore' + suffix)), '--exit-on-error', '--no-owner', '--no-privileges',
                 '--dbname', 'phoneproof_restore', str(dump)])
            restored = source_url.set(database='phoneproof_restore')
            run([sys.executable, fixture, 'verify', str(manifest)], environment | {
                'DATABASE_URL': restored.render_as_string(hide_password=False), 'SIGNING_KEY_PATH': str(key_copy)})
        result = {'passed': True, 'synthetic_only': True, 'fresh_database_restore': True,
            'signature_and_hash_preserved': True, 'ownership_and_quota_preserved': True,
            'raw_sql_immutability_preserved': True, 'signing_key_copy_verified': True}
        (output / 'backup-restore-result.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
        print('Synthetic PostgreSQL dump/restore and signing-key copy: PASS', flush=True)
        return result
    finally:
        # These are throwaway fixture keys, never a user's production signing key.
        key.unlink(missing_ok=True)
        key_copy.unlink(missing_ok=True)


def run_object_storage(binary, output):
    binary = binary.resolve()
    if not binary.is_file():
        raise ValueError('The --object-store argument must name a portable MinIO executable')
    data = output / 'minio-data'
    data.mkdir()
    port, console_port = free_port(), free_port()
    environment = os.environ | {
        'MINIO_ROOT_USER': 'pp-' + secrets.token_hex(8),
        'MINIO_ROOT_PASSWORD': secrets.token_urlsafe(32),
        # Local acceptance only: exercise the application's SSE-S3 requirement.
        'MINIO_KMS_SECRET_KEY': 'acceptance:' + base64.b64encode(secrets.token_bytes(32)).decode(),
        'MINIO_BROWSER': 'off', 'MINIO_SITE_REGION': 'auto', 'GOMAXPROCS': '2', 'GOMEMLIMIT': '512MiB'}
    process = None
    report = {'engine': 'MinIO', 'host': '127.0.0.1', 'port': port, 'stopped': False}
    report['version'] = subprocess.check_output([str(binary), '--version'], timeout=15,
        creationflags=HIDDEN).decode('utf-8', errors='replace').strip()
    with binary.open('rb') as file:
        report['binary_sha256'] = hashlib.file_digest(file, 'sha256').hexdigest()
    try:
        with (output / 'minio-server.log').open('w', encoding='utf-8') as log:
            process = subprocess.Popen([str(binary), 'server', '--address', f'127.0.0.1:{port}',
                '--console-address', f'127.0.0.1:{console_port}', str(data)], env=environment,
                stdout=log, stderr=subprocess.STDOUT, creationflags=HIDDEN)
            endpoint = f'http://127.0.0.1:{port}'
            deadline = time.monotonic() + 45
            while True:
                if process.poll() is not None:
                    raise RuntimeError('Portable MinIO exited; inspect minio-server.log')
                try:
                    with urlopen(endpoint + '/minio/health/ready', timeout=1) as response:
                        if response.status == 200:
                            break
                except (URLError, TimeoutError):
                    if time.monotonic() >= deadline:
                        raise RuntimeError('Portable MinIO startup timed out') from None
                    time.sleep(0.2)
            test_environment = os.environ | {'TEST_S3_ENDPOINT': endpoint,
                'TEST_S3_ACCESS_KEY': environment['MINIO_ROOT_USER'],
                'TEST_S3_SECRET_KEY': environment['MINIO_ROOT_PASSWORD']}
            junit = output / 'object-storage-acceptance.xml'
            with (output / 'object-storage-acceptance.log').open('w', encoding='utf-8') as test_log:
                result = subprocess.run([sys.executable, '-m', 'pytest', '-q', 'tests/test_object_storage.py',
                    '-p', 'no:cacheprovider', '--junitxml', str(junit)], cwd=ROOT / 'backend', env=test_environment,
                    stdout=test_log, stderr=subprocess.STDOUT, timeout=180, creationflags=HIDDEN)
            report['returncode'] = result.returncode
            if junit.exists():
                suite = ET.parse(junit).getroot().find('testsuite')
                report['tests'] = {key: int(suite.get(key, '0')) for key in ('tests', 'failures', 'errors', 'skipped')}
            print((output / 'object-storage-acceptance.log').read_text(encoding='utf-8'), flush=True)
    finally:
        if process is not None:
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=10)
            report['stopped'] = process.poll() is not None
        if data.exists() and (process is None or report['stopped']):
            resolved = data.resolve()
            if resolved.parent != output.resolve() or not resolved.is_relative_to((ROOT / 'output').resolve()):
                raise ValueError('Refusing to remove object-store data outside its owned output directory')
            shutil.rmtree(resolved)
        (output / 'object-storage-result.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--postgres-bin', type=Path)
    parser.add_argument('--object-store', type=Path, help='Path to a portable MinIO executable')
    parser.add_argument('--backup-restore', action='store_true', help='Also rehearse a synthetic PostgreSQL dump/restore')
    args = parser.parse_args()
    if not args.postgres_bin and not args.object_store:
        parser.error('Specify --postgres-bin and/or --object-store')
    if args.backup_restore and not args.postgres_bin:
        parser.error('--backup-restore requires --postgres-bin')
    output_parent = ROOT / 'output'
    output_parent.mkdir(exist_ok=True)
    output = Path(tempfile.mkdtemp(prefix='service-acceptance-', dir=output_parent)).resolve()
    print(f'Acceptance evidence: {output}', flush=True)
    reports = []
    if args.postgres_bin:
        reports.append(run_postgresql(args.postgres_bin, output, args.backup_restore))
    if args.object_store:
        reports.append(run_object_storage(args.object_store, output))
    print(json.dumps(reports, indent=2), flush=True)
    return 0 if all(report.get('returncode') == 0 and report['stopped'] and
                    report.get('tests', {}).get('skipped') == 0 for report in reports) else 1


if __name__ == '__main__':
    raise SystemExit(main())
