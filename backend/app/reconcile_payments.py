"""Run periodically: python -m app.reconcile_payments --limit 100."""
import argparse
import json
import time

from sqlalchemy import text

from .db import SessionLocal, engine
from .payments import pending_transaction_ids, reconcile_transaction
from .billing import Transaction, lock_account
from .models import User


def run_once(limit):
    with SessionLocal() as db:
        ids=pending_transaction_ids(db,limit)
    for transaction_id in ids:
        with SessionLocal() as db:
            if engine.dialect.name=='sqlite':db.execute(text('BEGIN IMMEDIATE'))
            transaction=db.get(Transaction,transaction_id)
            lock_account(db,db.get(User,transaction.user_id));db.refresh(transaction)
            reconcile_transaction(db,transaction)
            db.commit()
    return {'checked':len(ids)}


def main(argv=None):
    parser = argparse.ArgumentParser(description='Reconcile a bounded batch of pending payments')
    parser.add_argument('--limit', type=int, default=100)
    parser.add_argument('--watch',action='store_true',help='Keep checking pending payments until interrupted')
    parser.add_argument('--interval',type=int,default=60,help='Seconds between batches (10–3600)')
    args = parser.parse_args(argv)
    if not 1 <= args.limit <= 1000:
        parser.error('--limit must be between 1 and 1000')
    if not 10<=args.interval<=3600:parser.error('--interval must be between 10 and 3600 seconds')
    try:
        while True:
            print(json.dumps(run_once(args.limit)),flush=True)
            if not args.watch:return
            time.sleep(args.interval)
    except KeyboardInterrupt:
        return


if __name__ == '__main__':
    main()
