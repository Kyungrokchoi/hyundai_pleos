"""add participant_intervals.opened_by

Revision ID: 0002
Revises: 0001
"""
from alembic import op
import sqlalchemy as sa


revision = '0002'
down_revision = '0001'
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column('participant_intervals', sa.Column('opened_by', sa.String(length=10), nullable=True))


def downgrade() -> None:
    with op.batch_alter_table('participant_intervals') as b:
        b.drop_column('opened_by')
