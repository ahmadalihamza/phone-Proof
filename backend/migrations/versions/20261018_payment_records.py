"""Immutable purchase terms, payment attempts/events, receipts and precise refunds."""
from alembic import op
import sqlalchemy as sa

revision = '20261018_payment_records'
down_revision = '20261018_scoring_policy'
branch_labels = None
depends_on = None

TERMS = ('id','user_id','idempotency_key','kind','inspection_id','plan_id','amount','currency','provider','created_at','plan_snapshot')
IMMUTABLE = ('payment_events', 'payment_receipts')


def upgrade():
    op.add_column('transactions', sa.Column('plan_snapshot', sa.JSON(), nullable=True))
    op.add_column('transactions', sa.Column('fulfillment_status', sa.String(30), server_default='LEGACY', nullable=False))
    # SQLite permits a nullable inline REFERENCES column. Rebuilding/dropping the
    # existing table would fail when historical report_usage rows reference it.
    if op.get_bind().dialect.name == 'sqlite':
        op.execute('ALTER TABLE subscriptions ADD COLUMN transaction_id VARCHAR(36) REFERENCES transactions(id)')
    else:
        op.add_column('subscriptions', sa.Column('transaction_id', sa.String(36), sa.ForeignKey('transactions.id'), nullable=True))
    op.create_index('ix_subscriptions_transaction_id', 'subscriptions', ['transaction_id'], unique=True)
    op.add_column('refunds', sa.Column('provider_refund_id', sa.String(100), nullable=True))
    op.add_column('refunds', sa.Column('completed_at', sa.DateTime(timezone=True), nullable=True))
    op.create_index('ix_refunds_provider_refund_id', 'refunds', ['provider_refund_id'], unique=True)
    op.create_table('payment_attempts',
        sa.Column('id', sa.String(36), primary_key=True),
        sa.Column('transaction_id', sa.String(36), sa.ForeignKey('transactions.id'), nullable=False, unique=True),
        sa.Column('provider', sa.String(30), nullable=False),
        sa.Column('provider_reference', sa.String(100), nullable=True),
        sa.Column('status', sa.String(30), nullable=False),
        sa.Column('development_outcome', sa.String(20), nullable=True),
        sa.Column('verification_count', sa.Integer(), nullable=False),
        sa.Column('last_checked_at', sa.DateTime(timezone=True), nullable=True),
        sa.Column('last_error', sa.String(80), nullable=True),
        sa.Column('created_at', sa.DateTime(timezone=True), nullable=False),
        sa.UniqueConstraint('provider', 'provider_reference'))
    op.create_table('payment_events',
        sa.Column('id', sa.String(36), primary_key=True),
        sa.Column('transaction_id', sa.String(36), sa.ForeignKey('transactions.id'), nullable=False),
        sa.Column('provider', sa.String(30), nullable=False),
        sa.Column('event_id', sa.String(100), nullable=True),
        sa.Column('payload_hash', sa.String(64), nullable=True),
        sa.Column('kind', sa.String(30), nullable=False),
        sa.Column('previous_status', sa.String(30), nullable=False),
        sa.Column('status', sa.String(30), nullable=False),
        sa.Column('created_at', sa.DateTime(timezone=True), nullable=False),
        sa.UniqueConstraint('provider', 'event_id'))
    op.create_index('ix_payment_events_transaction_id', 'payment_events', ['transaction_id'])
    op.create_table('payment_receipts',
        sa.Column('id', sa.String(36), primary_key=True),
        sa.Column('transaction_id', sa.String(36), sa.ForeignKey('transactions.id'), nullable=False, unique=True),
        sa.Column('document', sa.JSON(), nullable=False),
        sa.Column('created_at', sa.DateTime(timezone=True), nullable=False))
    dialect = op.get_bind().dialect.name
    if dialect == 'sqlite':
        for table in IMMUTABLE:
            for action in ('UPDATE', 'DELETE'):
                op.execute(f"CREATE TRIGGER {table}_{action.lower()}_guard BEFORE {action} ON {table} "
                           "BEGIN SELECT RAISE(ABORT, 'Append-only PhoneProof record'); END")
        changed = ' OR '.join(f'NEW.{field} IS NOT OLD.{field}' for field in TERMS)
        changed += ' OR (OLD.provider_reference IS NOT NULL AND NEW.provider_reference IS NOT OLD.provider_reference)'
        changed += ' OR (OLD.paid_at IS NOT NULL AND NEW.paid_at IS NOT OLD.paid_at)'
        op.execute(f"CREATE TRIGGER transactions_terms_guard BEFORE UPDATE ON transactions WHEN {changed} "
                   "BEGIN SELECT RAISE(ABORT, 'Immutable financial purchase terms'); END")
        op.execute("CREATE TRIGGER transactions_delete_guard BEFORE DELETE ON transactions "
                   "BEGIN SELECT RAISE(ABORT, 'Append-only PhoneProof transaction'); END")
    elif dialect == 'postgresql':
        for table in IMMUTABLE:
            op.execute(f'CREATE TRIGGER {table}_immutable BEFORE UPDATE OR DELETE ON {table} '
                       'FOR EACH ROW EXECUTE FUNCTION phoneproof_immutable()')
        changed = ' OR '.join(f'NEW.{field}::text IS DISTINCT FROM OLD.{field}::text' for field in TERMS)
        changed += ' OR (OLD.provider_reference IS NOT NULL AND NEW.provider_reference IS DISTINCT FROM OLD.provider_reference)'
        changed += ' OR (OLD.paid_at IS NOT NULL AND NEW.paid_at IS DISTINCT FROM OLD.paid_at)'
        op.execute("CREATE FUNCTION phoneproof_purchase_terms() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                   f"IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'Append-only PhoneProof transaction'; END IF; IF {changed} "
                   "THEN RAISE EXCEPTION 'Immutable financial purchase terms'; END IF; RETURN NEW; END; $$")
        op.execute('CREATE TRIGGER transactions_terms_guard BEFORE UPDATE OR DELETE ON transactions '
                   'FOR EACH ROW EXECUTE FUNCTION phoneproof_purchase_terms()')


def downgrade():
    dialect = op.get_bind().dialect.name
    if dialect == 'sqlite':
        for table in IMMUTABLE:
            for action in ('update','delete'):op.execute(f'DROP TRIGGER {table}_{action}_guard')
        op.execute('DROP TRIGGER transactions_terms_guard')
        op.execute('DROP TRIGGER transactions_delete_guard')
    elif dialect == 'postgresql':
        for table in IMMUTABLE:op.execute(f'DROP TRIGGER {table}_immutable ON {table}')
        op.execute('DROP TRIGGER transactions_terms_guard ON transactions')
        op.execute('DROP FUNCTION phoneproof_purchase_terms()')
    op.drop_table('payment_receipts')
    op.drop_index('ix_payment_events_transaction_id', table_name='payment_events')
    op.drop_table('payment_events')
    op.drop_table('payment_attempts')
    op.drop_index('ix_refunds_provider_refund_id', table_name='refunds')
    op.drop_column('refunds', 'completed_at');op.drop_column('refunds', 'provider_refund_id')
    op.drop_index('ix_subscriptions_transaction_id', table_name='subscriptions')
    op.drop_column('subscriptions', 'transaction_id')
    op.drop_column('transactions', 'fulfillment_status')
    op.drop_column('transactions', 'plan_snapshot')
