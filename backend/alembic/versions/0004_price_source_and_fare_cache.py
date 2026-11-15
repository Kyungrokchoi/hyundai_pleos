from alembic import op
import sqlalchemy as sa


revision = '0004'
down_revision = '0003'
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column('cost_rules', sa.Column('unit_price_source', sa.String(length=8), nullable=False, server_default='MANUAL'))
    op.add_column('cost_rules', sa.Column('fuel_type', sa.String(length=8), nullable=True))
    op.create_table(
        'toll_fare_cache',
        sa.Column('dep_code', sa.String(length=8), nullable=False),
        sa.Column('arr_code', sa.String(length=8), nullable=False),
        sa.Column('vehicle_class', sa.Integer(), nullable=False),
        sa.Column('amount_krw', sa.Integer(), nullable=True),
        sa.Column('fetched_at', sa.DateTime(), nullable=False),
        sa.PrimaryKeyConstraint('dep_code', 'arr_code', 'vehicle_class'),
    )


def downgrade() -> None:
    op.drop_table('toll_fare_cache')
    with op.batch_alter_table('cost_rules') as b:
        b.drop_column('fuel_type')
        b.drop_column('unit_price_source')
