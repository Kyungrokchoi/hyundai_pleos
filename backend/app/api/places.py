from fastapi import APIRouter, Depends, Query

from app import schemas as s
from app.api.deps import current_user
from app.config import get_settings
from app.db import models as m
from app.errors import ApiError
from app.services import places

router = APIRouter(tags=["places"])


class PlaceOut(s.Api):
    address: str
    short: str


@router.get("/places/region", response_model=PlaceOut)
def region_name(lat: float = Query(..., ge=33.0, le=39.0), lon: float = Query(..., ge=124.0, le=132.0),
                _user: m.User = Depends(current_user)):
    try:
        p = places.lookup(get_settings().kakao_rest_key, lat, lon)
    except places.PlaceUnavailable:
        raise ApiError(503, "PLACE_UNAVAILABLE", "주소를 불러오지 못했어요.") from None
    return PlaceOut(address=p.address, short=p.short)
