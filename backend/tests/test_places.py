import json

import pytest

from app.config import get_settings
from app.services import places
from tests.helpers import Actor

DOCS = json.dumps({"documents": [
    {"region_type": "B", "address_name": "경기도 수원시 영통구 매탄동", "region_1depth_name": "경기도",
     "region_2depth_name": "수원시 영통구", "region_3depth_name": "매탄동"},
    {"region_type": "H", "address_name": "경기도 수원시 영통구 매탄3동", "region_1depth_name": "경기도",
     "region_2depth_name": "수원시 영통구", "region_3depth_name": "매탄3동"},
]})


@pytest.fixture(autouse=True)
def clean():
    places.reset()
    yield
    places.reset()


class Fake:
    def __init__(self, text):
        self.text, self.calls = text, []

    def __call__(self, url, key):
        self.calls.append((url, key))
        return self.text


def test_parse_prefers_the_legal_dong_and_shortens_the_city():
    p = places.parse(DOCS)
    assert (p.address, p.short) == ("경기도 수원시 영통구 매탄동", "영통구 매탄동")


@pytest.mark.parametrize("text", ["not json", json.dumps({"documents": []}), json.dumps({})])
def test_unusable_responses_raise(text):
    with pytest.raises(places.PlaceUnavailable):
        places.parse(text)


def test_nearby_points_share_one_call_and_the_key_goes_in_the_header_only():
    f = Fake(DOCS)
    places.lookup("k", 37.26401, 127.04001, fetch=f)
    places.lookup("k", 37.26404, 127.04003, fetch=f)
    assert len(f.calls) == 1
    url, key = f.calls[0]
    assert key == "k" and "k" not in url.split("?")[1].replace("x=", "").replace("y=", "")
    assert "x=127.040010" in url and "y=37.264010" in url


def test_no_key_never_calls_out_and_errors_become_unavailable():
    f = Fake(DOCS)
    with pytest.raises(places.PlaceUnavailable):
        places.lookup("", 37.2, 127.0, fetch=f)
    assert f.calls == []

    def boom(url, key):
        raise TimeoutError("slow")
    with pytest.raises(places.PlaceUnavailable):
        places.lookup("k", 37.2, 127.0, fetch=boom)


def test_endpoint_requires_login_hides_the_key_and_answers_503_without_one(client, monkeypatch):
    assert client.get("/api/v1/places/region?lat=37.26&lon=127.04").status_code == 401
    a = Actor(client, "A")
    r = a.get("/places/region?lat=37.26&lon=127.04")
    assert r.status_code == 503 and r.json()["code"] == "PLACE_UNAVAILABLE"
    monkeypatch.setattr(get_settings(), "kakao_rest_key", "SECRET-KEY")
    monkeypatch.setattr(places, "_http_get", Fake(DOCS))
    r = a.get("/places/region?lat=37.26&lon=127.04")
    assert r.status_code == 200, r.text
    assert r.json() == {"address": "경기도 수원시 영통구 매탄동", "short": "영통구 매탄동"}
    assert "SECRET-KEY" not in r.text


def test_points_outside_korea_are_rejected(client):
    assert Actor(client, "A").get("/places/region?lat=51.5&lon=-0.1").status_code == 422
