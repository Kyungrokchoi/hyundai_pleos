import os

os.environ.setdefault("MOATA_DATABASE_URL", "sqlite://")  # in-memory by default; CI/dev may point at PostgreSQL
os.environ["MOATA_ALLOW_GUEST_AUTH"] = "true"

import pytest  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402

from app.db import models as m  # noqa: E402
from app.db.session import engine  # noqa: E402
from app.main import app  # noqa: E402


@pytest.fixture(autouse=True)
def fresh_db():
    m.Base.metadata.drop_all(engine)
    m.Base.metadata.create_all(engine)
    yield


@pytest.fixture
def client():
    return TestClient(app)  # no context manager, so the init_db startup hook does not run


@pytest.fixture
def clock(monkeypatch):
    """Controllable server clock: clock.advance(seconds)."""
    from datetime import datetime, timedelta, timezone

    class Clock:
        now = datetime(2026, 11, 2, 9, 0, 0, tzinfo=timezone.utc)

        def advance(self, seconds: float):
            self.now += timedelta(seconds=seconds)

    c = Clock()
    monkeypatch.setattr(m, "utcnow", lambda: c.now)
    return c
