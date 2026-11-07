from pathlib import Path
from typing import Literal

from pydantic import model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="AI05_", extra="ignore")
    env: Literal["local", "public"] = "local"
    registry: Path = Path("artifacts/registry.json")
    database_url: str = "sqlite:///.task/events.db"
    hmac_keystore_path: Path = Path("private/device_keys.json")
    real_sms_ingest_enabled: bool = False
    raw_sms_retention_enabled: bool = False
    request_max_bytes: Literal[8192] = 8192

    @model_validator(mode="after")
    def safe_mode(self) -> "Settings":
        if self.raw_sms_retention_enabled:
            raise ValueError("Raw SMS retention unsupported")
        if self.real_sms_ingest_enabled:
            raise ValueError(
                "Real SMS collection requires separate privacy/provisioning review"
            )
        if not self.database_url.startswith("sqlite:///"):
            raise ValueError("Only local SQLite supported")
        return self
