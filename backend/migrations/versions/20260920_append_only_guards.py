"""Database guards for signed reports, raw attempts and audit events."""
from alembic import op

revision='20260920_append_only'
down_revision='3d41ae0b18db'
branch_labels=None
depends_on=None
TABLES=('reports','diagnostic_results','audit_events','report_revocations')

def upgrade():
    dialect=op.get_bind().dialect.name
    if dialect=='postgresql':
        op.execute("CREATE FUNCTION phoneproof_immutable() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Append-only PhoneProof record'; END; $$")
        for table in TABLES:
            op.execute(f'CREATE TRIGGER {table}_immutable BEFORE UPDATE OR DELETE ON {table} FOR EACH ROW EXECUTE FUNCTION phoneproof_immutable()')
    elif dialect=='sqlite':
        for table in TABLES:
            for action in ('UPDATE','DELETE'):
                op.execute(f"CREATE TRIGGER {table}_{action.lower()}_guard BEFORE {action} ON {table} BEGIN SELECT RAISE(ABORT, 'Append-only PhoneProof record'); END")

def downgrade():
    dialect=op.get_bind().dialect.name
    if dialect=='postgresql':
        for table in TABLES:op.execute(f'DROP TRIGGER {table}_immutable ON {table}')
        op.execute('DROP FUNCTION phoneproof_immutable()')
    elif dialect=='sqlite':
        for table in TABLES:
            for action in ('update','delete'):op.execute(f'DROP TRIGGER {table}_{action}_guard')
