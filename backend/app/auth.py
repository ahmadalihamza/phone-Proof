import hashlib
import secrets
import uuid
from datetime import timedelta, timezone
from typing import Annotated
import jwt
from argon2 import PasswordHasher
from argon2.exceptions import VerificationError, InvalidHashError
from fastapi import APIRouter, Depends, HTTPException
from fastapi.security import HTTPBearer, HTTPAuthorizationCredentials
from pydantic import BaseModel, EmailStr, Field, ConfigDict
from sqlalchemy import select, update
from .config import settings
from .db import get_db, utcnow
from .models import User, RefreshToken
from .audit import audit

router = APIRouter(prefix='/auth', tags=['Authentication'])
hasher = PasswordHasher()
dummy_hash = hasher.hash(secrets.token_urlsafe(32))
bearer = HTTPBearer(auto_error=False)


class Credentials(BaseModel):
    model_config = ConfigDict(extra='forbid')
    email: EmailStr
    password: str = Field(min_length=12, max_length=128)
    otp: str | None = Field(default=None, pattern=r'^\d{6}$')


class RefreshInput(BaseModel):
    refresh_token: str = Field(min_length=32, max_length=200)


def digest(token):
    return hashlib.sha256(token.encode()).hexdigest()


def as_utc(value):
    return value.replace(tzinfo=timezone.utc) if value.tzinfo is None else value


def issue(db, user, family=None, mfa=False):
    now = utcnow()
    token = secrets.token_urlsafe(48)
    db.add(RefreshToken(token_hash=digest(token), user_id=user.id, family=family or str(uuid.uuid4()), expires_at=now + timedelta(days=30),mfa=int(mfa)))
    access = jwt.encode({'sub': user.id, 'iat': now, 'exp': now + timedelta(minutes=15), 'iss': 'phoneproof', 'aud': 'phoneproof-app', 'type': 'access','mfa':bool(mfa),'session_version':user.session_version}, settings.jwt_secret, algorithm='HS256')
    return {'access_token': access, 'refresh_token': token, 'token_type': 'bearer', 'expires_in': 900}


def current_user(credentials: Annotated[HTTPAuthorizationCredentials | None, Depends(bearer)], db=Depends(get_db, scope='function')):
    try:
        if credentials is None:
            raise ValueError()
        claims = jwt.decode(credentials.credentials, settings.jwt_secret, algorithms=['HS256'], audience='phoneproof-app', issuer='phoneproof', options={'require': ['exp', 'iat', 'sub']})
        if claims.get('type') != 'access':
            raise ValueError()
        user = db.get(User, claims['sub'])
        if not user:
            raise ValueError()
        if claims.get('session_version',0) != user.session_version:
            raise ValueError()
        if user.role=='system_admin' and (user.mfa_secret or settings.app_env=='production') and not claims.get('mfa'):
            raise ValueError()
        return user
    except (jwt.PyJWTError, ValueError):
        raise HTTPException(401, 'Authentication required', headers={'WWW-Authenticate': 'Bearer'})


def require_admin(user=Depends(current_user)):
    if user.role != 'system_admin':
        raise HTTPException(403, 'Administrator role required')
    return user


@router.post('/register', status_code=201)
def register(data: Credentials, db=Depends(get_db, scope='function')):
    email = str(data.email).lower()
    if db.scalar(select(User).where(User.email == email)):
        raise HTTPException(409, 'Account already exists')
    user = User(email=email, password_hash=hasher.hash(data.password))
    db.add(user)
    db.flush()
    audit(db, user.id, 'ACCOUNT_CREATED')
    return issue(db, user)


@router.post('/login')
def login(data: Credentials, db=Depends(get_db, scope='function')):
    user = db.scalar(select(User).where(User.email == str(data.email).lower()).with_for_update())
    try:
        hasher.verify(user.password_hash if user else dummy_hash, data.password)
    except (VerificationError, InvalidHashError):
        raise HTTPException(401, 'Invalid email or password')
    if not user:
        raise HTTPException(401, 'Invalid email or password')
    mfa_ok=False
    if user.role=='system_admin':
        if settings.app_env=='production' and not user.mfa_secret:
            raise HTTPException(403,'Administrator MFA enrollment required')
        if user.mfa_secret:
            from .mfa import decrypt_secret,validate
            step=validate(decrypt_secret(user.mfa_secret),data.otp,user.mfa_last_step)
            if step is None:raise HTTPException(401,'Valid, unused authenticator code required')
            user.mfa_last_step=step;mfa_ok=True
    if hasher.check_needs_rehash(user.password_hash):
        user.password_hash = hasher.hash(data.password)
    audit(db, user.id, 'LOGIN')
    return issue(db, user,mfa=mfa_ok)


@router.post('/refresh')
def refresh(data: RefreshInput, db=Depends(get_db, scope='function')):
    token = db.get(RefreshToken, digest(data.refresh_token))
    if not token:
        raise HTTPException(401, 'Refresh token expired or revoked')
    # Password reset and login lock the account first. Taking the same lock here
    # ensures a refresh either completes before reset (and is revoked by it), or
    # sees the reset's revocation and cannot issue a session afterward.
    user = db.scalar(select(User).where(User.id == token.user_id).with_for_update()
                     .execution_options(populate_existing=True))
    token = db.scalar(select(RefreshToken).where(RefreshToken.token_hash == digest(data.refresh_token)).with_for_update()
                      .execution_options(populate_existing=True))
    if not user or not token or as_utc(token.expires_at) <= utcnow() or token.revoked:
        raise HTTPException(401, 'Refresh token expired or revoked')
    if token.used:
        db.execute(update(RefreshToken).where(RefreshToken.family == token.family).values(revoked=1))
        audit(db, token.user_id, 'REFRESH_REUSE_REVOKED')
        db.commit()  # Persist revocation even though the request is rejected.
        raise HTTPException(401, 'Refresh token reuse detected; sign in again')
    token.used = 1
    return issue(db, user, token.family, bool(token.mfa))


@router.post('/logout')
def logout(data: RefreshInput, user=Depends(current_user), db=Depends(get_db, scope='function')):
    # Use the same account-first order as refresh and password recovery.
    user = db.scalar(select(User).where(User.id == user.id).with_for_update()
                     .execution_options(populate_existing=True))
    token = db.get(RefreshToken, digest(data.refresh_token))
    if token and token.user_id == user.id:
        db.execute(update(RefreshToken).where(RefreshToken.family == token.family).values(revoked=1))
    audit(db, user.id, 'LOGOUT')
    return {'revoked': True}


@router.get('/me')
def me(user=Depends(current_user)):
    return {'id': user.id, 'email': user.email, 'role': user.role, 'email_verified':user.email_verified_at is not None}
