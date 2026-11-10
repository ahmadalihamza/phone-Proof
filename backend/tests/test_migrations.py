"""Exercise real migrations and SQL guards, independently of the ORM fixtures."""
import os
from pathlib import Path
import sqlite3
import subprocess
import sys

import pytest


def test_fresh_migrations_match_models_and_reject_raw_audit_mutation(tmp_path):
    database = tmp_path / 'migrated.db'
    environment = os.environ | {'DATABASE_URL': 'sqlite:///' + database.as_posix()}
    directory = Path(__file__).resolve().parents[1]
    for arguments in [('upgrade', 'head'), ('check',)]:
        result = subprocess.run([sys.executable, '-m', 'alembic', *arguments], cwd=directory,
                                env=environment, capture_output=True, text=True, timeout=120)
        assert result.returncode == 0, result.stdout + result.stderr
    with sqlite3.connect(database) as connection:
        connection.execute("INSERT INTO audit_events (id, event, data, created_at) VALUES ('test-audit', 'TEST', '{}', '2026-09-21 00:00:00')")
        connection.commit()
        for statement in ["UPDATE audit_events SET event='REWRITTEN' WHERE id='test-audit'",
                          "DELETE FROM audit_events WHERE id='test-audit'"]:
            with pytest.raises(sqlite3.IntegrityError, match='Append-only'):
                connection.execute(statement)
        assert connection.execute('SELECT event FROM audit_events').fetchone()[0] == 'TEST'


def test_existing_accounts_and_refresh_tokens_survive_upgrade(tmp_path):
    database = tmp_path / 'existing.db'
    environment = os.environ | {'DATABASE_URL': 'sqlite:///' + database.as_posix()}
    directory = Path(__file__).resolve().parents[1]

    def migrate(*arguments):
        result = subprocess.run([sys.executable, '-m', 'alembic', *arguments], cwd=directory,
                                env=environment, capture_output=True, text=True, timeout=120)
        assert result.returncode == 0, result.stdout + result.stderr

    migrate('upgrade', '20260920_append_only')
    with sqlite3.connect(database) as connection:
        connection.execute("""INSERT INTO users (id, email, password_hash, role, created_at)
                              VALUES ('existing-user', 'existing@example.com', 'preserved-hash', 'buyer',
                                      '2026-09-20 00:00:00')""")
        connection.execute("""INSERT INTO refresh_tokens
                              (token_hash, user_id, family, expires_at, used, revoked)
                              VALUES ('existing-token', 'existing-user', 'existing-family',
                                      '2026-11-01 00:00:00', 0, 0)""")
        connection.commit()
    migrate('upgrade', 'head')
    migrate('check')
    with sqlite3.connect(database) as connection:
        assert connection.execute(
            'SELECT password_hash, mfa_last_step, email_verified_at, session_version FROM users'
        ).fetchone() == ('preserved-hash', -1, None, 0)
        assert connection.execute('SELECT used, revoked, mfa FROM refresh_tokens').fetchone() == (0, 0, 0)
        assert connection.execute('SELECT count(*) FROM account_actions').fetchone()[0] == 0
        assert connection.execute('SELECT count(*) FROM mail_outbox').fetchone()[0] == 0


def test_scoring_upgrade_preserves_legacy_inspections_and_signed_payloads(tmp_path):
    database = tmp_path / 'legacy-scoring.db'
    environment = os.environ | {'DATABASE_URL': 'sqlite:///' + database.as_posix()}
    directory = Path(__file__).resolve().parents[1]

    def migrate(target):
        result = subprocess.run([sys.executable, '-m', 'alembic', 'upgrade', target], cwd=directory,
                                env=environment, capture_output=True, text=True, timeout=120)
        assert result.returncode == 0, result.stdout + result.stderr

    migrate('20261010_account_recovery')
    payload = '{"fixture":"SYNTHETIC LEGACY SIGNED PAYLOAD","rule_version":1}'
    with sqlite3.connect(database) as connection:
        connection.execute("""INSERT INTO users
            (id,email,password_hash,role,created_at,mfa_last_step,session_version)
            VALUES ('owner','legacy@example.com','fixture-hash','buyer','2026-10-10 00:00:00',-1,0)""")
        connection.execute("""INSERT INTO inspections
            (id,owner_id,status,commercial_status,device_profile,capabilities,definitions,created_at,pta_status)
            VALUES ('legacy','owner','FINALIZED','TRIAL','{}','{}','[]','2026-10-10 00:00:00','NOT_VERIFIED')""")
        connection.execute("""INSERT INTO reports
            (id,inspection_id,public_token,payload,canonical_json,hash,signature,key_version,public_key,created_at)
            VALUES ('report','legacy','legacy-token',?,?,'unchanged-hash','unchanged-signature','v1','fixture-key','2026-10-10 00:00:00')""", (payload, payload))
        connection.commit()
    migrate('head')
    with sqlite3.connect(database) as connection:
        assert connection.execute('SELECT score_rule_version,status,commercial_status FROM inspections').fetchone() == (1, 'FINALIZED', 'TRIAL')
        assert connection.execute('SELECT payload,canonical_json,hash,signature FROM reports').fetchone() == (
            payload, payload, 'unchanged-hash', 'unchanged-signature')


