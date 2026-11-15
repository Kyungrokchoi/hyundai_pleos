from alembic import op
import sqlalchemy as sa


revision = '0003'
down_revision = '0002'
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column('cost_items', sa.Column('road_name', sa.String(length=40), nullable=False, server_default=''))
    op.add_column('cost_items', sa.Column('estimated', sa.Boolean(), nullable=False, server_default=sa.false()))


def downgrade() -> None:
    with op.batch_alter_table('cost_items') as b:
        b.drop_column('estimated')
        b.drop_column('road_name')
