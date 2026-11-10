"""Opt-in acceptance against PostgreSQL, using a new disposable schema per run.

Set TEST_POSTGRES_URL to a dedicated PostgreSQL test database. CI supplies one.
SQLite tests cannot establish the locking or PostgreSQL trigger guarantees here.
"""
import hashlib
import os
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import subprocess
import sys
from threading import Barrier, Event, current_thread
from uuid import uuid4

import pytest
from fastapi import HTTPException
from sqlalchemy import create_engine, event, func, select, text
from sqlalchemy.engine import make_url
from sqlalchemy.exc import DBAPIError
from sqlalchemy.orm import sessionmaker

from app import auth, billing, payments, reports
from app.config import settings
from app.db import utcnow
from app.models import Audit, Inspection, RefreshToken, Report, Result, User
from app.registry import DEFINITIONS


pytestmark = pytest.mark.skipif(not os.environ.get('TEST_POSTGRES_URL'), reason='TEST_POSTGRES_URL is not configured')


@pytest.fixture(scope='module')
def postgres():
    url = make_url(os.environ['TEST_POSTGRES_URL'])
    assert url.get_backend_name() == 'postgresql', 'Use a dedicated PostgreSQL test database'
    schema = 'phoneproof_acceptance_' + uuid4().hex
    admin = create_engine(url)
    with admin.begin() as connection:
        connection.execute(text(f'CREATE SCHEMA "{schema}"'))
    scoped_url = url.update_query_dict({'options': f'-csearch_path={schema} -clock_timeout=10s -cstatement_timeout=15s'})
    engine = create_engine(scoped_url, pool_size=6)
    directory = Path(__file__).resolve().parents[1]
    try:
        environment = os.environ | {'DATABASE_URL': scoped_url.render_as_string(hide_password=False)}
        for args in [('upgrade', 'head'), ('check',)]:
            result = subprocess.run([sys.executable, '-m', 'alembic', *args], cwd=directory,
                env=environment, capture_output=True, text=True, timeout=120)
            assert result.returncode == 0, result.stdout + result.stderr
        yield engine, sessionmaker(engine, expire_on_commit=False)
    finally:
        engine.dispose()
        with admin.begin() as connection:
            connection.execute(text(f'DROP SCHEMA "{schema}" CASCADE'))
        admin.dispose()


def account(sessions):
    with sessions() as db:
        user = User(email=f'fixture-{uuid4().hex}@example.com', password_hash=auth.hasher.hash('Postgres-fixture-2026'))
        db.add(user)
        db.flush()
        identity = user.id
        billing.seed_plans(db)
        db.commit()
    return identity


def inspection(sessions, owner_id):
    definition = next(d for d in DEFINITIONS if d['weight'] > 0 and d['implementation'] == 'ENABLED'
                      and '{camera_id}' not in d['id'])
    timestamp = utcnow().isoformat()
    payload = {'id': str(uuid4()), 'check_id': definition['id'], 'definition_version': definition['version'],
        'status': 'PASS', 'measured_value': {}, 'evidence': {'fixture': 'synthetic PostgreSQL acceptance'},
        'message': 'No real phone tested', 'app_version': 'acceptance', 'started_at': timestamp,
        'completed_at': timestamp}
    with sessions() as db:
        row = Inspection(owner_id=owner_id, definitions=[definition], score_rule_version=1)
        db.add(row)
        db.flush()
        db.add(Result(id=payload['id'], inspection_id=row.id, check_id=definition['id'], payload=payload,
            payload_hash=hashlib.sha256(reports.canonical(payload).encode()).hexdigest()))
        db.commit()
        return row.id


def test_postgresql_migrations_and_raw_sql_financial_guards(postgres):
    engine, sessions = postgres
    identity = account(sessions)
    with sessions() as db:
        tx = billing.Transaction(user_id=identity, idempotency_key=str(uuid4()), kind='SUBSCRIPTION_PURCHASE',
            plan_id='dealer', amount=2999, currency='PKR', provider='development')
        audit = Audit(actor_id=identity, event='POSTGRES_ACCEPTANCE')
        db.add_all([tx, audit])
        db.commit()
        transaction_id, audit_id = tx.id, audit.id
    for statement, values in [
        ('UPDATE transactions SET amount=1 WHERE id=:id', {'id': transaction_id}),
        ('DELETE FROM transactions WHERE id=:id', {'id': transaction_id}),
        ("UPDATE audit_events SET event='REWRITTEN' WHERE id=:id", {'id': audit_id}),
        ('DELETE FROM audit_events WHERE id=:id', {'id': audit_id}),
    ]:
        with pytest.raises(DBAPIError):
            with engine.begin() as connection:
                connection.execute(text(statement), values)


