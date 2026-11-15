from datetime import datetime
from typing import Literal

from fastapi import APIRouter, Depends, Query

from app import schemas as s
from app.api.deps import current_user
from app.config import get_settings
from app.db import models as m
from app.errors import ApiError
from app.services import fuel_price

router = APIRouter(tags=["fuel"])


class FuelPriceOut(s.Api):
    fuel: Literal["GASOLINE", "PREMIUM", "DIESEL", "LPG"]
    region: str
    price_krw: int
    diff_krw: float | None = None
    fetched_at: datetime
    cached: bool
    source: Literal["OPINET"] = "OPINET"
    attribution: str = "유가 정보: 한국석유공사 오피넷"


@router.get("/fuel-price", response_model=FuelPriceOut)
def fuel_price_now(fuel: Literal["GASOLINE", "PREMIUM", "DIESEL", "LPG"] = Query("GASOLINE"),
                   region: str | None = Query(None, max_length=10, description="시·도 이름(예: 서울). 비우면 전국 평균."),
                   _user: m.User = Depends(current_user)):
    cfg = get_settings()
    try:
        p = fuel_price.get_price(cfg.opinet_key, fuel, region, cfg.opinet_daily_limit)
    except fuel_price.FuelPriceUnavailable:
        raise ApiError(503, "FUEL_PRICE_UNAVAILABLE", "유가를 불러오지 못했어요. 직접 입력해 주세요.") from None
    return FuelPriceOut(fuel=p.fuel, region=p.region, price_krw=p.price_krw, diff_krw=p.diff_krw, fetched_at=p.fetched_at, cached=p.cached)
