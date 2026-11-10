"""Synthetic PostgreSQL restore fixture, used only by verify_services.py."""
import argparse
import base64
import hashlib
import json
from pathlib import Path
import secrets
import sys
from uuid import uuid4

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'backend'))

from sqlalchemy import func, select, text  # noqa: E402
from sqlalchemy.exc import DBAPIError  # noqa: E402
from app import auth, billing, reports  # noqa: E402
from app.db import SessionLocal, engine, utcnow  # noqa: E402
from app.models import Audit, Inspection, Report, Result, User  # noqa: E402
from app.registry import DEFINITIONS  # noqa: E402


def seed(manifest):
    with SessionLocal() as db:
        if db.scalar(select(func.count()).select_from(User)):
            raise RuntimeError('Backup fixture requires a fresh empty acceptance database')
        user = User(email='synthetic-backup@example.invalid', password_hash=auth.hasher.hash(secrets.token_urlsafe(32)))
        db.add(user)
        db.flush()
        billing.seed_plans(db)
        definition = next(d for d in DEFINITIONS if d['weight'] > 0 and d['implementation'] == 'ENABLED'
                          and '{camera_id}' not in d['id'])
        inspection = Inspection(owner_id=user.id, definitions=[definition], score_rule_version=1,
            device_profile={'model': 'BACKUP FIXTURE - NOT A PHONE INSPECTION'})
        db.add(inspection)
        db.flush()
        now = utcnow().isoformat()
        payload = {'check_id': definition['id'], 'definition_version': definition['version'], 'status': 'PASS',
            'measured_value': {}, 'evidence': {'fixture': 'synthetic restore drill'},
            'message': 'No real phone tested', 'app_version': 'backup-acceptance', 'started_at': now, 'completed_at': now}
        db.add(Result(id=str(uuid4()), inspection_id=inspection.id, check_id=definition['id'], payload=payload,
            payload_hash=hashlib.sha256(reports.canonical(payload).encode()).hexdigest()))
        db.flush()
        info = reports.finalize(inspection.id, user=user, db=db)
        db.commit()
        record = {'report_id': info['id'], 'inspection_id': inspection.id, 'owner_id': user.id,
            'hash': info['hash'], 'signature': info['signature'], 'public_key': info['public_key']}
    manifest.write_text(json.dumps(record, indent=2), encoding='utf-8')


def verify(manifest):
    record = json.loads(manifest.read_text(encoding='utf-8'))
    with SessionLocal() as db:
        report = db.get(Report, record['report_id'])
        assert report is not None and reports.verify_signature(report)
        assert report.hash == record['hash'] and report.signature == record['signature']
        assert report.public_key == record['public_key']
        assert db.get(Inspection, record['inspection_id']).owner_id == record['owner_id']
        assert db.scalar(select(func.count()).select_from(billing.Usage)) == 1
        assert db.scalar(select(func.count()).select_from(Audit)) > 0
        from cryptography.hazmat.primitives import serialization
        public = reports.signing_key().public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw)
        assert base64.b64encode(public).decode() == report.public_key
    for statement in ('UPDATE reports SET hash=\'changed\' WHERE id=:id', 'DELETE FROM reports WHERE id=:id'):
        try:
            with engine.begin() as connection:
                connection.execute(text(statement), {'id': record['report_id']})
        except DBAPIError:
            continue
        raise AssertionError('Restored database lost its report immutability trigger')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=('seed', 'verify'))
    parser.add_argument('manifest', type=Path)
    args = parser.parse_args()
    try:
        (seed if args.mode == 'seed' else verify)(args.manifest)
        print(f'Synthetic backup fixture {args.mode}: PASS')
    finally:
        engine.dispose()


if __name__ == '__main__':
    main()
