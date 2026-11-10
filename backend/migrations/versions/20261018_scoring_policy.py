"""Pin existing inspections to legacy scoring and use v2 for new inspections."""
from alembic import op
import sqlalchemy as sa

revision = '20261018_scoring_policy'
down_revision = '20261010_account_recovery'
branch_labels = None
depends_on = None


def upgrade():
    op.add_column('inspections', sa.Column('score_rule_version', sa.Integer(), nullable=False, server_default='1'))


def downgrade():
    op.drop_column('inspections', 'score_rule_version')
