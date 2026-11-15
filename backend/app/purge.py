from app.config import get_settings
from app.db import models as m
from app.db.session import SessionLocal
from app.services.privacy import purge_expired

if __name__ == "__main__":
    with SessionLocal() as db:
        result = purge_expired(db, m.utcnow(), get_settings().retention_days)
        db.commit()
    print(f"retention {get_settings().retention_days} days -> purged {result}")
