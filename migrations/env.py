from alembic import context
from sqlalchemy import engine_from_config, pool

from ai05.config import Settings
from ai05.infrastructure.events import SCHEMA

config = context.config
config.set_main_option("sqlalchemy.url", Settings().database_url)
engine = engine_from_config(
    config.get_section(config.config_ini_section),
    prefix="sqlalchemy.",
    poolclass=pool.NullPool,
)
with engine.connect() as connection:
    context.configure(connection=connection, target_metadata=SCHEMA)
    with context.begin_transaction():
        context.run_migrations()
