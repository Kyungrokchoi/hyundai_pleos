import os

os.environ.setdefault("MOATA_DATABASE_URL", "sqlite://")
os.environ["MOATA_ALLOW_GUEST_AUTH"] = "true"
os.environ["MOATA_OPINET_KEY"] = ""
os.environ["MOATA_EXCO_KEY"] = ""
os.environ["MOATA_KAKAO_REST_KEY"] = ""

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
    return TestClient(app)


@pytest.fixture
def clock(monkeypatch):
    from datetime import datetime, timedelta, timezone

    class Clock:
        now = datetime(2026, 11, 2, 9, 0, 0, tzinfo=timezone.utc)

        def advance(self, seconds: float):
            self.now += timedelta(seconds=seconds)

    c = Clock()
    monkeypatch.setattr(m, "utcnow", lambda: c.now)
    return c
