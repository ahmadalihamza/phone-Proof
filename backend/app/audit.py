from .models import Audit


def audit(db, actor_id, event, inspection_id=None, data=None):
    db.add(Audit(actor_id=actor_id, event=event, inspection_id=inspection_id, data=data or {}))
