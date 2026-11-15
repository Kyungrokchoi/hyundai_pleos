from fastapi import APIRouter, Depends
from sqlalchemy.orm import Session

from app import schemas as s
from app.api.deps import active_member, current_user, locked_driver_trip
from app.db import models as m
from app.db.session import get_db
from app.errors import not_found
from app.services import settlements as svc
from app.services.trips import lock_trip

router = APIRouter(tags=["settlements"])


@router.post("/trips/{trip_id}/settlements/preview", response_model=s.SettlementOut)
def preview(trip_id: str, user: m.User = Depends(current_user), db: Session = Depends(get_db)):
    trip = locked_driver_trip(db, trip_id, user)
    st = svc.propose(db, trip, user)
    db.commit()
    return svc.to_out(st)


@router.post("/settlements/{settlement_id}/approvals", response_model=s.SettlementOut)
def approve(settlement_id: str, body: s.ApprovalIn, user: m.User = Depends(current_user), db: Session = Depends(get_db)):
    st = db.get(m.Settlement, settlement_id)
    if st is None:
        raise not_found()
    trip = db.get(m.Trip, st.trip_id)
    active_member(db, trip.group_id, user.id)
    lock_trip(db, trip.id)
    db.refresh(st)
    svc.decide(db, st, user, body.decision, body.comment, m.utcnow())
    db.commit()
    return svc.to_out(st)
