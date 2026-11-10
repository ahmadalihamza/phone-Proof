"""Account verification, session revocation and encrypted mail outbox.

Existing accounts remain unverified and retain their current session version.
"""
from alembic import op
import sqlalchemy as sa

revision = '20261010_account_recovery'
down_revision = 'a4ebe32947d1'
branch_labels = None
depends_on = None


def upgrade():
    op.add_column('users', sa.Column('email_verified_at', sa.DateTime(timezone=True), nullable=True))
    op.add_column('users', sa.Column('session_version', sa.Integer(), nullable=False, server_default='0'))
    op.create_table(
        'account_actions',
        sa.Column('token_hash', sa.String(64), primary_key=True),
        sa.Column('user_id', sa.String(36), sa.ForeignKey('users.id'), nullable=False),
        sa.Column('kind', sa.String(30), nullable=False),
        sa.Column('expires_at', sa.DateTime(timezone=True), nullable=False),
        sa.Column('used', sa.Integer(), nullable=False),
        sa.Column('created_at', sa.DateTime(timezone=True), nullable=False),
    )
    op.create_index('ix_account_actions_user_id', 'account_actions', ['user_id'])
    op.create_table(
        'mail_outbox',
        sa.Column('id', sa.String(36), primary_key=True),
        sa.Column('user_id', sa.String(36), sa.ForeignKey('users.id'), nullable=False),
        sa.Column('payload', sa.Text(), nullable=True),
        sa.Column('state', sa.String(20), nullable=False),
        sa.Column('attempts', sa.Integer(), nullable=False),
        sa.Column('available_at', sa.DateTime(timezone=True), nullable=False),
        sa.Column('last_error', sa.String(80), nullable=True),
        sa.Column('created_at', sa.DateTime(timezone=True), nullable=False),
        sa.Column('sent_at', sa.DateTime(timezone=True), nullable=True),
    )
    op.create_index('ix_mail_outbox_user_id', 'mail_outbox', ['user_id'])


def downgrade():
    op.drop_index('ix_mail_outbox_user_id', table_name='mail_outbox')
    op.drop_table('mail_outbox')
    op.drop_index('ix_account_actions_user_id', table_name='account_actions')
    op.drop_table('account_actions')
    op.drop_column('users', 'session_version')
    op.drop_column('users', 'email_verified_at')
