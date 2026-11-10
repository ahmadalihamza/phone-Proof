"""Operator-run bounded SMTP outbox worker: python -m app.mail_delivery.

No messages are sent during API transactions. TLS certificate verification is
mandatory; absent credentials/configuration never simulate a successful send.
SMTP delivery is at least once: a crash after acceptance but before recording
SENT can cause a duplicate email. Codes remain single use regardless.
"""
import argparse
import base64
import json
import secrets
import smtplib
import ssl
import time
from datetime import timedelta
from email.message import EmailMessage

from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from sqlalchemy import select, text

from .config import settings
from .db import SessionLocal, utcnow
from .mfa import encryption_key
from .models import AccountAction, MailOutbox


def encrypt_payload(payload):
    nonce=secrets.token_bytes(12)
    return base64.b64encode(nonce+AESGCM(encryption_key()).encrypt(nonce,json.dumps(payload).encode(),b'phoneproof-mail-v1')).decode()


def decrypt_payload(payload):
    raw=base64.b64decode(payload)
    return json.loads(AESGCM(encryption_key()).decrypt(raw[:12],raw[12:],b'phoneproof-mail-v1'))


def cancel_action_mail(db,user_id,action_hashes):
    """Discard obsolete encrypted codes in the same transaction as invalidation.

    The caller holds the account lock and has already invalidated its actions.
    A worker may already be sending an email; changing its state also fences its
    completion so it cannot restore a cancelled payload or record it as sent.
    """
    if not action_hashes:return
    items=db.scalars(select(MailOutbox).where(MailOutbox.user_id==user_id,
                     MailOutbox.state.in_(['PENDING','RETRY','SENDING'])).with_for_update())
    for item in items:
        try:
            payload=decrypt_payload(item.payload)
        except Exception:
            # The delivery worker handles malformed rows independently.
            continue
        if isinstance(payload,dict) and payload.get('action_hash') in action_hashes:
            item.state='CANCELLED';item.payload=None;item.last_error=None


def send_smtp(payload):
    if not settings.smtp_host or not settings.smtp_from:raise RuntimeError('SMTP is not configured')
    message=EmailMessage()
    message['From']=settings.smtp_from;message['To']=payload['to'];message['Subject']=payload['subject']
    message.set_content(payload['body'])
    tls=ssl.create_default_context()
    if settings.smtp_implicit_tls:
        transport=smtplib.SMTP_SSL(settings.smtp_host,settings.smtp_port,timeout=20,context=tls)
    else:
        transport=smtplib.SMTP(settings.smtp_host,settings.smtp_port,timeout=20)
    with transport:
        if not settings.smtp_implicit_tls:
            transport.ehlo();transport.starttls(context=tls);transport.ehlo()
        if settings.smtp_username:transport.login(settings.smtp_username,settings.smtp_password)
        refused=transport.send_message(message)
        if refused:raise RuntimeError('SMTP recipient rejected')


def deliver_batch(limit=20):
    if not isinstance(limit,int) or not 1<=limit<=100:
        raise ValueError('Mail batch limit must be between 1 and 100')
    sent=failed=0
    for _ in range(limit):
        with SessionLocal() as db:
            if db.bind.dialect.name=='sqlite':db.execute(text('BEGIN IMMEDIATE'))
            item=db.scalar(select(MailOutbox).where(MailOutbox.state.in_(['PENDING','RETRY','SENDING']),MailOutbox.available_at<=utcnow())
                           .order_by(MailOutbox.created_at).with_for_update(skip_locked=True).limit(1))
            if not item:break
            id=item.id
            # Do not send security codes after their useful lifetime.
            from .auth import as_utc
            if as_utc(item.created_at)+timedelta(minutes=30)<=utcnow():
                item.state='EXPIRED';item.payload=None;db.commit();continue
            if item.attempts>=5:
                item.state='FAILED';item.payload=None;db.commit();continue
            try:
                payload=decrypt_payload(item.payload)
                if not isinstance(payload,dict):raise ValueError('Invalid mail payload')
            except Exception as exception:
                item.state='FAILED';item.last_error=type(exception).__name__;item.payload=None
                db.commit();failed+=1;continue
            action=db.get(AccountAction,payload.get('action_hash',''))
            if (not action or action.user_id!=item.user_id or action.used
                    or as_utc(action.expires_at)<=utcnow()):
                item.state='CANCELLED';item.payload=None;db.commit();continue
            item.state='SENDING';item.attempts+=1;item.available_at=utcnow()+timedelta(minutes=5)
            attempt=item.attempts
            db.commit()
        try:
            send_smtp(payload)
            error=None
        except Exception as exception:
            error=type(exception).__name__
        with SessionLocal() as db:
            if db.bind.dialect.name=='sqlite':db.execute(text('BEGIN IMMEDIATE'))
            item=db.scalar(select(MailOutbox).where(MailOutbox.id==id).with_for_update())
            # Another worker may have reclaimed an expired lease while SMTP was
            # stalled. Its result owns the row; never overwrite it with this one.
            if not item or item.state!='SENDING' or item.attempts!=attempt:
                continue
            if error:
                item.last_error=error;item.state='FAILED' if item.attempts>=5 else 'RETRY'
                item.available_at=utcnow()+timedelta(seconds=min(60*2**item.attempts,600))
                if item.state=='FAILED':item.payload=None
                failed+=1
            else:
                item.state='SENT';item.sent_at=utcnow();item.payload=None;item.last_error=None;sent+=1
            db.commit()
    return {'sent':sent,'failed':failed}


def main(argv=None):
    parser=argparse.ArgumentParser(description='Deliver encrypted PhoneProof account emails through SMTP')
    parser.add_argument('--watch',action='store_true',help='Keep delivering bounded batches until interrupted')
    parser.add_argument('--interval',type=int,default=60,help='Seconds between batches (1–3600; default: 60)')
    parser.add_argument('--limit',type=int,default=20,help='Maximum messages per batch (1–100; default: 20)')
    args=parser.parse_args(argv)
    if not 1<=args.interval<=3600:parser.error('--interval must be between 1 and 3600 seconds')
    if not 1<=args.limit<=100:parser.error('--limit must be between 1 and 100')
    try:
        while True:
            print(json.dumps(deliver_batch(args.limit)),flush=True)
            if not args.watch:return
            time.sleep(args.interval)
    except KeyboardInterrupt:
        return


if __name__=='__main__':
    main()