def test_billing_upgrade_preserves_used_subscriptions_and_guards_financial_records(tmp_path):
    database=tmp_path/'legacy-billing.db'
    environment=os.environ|{'DATABASE_URL':'sqlite:///'+database.as_posix()}
    directory=Path(__file__).resolve().parents[1]
    def migrate(*arguments):
        result=subprocess.run([sys.executable,'-m','alembic',*arguments],cwd=directory,env=environment,
                              capture_output=True,text=True,timeout=120)
        assert result.returncode==0,result.stdout+result.stderr
    migrate('upgrade','20261018_scoring_policy')
    with sqlite3.connect(database) as db:
        db.execute('PRAGMA foreign_keys=ON')
        db.execute("""INSERT INTO users (id,email,password_hash,role,created_at,mfa_last_step,session_version)
            VALUES ('owner','legacy-billing@example.com','fixture','buyer','2026-10-10',-1,0)""")
        db.execute("""INSERT INTO plans (id,name,price,currency,report_limit,period_days,active)
            VALUES ('old-plan','Historical plan',100,'PKR',10,30,1)""")
        db.execute("""INSERT INTO transactions
            (id,user_id,idempotency_key,kind,plan_id,amount,currency,status,provider,created_at,paid_at)
            VALUES ('tx','owner','legacy-key','SUBSCRIPTION_PURCHASE','old-plan',100,'PKR','PAID','development','2026-10-10','2026-10-10')""")
        db.execute("""INSERT INTO subscriptions
            (id,user_id,plan_id,status,current_period_start,current_period_end,cancel_at_period_end,report_limit)
            VALUES ('sub','owner','old-plan','ACTIVE','2026-10-10','2026-11-10',0,10)""")
        db.execute("""INSERT INTO inspections
            (id,owner_id,status,commercial_status,device_profile,capabilities,definitions,created_at,pta_status,score_rule_version)
            VALUES ('inspection','owner','FINALIZED','PAID','{}','{}','[]','2026-10-10','NOT_VERIFIED',1)""")
        db.execute("""INSERT INTO report_usage (inspection_id,user_id,source,subscription_id,created_at)
            VALUES ('inspection','owner','subscription','sub','2026-10-10')""")
        db.commit()
    migrate('upgrade','head');migrate('check')
    with sqlite3.connect(database) as db:
        assert db.execute('PRAGMA foreign_key_check').fetchall()==[]
        assert db.execute('SELECT amount,status,plan_snapshot,fulfillment_status FROM transactions').fetchone()==(100,'PAID',None,'LEGACY')
        assert db.execute('SELECT report_limit,transaction_id FROM subscriptions').fetchone()==(10,None)
        assert db.execute('SELECT source,subscription_id FROM report_usage').fetchone()==('subscription','sub')
        for statement in ["UPDATE transactions SET amount=1 WHERE id='tx'","DELETE FROM transactions WHERE id='tx'"]:
            with pytest.raises(sqlite3.IntegrityError):db.execute(statement)
        db.execute("INSERT INTO payment_events VALUES ('event','tx','development',NULL,NULL,'FIXTURE','PENDING','PAID','2026-10-10')")
        db.execute("INSERT INTO payment_receipts VALUES ('receipt','tx','{}','2026-10-10')")
        db.commit()
        for table in ('payment_events','payment_receipts'):
            for statement in [f"UPDATE {table} SET id='changed'",f'DELETE FROM {table}']:
                with pytest.raises(sqlite3.IntegrityError,match='Append-only'):db.execute(statement)
