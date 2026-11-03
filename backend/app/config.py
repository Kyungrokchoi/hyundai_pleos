from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="MOATA_", env_file=".env", extra="ignore")

    database_url: str = "sqlite:///./moata.db"
    cors_origins: str = "http://localhost:3000,http://127.0.0.1:3000"  # comma separated; no wildcard
    enable_docs: bool = True  # set false in production: hides /docs and /openapi.json
    allow_guest_auth: bool = True  # temporary guest auth: demo / closed-test environments only
    token_ttl_days: int = 30
    max_body_bytes: int = 256 * 1024
    stale_reading_seconds: int = 180  # a boarding boundary older than this is flagged
    retention_days: int = 90  # trips older than this are purged by `python -m app.purge`
    xp_per_verified_trip: int = 10
    # XP at which each level starts (Lv.1 = 0)
    level_thresholds: tuple[int, ...] = (0, 30, 70, 130, 210, 310)

    @property
    def cors_origin_list(self) -> list[str]:
        return [o.strip() for o in self.cors_origins.split(",") if o.strip() and o.strip() != "*"]


@lru_cache
def get_settings() -> Settings:
    return Settings()
