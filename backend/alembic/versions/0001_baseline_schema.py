from alembic import op
import sqlalchemy as sa
import app.db.models


revision = '0001'
down_revision = None
branch_labels = None
depends_on = None


def upgrade() -> None:

    op.create_table('audit_events',
    sa.Column('id', sa.String(length=32), nullable=False),
    sa.Column('actor_id', sa.String(length=32), nullable=True),
    sa.Column('entity_type', sa.String(length=20), nullable=False),
    sa.Column('entity_id', sa.String(length=140), nullable=False),
    sa.Column('action', sa.String(length=40), nullable=False),
    sa.Column('at', app.db.models.UTCDateTime(timezone=True), nullable=False),
    sa.Column('diff', sa.JSON(), nullable=True),
    sa.PrimaryKeyConstraint('id')
    )
    op.create_index(op.f('ix_audit_events_actor_id'), 'audit_events', ['actor_id'], unique=False)
    op.create_index(op.f('ix_audit_events_entity_id'), 'audit_events', ['entity_id'], unique=False)
    op.create_table('users',
    sa.Column('id', sa.String(length=32), nullable=False),
    sa.Column('nickname', sa.String(length=20), nullable=False),
    sa.Column('consent_version', sa.String(length=20), nullable=False),
    sa.Column('created_at', app.db.models.UTCDateTime(timezone=True), nullable=False),
    sa.PrimaryKeyConstraint('id')
    )
    op.create_table('auth_tokens',
    sa.Column('id', sa.String(length=32), nullable=False),
    sa.Column('user_id', sa.String(length=32), nullable=False),
    sa.Column('token_hash', sa.String(length=64), nullable=False),
    sa.Column('expires_at', app.db.models.UTCDateTime(timezone=True), nullable=False),
    sa.Column('revoked_at', app.db.models.UTCDateTime(timezone=True), nullable=True),
    sa.ForeignKeyConstraint(['user_id'], ['users.id'], ondelete='CASCADE'),
    sa.PrimaryKeyConstraint('id'),
    sa.UniqueConstraint('token_hash')
    )
    op.create_index(op.f('ix_auth_tokens_user_id'), 'auth_tokens', ['user_id'], unique=False)
    op.create_table('groups',
    sa.Column('id', sa.String(length=64), nullable=False),
    sa.Column('name', sa.String(length=30), nullable=False),
    sa.Column('owner_user_id', sa.String(length=32), nullable=False),
    sa.Column('created_at', app.db.models.UTCDateTime(timezone=True), nullable=False),
    sa.ForeignKeyConstraint(['owner_user_id'], ['users.id'], ),
    sa.PrimaryKeyConstraint('id')
    )
    op.create_table('badges',
    sa.Column('id', sa.String(length=32), nullable=False),
    sa.Column('group_id', sa.String(length=64), nullable=False),
    sa.Column('badge_id', sa.String(length=30), nullable=False),
    sa.Column('earned_at', app.db.models.UTCDateTime(timezone=True), nullable=False),
    sa.ForeignKeyConstraint(['group_id'], ['groups.id'], ondelete='CASCADE'),
    sa.PrimaryKeyConstraint('id'),
    sa.UniqueConstraint('group_id', 'badge_id')
    )
    op.create_index(op.f('ix_badges_group_id'), 'badges', ['group_id'], unique=False)
    op.create_table('character_progress',
    sa.Column('group_id', sa.String(length=64), nullable=False),
    sa.Column('xp', sa.Integer(), nullable=False),
    sa.Column('skin_id', sa.String(length=20), nullable=False),
    sa.Column('updated_at', app.db.models.UTCDateTime(timezone=True), nullable=False),
    sa.ForeignKeyConstraint(['group_id'], ['groups.id'], ondelete='CASCADE'),
    sa.PrimaryKeyConstraint('group_id')
    )
    op.create_table('demo_coupons',
    sa.Column('id', sa.String(length=32), nullable=False),
    sa.Column('group_id', sa.String(length=64), nullable=False),
    sa.Column('level', sa.Integer(), nullable=False),
    sa.Column('brand_label', sa.String(length=40), nullable=False),
    sa.Column('created_at', app.db.models.UTCDateTime(timezone=True), nullable=False),
    sa.ForeignKeyConstraint(['group_id'], ['groups.id'], ondelete='CASCADE'),
    sa.PrimaryKeyConstraint('id'),
    sa.UniqueConstraint('group_id', 'level')
    )
    op.create_index(op.f('ix_demo_coupons_group_id'), 'demo_coupons', ['group_id'], unique=False)
    op.create_table('group_members',
    sa.Column('id', sa.String(length=32), nullable=False),
    sa.Column('group_id', sa.String(length=64), nullable=False),
    sa.Column('user_id', sa.String(length=32), nullable=False),
    sa.Column('role', sa.String(length=10), nullable=False),
    sa.Column('status', sa.String(length=10), nullable=False),
    sa.Column('rules_accepted_at', app.db.models.UTCDateTime(timezone=True), nullable=True),
    sa.Column('joined_at', app.db.models.UTCDateTime(timezone=True), nullable=False),
    sa.Column('left_at', app.db.models.UTCDateTime(timezone=True), nullable=True),
    sa.ForeignKeyConstraint(['group_id'], ['groups.id'], ondelete='CASCADE'),
    sa.ForeignKeyConstraint(['user_id'], ['users.id'], ),
    sa.PrimaryKeyConstraint('id'),
    sa.UniqueConstraint('group_id', 'user_id')
    )
    op.create_index(op.f('ix_group_members_group_id'), 'group_members', ['group_id'], unique=False)
    op.create_index(op.f('ix_group_members_user_id'), 'group_members', ['user_id'], unique=False)
    op.create_table('invites',
    sa.Column('id', sa.String(length=32), nullable=False),
    sa.Column('group_id', sa.String(length=64), nullable=False),
    sa.Column('trip_id', sa.String(length=64), nullable=True),
    sa.Column('token_hash', sa.String(length=64), nullable=False),
    sa.Column('created_by', sa.String(length=32), nullable=False),
    sa.Column('expires_at', app.db.models.UTCDateTime(timezone=True), nullable=False),
    sa.Column('max_uses', sa.Integer(), nullable=False),
    sa.Column('used_count', sa.Integer(), nullable=False),
    sa.Column('revoked_at', app.db.models.UTCDateTime(timezone=True), nullable=True),
    sa.ForeignKeyConstraint(['created_by'], ['users.id'], ),
    sa.ForeignKeyConstraint(['group_id'], ['groups.id'], ondelete='CASCADE'),
    sa.PrimaryKeyConstraint('id'),
    sa.UniqueConstraint('token_hash')
    )
    op.create_index(op.f('ix_invites_group_id'), 'invites', ['group_id'], unique=False)
    op.create_table('reward_events',
    sa.Column('id', sa.String(length=32), nullable=False),
    sa.Column('group_id', sa.String(length=64), nullable=False),
    sa.Column('trip_id', sa.String(length=64), nullable=False),
    sa.Column('rule_id', sa.String(length=30), nullable=False),
    sa.Column('xp_delta', sa.Integer(), nullable=False),
    sa.Column('data_mode', sa.String(length=8), nullable=False),
    sa.Column('created_at', app.db.models.UTCDateTime(timezone=True), nullable=False),
    sa.ForeignKeyConstraint(['group_id'], ['groups.id'], ondelete='CASCADE'),
    sa.PrimaryKeyConstraint('id'),
    sa.UniqueConstraint('trip_id', 'rule_id')
    )
    op.create_index(op.f('ix_reward_events_group_id'), 'reward_events', ['group_id'], unique=False)
    op.create_table('trips',
    sa.Column('id', sa.String(length=64), nullable=False),
    sa.Column('group_id', sa.String(length=64), nullable=False),
    sa.Column('driver_user_id', sa.String(length=32), nullable=False),
    sa.Column('vehicle_alias', sa.String(length=20), nullable=False),
    sa.Column('state', sa.String(length=12), nullable=False),
    sa.Column('data_mode', sa.String(length=8), nullable=False),
    sa.Column('started_at', app.db.models.UTCDateTime(timezone=True), nullable=True),
    sa.Column('ended_at', app.db.models.UTCDateTime(timezone=True), nullable=True),
    sa.Column('odometer_start_km', sa.Numeric(precision=14, scale=3), nullable=True),
    sa.Column('odometer_end_km', sa.Numeric(precision=14, scale=3), nullable=True),
    sa.Column('revision', sa.Integer(), nullable=False),
    sa.Column('created_at', app.db.models.UTCDateTime(timezone=True), nullable=False),
    sa.ForeignKeyConstraint(['driver_user_id'], ['users.id'], ),
    sa.ForeignKeyConstraint(['group_id'], ['groups.id'], ondelete='CASCADE'),
    sa.PrimaryKeyConstraint('id')
    )
    op.create_index(op.f('ix_trips_group_id'), 'trips', ['group_id'], unique=False)
    op.create_index('uq_trip_one_in_progress', 'trips', ['group_id'], unique=True, postgresql_where=sa.text("state = 'IN_PROGRESS'"), sqlite_where=sa.text("state = 'IN_PROGRESS'"))
    op.create_table('cost_items',
    sa.Column('id', sa.String(length=64), nullable=False),
    sa.Column('trip_id', sa.String(length=64), nullable=False),
    sa.Column('kind', sa.String(length=8), nullable=False),
    sa.Column('amount_krw', sa.Integer(), nullable=False),
    sa.Column('allocation', sa.String(length=20), nullable=False),
    sa.Column('note', sa.String(length=60), nullable=False),
    sa.Column('created_by', sa.String(length=32), nullable=False),
    sa.Column('created_at', app.db.models.UTCDateTime(timezone=True), nullable=False),
    sa.ForeignKeyConstraint(['created_by'], ['users.id'], ),
    sa.ForeignKeyConstraint(['trip_id'], ['trips.id'], ondelete='CASCADE'),
    sa.PrimaryKeyConstraint('id', 'trip_id')
    )
    op.create_table('cost_rules',
    sa.Column('trip_id', sa.String(length=64), nullable=False),
    sa.Column('vehicle_type', sa.String(length=4), nullable=False),
    sa.Column('efficiency_value', sa.Numeric(precision=14, scale=4), nullable=True),
    sa.Column('efficiency_unit', sa.String(length=12), nullable=True),
    sa.Column('efficiency_source', sa.String(length=8), nullable=False),
    sa.Column('unit_price_krw', sa.Numeric(precision=14, scale=4), nullable=True),
    sa.Column('driver_included', sa.Boolean(), nullable=False),
    sa.Column('energy_policy', sa.String(length=16), nullable=False),
    sa.ForeignKeyConstraint(['trip_id'], ['trips.id'], ondelete='CASCADE'),
    sa.PrimaryKeyConstraint('trip_id')
    )
    op.create_table('participant_intervals',
    sa.Column('id', sa.String(length=32), nullable=False),
    sa.Column('trip_id', sa.String(length=64), nullable=False),
    sa.Column('user_id', sa.String(length=32), nullable=False),
    sa.Column('onboard_at', app.db.models.UTCDateTime(timezone=True), nullable=True),
    sa.Column('offboard_at', app.db.models.UTCDateTime(timezone=True), nullable=True),
    sa.Column('odometer_on_km', sa.Numeric(precision=14, scale=3), nullable=True),
    sa.Column('odometer_off_km', sa.Numeric(precision=14, scale=3), nullable=True),
    sa.Column('boundary_quality', sa.String(length=12), nullable=False),
    sa.Column('closed_by', sa.String(length=10), nullable=True),
    sa.Column('created_at', app.db.models.UTCDateTime(timezone=True), nullable=False),
    sa.ForeignKeyConstraint(['trip_id'], ['trips.id'], ondelete='CASCADE'),
    sa.ForeignKeyConstraint(['user_id'], ['users.id'], ),
    sa.PrimaryKeyConstraint('id')
    )
    op.create_index(op.f('ix_participant_intervals_trip_id'), 'participant_intervals', ['trip_id'], unique=False)
    op.create_table('settlements',
    sa.Column('id', sa.String(length=32), nullable=False),
    sa.Column('trip_id', sa.String(length=64), nullable=False),
    sa.Column('revision', sa.Integer(), nullable=False),
    sa.Column('state', sa.String(length=12), nullable=False),
    sa.Column('input_hash', sa.String(length=64), nullable=False),
    sa.Column('total_krw', sa.BigInteger(), nullable=False),
    sa.Column('result', sa.JSON(), nullable=False),
    sa.Column('created_by', sa.String(length=32), nullable=False),
    sa.Column('created_at', app.db.models.UTCDateTime(timezone=True), nullable=False),
    sa.ForeignKeyConstraint(['created_by'], ['users.id'], ),
    sa.ForeignKeyConstraint(['trip_id'], ['trips.id'], ondelete='CASCADE'),
    sa.PrimaryKeyConstraint('id'),
    sa.UniqueConstraint('trip_id', 'input_hash'),
    sa.UniqueConstraint('trip_id', 'revision')
    )
    op.create_index(op.f('ix_settlements_trip_id'), 'settlements', ['trip_id'], unique=False)
    op.create_table('vehicle_readings',
    sa.Column('id', sa.String(length=32), nullable=False),
    sa.Column('trip_id', sa.String(length=64), nullable=False),
    sa.Column('event_id', sa.String(length=64), nullable=False),
    sa.Column('type', sa.String(length=14), nullable=False),
    sa.Column('captured_at', app.db.models.UTCDateTime(timezone=True), nullable=False),
    sa.Column('raw_value', sa.Numeric(precision=18, scale=6), nullable=True),
    sa.Column('raw_unit', sa.String(length=12), nullable=False),
    sa.Column('normalized_value', sa.Numeric(precision=18, scale=6), nullable=True),
    sa.Column('normalized_unit', sa.String(length=12), nullable=True),
    sa.Column('source', sa.String(length=8), nullable=False),
    sa.Column('quality', sa.String(length=12), nullable=False),
    sa.Column('sdk_version', sa.String(length=20), nullable=True),
    sa.Column('error', sa.String(length=200), nullable=True),
    sa.Column('received_at', app.db.models.UTCDateTime(timezone=True), nullable=False),
    sa.ForeignKeyConstraint(['trip_id'], ['trips.id'], ondelete='CASCADE'),
    sa.PrimaryKeyConstraint('id'),
    sa.UniqueConstraint('trip_id', 'event_id')
    )
    op.create_index('ix_readings_trip_type_time', 'vehicle_readings', ['trip_id', 'type', 'captured_at'], unique=False)
    op.create_table('settlement_approvals',
    sa.Column('id', sa.String(length=32), nullable=False),
    sa.Column('settlement_id', sa.String(length=32), nullable=False),
    sa.Column('user_id', sa.String(length=32), nullable=False),
    sa.Column('decision', sa.String(length=8), nullable=False),
    sa.Column('comment', sa.String(length=100), nullable=False),
    sa.Column('decided_at', app.db.models.UTCDateTime(timezone=True), nullable=False),
    sa.ForeignKeyConstraint(['settlement_id'], ['settlements.id'], ondelete='CASCADE'),
    sa.ForeignKeyConstraint(['user_id'], ['users.id'], ),
    sa.PrimaryKeyConstraint('id'),
    sa.UniqueConstraint('settlement_id', 'user_id')
    )
    op.create_index(op.f('ix_settlement_approvals_settlement_id'), 'settlement_approvals', ['settlement_id'], unique=False)



