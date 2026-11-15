import json

import pytest

from app.config import get_settings
from app.services import fuel_price as fp
from tests.helpers import Actor

GOOD = json.dumps({"RESULT": {"OIL": [{"PRODCD": "B027", "PRICE": "1678.42", "DIFF": "-0.31", "SIDOCD": "01", "SIDONM": "서울"},
                                      {"PRODCD": "D047", "PRICE": "1560.0", "DIFF": "0.2"}]}})
EMPTY = json.dumps({"RESULT": {"OIL": []}})


@pytest.fixture(autouse=True)
def clean():
    fp.reset()
    yield
    fp.reset()


class Fake:
    def __init__(self, text):
        self.text, self.urls = text, []

    def __call__(self, url):
        self.urls.append(url)
        return self.text


def test_parse_picks_the_requested_product_and_rounds():
    assert fp.parse(GOOD, "B027") == (1678, -0.31)
    assert fp.parse(GOOD, "D047") == (1560, 0.2)


@pytest.mark.parametrize("text", [EMPTY, "not json", json.dumps({"RESULT": {"OIL": [{"PRODCD": "B027", "PRICE": "12"}]}}), json.dumps({})])
def test_unusable_responses_raise(text):
    with pytest.raises(fp.FuelPriceUnavailable):
        fp.parse(text, "B027")


def test_second_call_is_served_from_cache_and_only_the_first_costs_quota():
    f = Fake(GOOD)
    a = fp.get_price("k", "GASOLINE", "서울", 250, fetch=f)
    b = fp.get_price("k", "GASOLINE", "서울", 250, fetch=f)
    assert (a.price_krw, a.cached, b.cached) == (1678, False, True)
    assert len(f.urls) == 1 and fp.calls_today() == 1
    assert "sido=01" in f.urls[0] and "avgSidoPrice" in f.urls[0] and "prodcd=B027" in f.urls[0]
    assert "certkey=k" in f.urls[0] and "code=" not in f.urls[0]


def test_jeonnam_and_gwangju_share_one_opinet_region():
    for name in ("전남", "광주"):
        fp.reset()
        f = Fake(GOOD)
        out = fp.get_price("k", "GASOLINE", name, 250, fetch=f)
        assert "sido=20" in f.urls[0] and out.region == "전남광주"


def test_region_missing_uses_the_national_endpoint():
    f = Fake(GOOD)
    out = fp.get_price("k", "GASOLINE", None, 250, fetch=f)
    assert out.region == "전국" and "avgAllPrice" in f.urls[0] and "sido" not in f.urls[0]


def test_daily_limit_blocks_further_calls_without_calling_out():
    f = Fake(GOOD)
    fp.get_price("k", "GASOLINE", "서울", 1, fetch=f)
    with pytest.raises(fp.FuelPriceUnavailable, match="limit"):
        fp.get_price("k", "DIESEL", "서울", 1, fetch=f)
    assert len(f.urls) == 1


def test_failed_calls_still_count_and_empty_results_are_not_cached():
    f = Fake(EMPTY)
    for _ in range(2):
        with pytest.raises(fp.FuelPriceUnavailable):
            fp.get_price("k", "GASOLINE", "서울", 250, fetch=f)
    assert len(f.urls) == 2 and fp.calls_today() == 2


def test_network_errors_become_unavailable():
    def boom(url):
        raise TimeoutError("slow")
    with pytest.raises(fp.FuelPriceUnavailable):
        fp.get_price("k", "GASOLINE", "서울", 250, fetch=boom)


def test_no_key_never_calls_out():
    f = Fake(GOOD)
    with pytest.raises(fp.FuelPriceUnavailable):
        fp.get_price("", "GASOLINE", "서울", 250, fetch=f)
    assert f.urls == []


def test_endpoint_requires_login_and_hides_the_key(client, monkeypatch):
    assert client.get("/api/v1/fuel-price").status_code == 401
    monkeypatch.setattr(get_settings(), "opinet_key", "SECRET-KEY")
    monkeypatch.setattr(fp, "_http_get", Fake(GOOD))
    a = Actor(client, "A")
    r = a.get("/fuel-price?fuel=GASOLINE&region=서울")
    assert r.status_code == 200, r.text
    body = r.json()
    assert (body["price_krw"], body["region"], body["source"]) == (1678, "서울", "OPINET")
    assert "SECRET-KEY" not in r.text


def test_endpoint_answers_503_with_a_korean_message_when_unavailable(client):
    a = Actor(client, "A")
    r = a.get("/fuel-price")
    assert r.status_code == 503 and r.json()["code"] == "FUEL_PRICE_UNAVAILABLE" and "직접 입력" in r.json()["message_ko"]


def test_unknown_fuel_is_rejected(client):
    assert Actor(client, "A").get("/fuel-price?fuel=COAL").status_code == 422
