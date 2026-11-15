from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="MOATA_", env_file=".env", extra="ignore")

    database_url: str = "sqlite:///./moata.db"
    cors_origins: str = "http://localhost:3000,http://127.0.0.1:3000"
    enable_docs: bool = True
    allow_guest_auth: bool = True
    token_ttl_days: int = 30
    max_body_bytes: int = 256 * 1024
    stale_reading_seconds: int = 180
    retention_days: int = 90
    xp_per_verified_trip: int = 10
    opinet_key: str = ""
    opinet_daily_limit: int = 250
    exco_key: str = ""
    exco_max_lookups_per_estimate: int = 8
    kakao_rest_key: str = ""
    efficiency_data_dir: str = ""
    toll_data_dir: str = ""
    toll_gate_radius_m: float = 80

    level_thresholds: tuple[int, ...] = (0, 30, 70, 130, 210, 310)

    @property
    def cors_origin_list(self) -> list[str]:
        return [o.strip() for o in self.cors_origins.split(",") if o.strip() and o.strip() != "*"]


@lru_cache
def get_settings() -> Settings:
    return Settings()