def downgrade() -> None:

    op.drop_index(op.f('ix_settlement_approvals_settlement_id'), table_name='settlement_approvals')
    op.drop_table('settlement_approvals')
    op.drop_index('ix_readings_trip_type_time', table_name='vehicle_readings')
    op.drop_table('vehicle_readings')
    op.drop_index(op.f('ix_settlements_trip_id'), table_name='settlements')
    op.drop_table('settlements')
    op.drop_index(op.f('ix_participant_intervals_trip_id'), table_name='participant_intervals')
    op.drop_table('participant_intervals')
    op.drop_table('cost_rules')
    op.drop_table('cost_items')
    op.drop_index('uq_trip_one_in_progress', table_name='trips', postgresql_where=sa.text("state = 'IN_PROGRESS'"), sqlite_where=sa.text("state = 'IN_PROGRESS'"))
    op.drop_index(op.f('ix_trips_group_id'), table_name='trips')
    op.drop_table('trips')
    op.drop_index(op.f('ix_reward_events_group_id'), table_name='reward_events')
    op.drop_table('reward_events')
    op.drop_index(op.f('ix_invites_group_id'), table_name='invites')
    op.drop_table('invites')
    op.drop_index(op.f('ix_group_members_user_id'), table_name='group_members')
    op.drop_index(op.f('ix_group_members_group_id'), table_name='group_members')
    op.drop_table('group_members')
    op.drop_index(op.f('ix_demo_coupons_group_id'), table_name='demo_coupons')
    op.drop_table('demo_coupons')
    op.drop_table('character_progress')
    op.drop_index(op.f('ix_badges_group_id'), table_name='badges')
    op.drop_table('badges')
    op.drop_table('groups')
    op.drop_index(op.f('ix_auth_tokens_user_id'), table_name='auth_tokens')
    op.drop_table('auth_tokens')
    op.drop_table('users')
    op.drop_index(op.f('ix_audit_events_entity_id'), table_name='audit_events')
    op.drop_index(op.f('ix_audit_events_actor_id'), table_name='audit_events')
    op.drop_table('audit_events')