def test_entitlement_and_finalize_overlap_without_lock_inversion(postgres, monkeypatch):
    engine, sessions = postgres
    identity = account(sessions)
    inspection_id = inspection(sessions, identity)
    monkeypatch.setattr(settings, 'require_cosmetic', False)
    account_locked, entitlement_requests_account = Event(), Event()

    def observe_account_request(connection, cursor, statement, parameters, context, executemany):
        if current_thread().name.startswith('entitlement') and 'FOR UPDATE' in statement and 'FROM users' in statement:
            entitlement_requests_account.set()

    def finalize():
        with sessions() as db:
            user = db.get(User, identity)
            billing.lock_account(db, user)
            account_locked.set()
            assert entitlement_requests_account.wait(5), 'Entitlement did not request its account lock'
            result = reports.finalize(inspection_id, user=user, db=db)
            db.commit()
            return result

    def entitlement():
        assert account_locked.wait(5)
        with sessions() as db:
            result = billing.report_entitlement(inspection_id, user=db.get(User, identity), db=db)
            db.commit()
            return result

    event.listen(engine, 'before_cursor_execute', observe_account_request)
    try:
        with ThreadPoolExecutor(max_workers=1) as finalizer, ThreadPoolExecutor(max_workers=1, thread_name_prefix='entitlement') as reader:
            signed, access = finalizer.submit(finalize), reader.submit(entitlement)
            assert signed.result(timeout=20)['status'] == 'VALID'
            assert access.result(timeout=20)['can_generate_verified_report'] is True
    finally:
        event.remove(engine, 'before_cursor_execute', observe_account_request)


def test_concurrent_finalization_cannot_overspend_one_trial_report(postgres, monkeypatch):
    _, sessions = postgres
    identity = account(sessions)
    ids = [inspection(sessions, identity) for _ in range(2)]
    monkeypatch.setattr(settings, 'require_cosmetic', False)
    monkeypatch.setattr(settings, 'trial_report_limit', 1)
    barrier = Barrier(2)
    def finalize(inspection_id):
        with sessions() as db:
            user = db.get(User, identity)
            barrier.wait(timeout=5)
            try:
                reports.finalize(inspection_id, user=user, db=db)
                db.commit()
                return 200
            except HTTPException as error:
                db.rollback()
                return error.status_code
    with ThreadPoolExecutor(max_workers=2) as pool:
        assert sorted(pool.map(finalize, ids)) == [200, 402]
    with sessions() as db:
        assert db.scalar(select(func.count()).select_from(billing.Usage).where(billing.Usage.user_id == identity)) == 1
        assert db.scalar(select(func.count()).select_from(Report).join(Inspection).where(Inspection.owner_id == identity)) == 1


def test_concurrent_refresh_reuse_revokes_entire_token_family(postgres):
    _, sessions = postgres
    identity = account(sessions)
    with sessions() as db:
        tokens = auth.issue(db, db.get(User, identity))
        db.commit()
    barrier = Barrier(2)
    def refresh(_):
        with sessions() as db:
            barrier.wait(timeout=5)
            try:
                auth.refresh(auth.RefreshInput(refresh_token=tokens['refresh_token']), db=db)
                db.commit()
                return 200
            except HTTPException as error:
                db.rollback()
                return error.status_code
    with ThreadPoolExecutor(max_workers=2) as pool:
        assert sorted(pool.map(refresh, range(2))) == [200, 401]
    with sessions() as db:
        family = list(db.scalars(select(RefreshToken).where(RefreshToken.user_id == identity)))
        assert len(family) == 2 and all(token.revoked for token in family)


def test_concurrent_checkout_and_settlement_are_idempotent(postgres):
    _, sessions = postgres
    identity = account(sessions)
    data = billing.Checkout(idempotency_key=str(uuid4()), plan_id='dealer', provider='development')
    barrier = Barrier(2)
    def checkout(_):
        with sessions() as db:
            user = db.get(User, identity)
            barrier.wait(timeout=5)
            result = payments.create_checkout(db, user, data)
            db.commit()
            return result
    with ThreadPoolExecutor(max_workers=2) as pool:
        transactions = list(pool.map(checkout, range(2)))
    assert transactions[0]['id'] == transactions[1]['id']
    barrier = Barrier(2)
    def settle(_):
        with sessions() as db:
            user = db.get(User, identity)
            barrier.wait(timeout=5)
            result = billing.settle(transactions[0]['id'], billing.DevelopmentSettlement(outcome='PAID'), user=user, db=db)
            db.commit()
            return result
    with ThreadPoolExecutor(max_workers=2) as pool:
        assert all(result['status'] == 'PAID' for result in pool.map(settle, range(2)))
    with sessions() as db:
        assert db.scalar(select(func.count()).select_from(billing.Subscription).where(billing.Subscription.user_id == identity)) == 1
        assert db.scalar(select(func.count()).select_from(billing.PaymentReceipt).where(
            billing.PaymentReceipt.transaction_id == transactions[0]['id'])) == 1
        assert db.scalar(select(func.count()).select_from(billing.PaymentEvent).where(
            billing.PaymentEvent.transaction_id == transactions[0]['id'])) == 1
