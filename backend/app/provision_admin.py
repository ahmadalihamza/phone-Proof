"""Interactive operator-only provisioning: python -m app.provision_admin email@example.com."""
import base64
import getpass
import secrets
import sys
from urllib.parse import quote
from sqlalchemy import select
from .db import SessionLocal
from .models import User
from .auth import hasher
from .mfa import encrypt_secret
from .audit import audit

def main():
    email=sys.argv[1].strip().lower()
    password=getpass.getpass('New admin password (12+ characters): ')
    if len(password)<12:raise ValueError('Password too short')
    if getpass.getpass('Confirm password: ')!=password:raise ValueError('Passwords differ')
    secret=base64.b32encode(secrets.token_bytes(20)).decode()
    with SessionLocal() as db:
        if db.scalar(select(User).where(User.email==email)):raise ValueError('Account already exists; use a separately audited recovery process')
        user=User(email=email,password_hash=hasher.hash(password),role='system_admin',mfa_secret=encrypt_secret(secret))
        db.add(user);db.flush();audit(db,user.id,'ADMIN_PROVISIONED');db.commit()
    print('Enroll this one-time provisioning secret in your authenticator; do not save it in source or logs:')
    print('otpauth://totp/PhoneProof:'+quote(email)+'?secret='+secret+'&issuer=PhoneProof')

if __name__=='__main__':main()
