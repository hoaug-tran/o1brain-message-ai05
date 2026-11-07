import sqlalchemy as sa
from alembic import op

revision = "0001"
down_revision = None
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "sms_events",
        sa.Column("source_id", sa.String(), primary_key=True),
        sa.Column("event_id", sa.String(), primary_key=True),
        sa.Column("source_kind", sa.String(), nullable=False),
        sa.Column("message_origin", sa.String(), nullable=False),
        sa.Column("predicted_label", sa.String(), nullable=False),
        sa.Column("policy_status", sa.String(), nullable=False),
        sa.Column("model_id", sa.String(), nullable=False),
        sa.Column("model_version", sa.String(), nullable=False),
        sa.Column("score", sa.Float(), nullable=False),
        sa.Column("score_kind", sa.String(), nullable=False),
        sa.Column("expires_at", sa.Integer(), nullable=False),
        sa.Column("created_at", sa.Integer(), nullable=False),
    )
    op.create_table(
        "idempotency_receipts",
        sa.Column("source_id", sa.String(), primary_key=True),
        sa.Column("idempotency_key", sa.String(), primary_key=True),
        sa.Column("body_hmac", sa.String(), nullable=False),
        sa.Column("response", sa.String(), nullable=False),
        sa.Column("event_id", sa.String(), nullable=False),
        sa.Column("expires_at", sa.Integer(), nullable=False),
        sa.UniqueConstraint("source_id", "event_id"),
    )
    op.create_table(
        "nonces",
        sa.Column("source_id", sa.String(), primary_key=True),
        sa.Column("nonce", sa.String(), primary_key=True),
        sa.Column("expires_at", sa.Integer(), nullable=False),
    )


def downgrade() -> None:
    for name in ("nonces", "idempotency_receipts", "sms_events"):
        op.drop_table(name)
