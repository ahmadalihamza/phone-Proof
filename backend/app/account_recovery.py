"""Single-use account actions with transactional, encrypted email delivery."""
import secrets
from datetime import timedelta

from fastapi import APIRouter, Depends, HTTPException
from pydantic import EmailStr, Field
from sqlalchemy import select, update

from .audit import audit
from .auth import as_utc, current_user, digest, hasher
from .config import settings
from .db import get_db, utcnow
from .inspections import Strict
from .models import AccountAction, MailOutbox, RefreshToken, User
from .mail_delivery import cancel_action_mail, encrypt_payload

router=APIRouter(prefix='/auth',tags=['Account recovery'])
RESEND_COOLDOWN = timedelta(seconds=60)


class RecoveryRequest(Strict):
    email:EmailStr


class ActionToken(Strict):
    token:str=Field(min_length=40,max_length=100)


class ResetPassword(ActionToken):
    password:str=Field(min_length=12,max_length=128)
    otp:str|None=Field(default=None,pattern=r'^\d{6}$')


def mail_available():
    return bool(settings.smtp_host and settings.smtp_from)


def enqueue_action(db,user,kind):
    # Caller owns the account lock. A short cooldown prevents repeated requests
    # from invalidating a code before the owner has had time to use it.
    latest=db.scalar(select(AccountAction).where(AccountAction.user_id==user.id,AccountAction.kind==kind,
                                                AccountAction.used==0).order_by(AccountAction.created_at.desc()))
    if latest and as_utc(latest.created_at)+RESEND_COOLDOWN>utcnow():
        return
    # A fresh message invalidates earlier links for the same purpose.
    invalidate_actions(db,user.id,kind)
    token=secrets.token_urlsafe(48)
    token_hash=digest(token)
    db.add(AccountAction(token_hash=token_hash,user_id=user.id,kind=kind,expires_at=utcnow()+timedelta(minutes=30)))
    label='reset your password' if kind=='PASSWORD_RESET' else 'verify your email'
    db.add(MailOutbox(user_id=user.id,payload=encrypt_payload({
        'to':user.email,'subject':'PhoneProof account verification',
        'action_hash':token_hash,
        'body':f'Use this code in the PhoneProof Account screen to {label}. It expires in 30 minutes and works once.\n\n{token}\n\nIf you did not request this, ignore this email. Do not share the code.'
    })))
    audit(db,user.id,'ACCOUNT_ACTION_REQUESTED',data={'kind':kind})


def invalidate_actions(db,user_id,kind):
    hashes=set(db.scalars(select(AccountAction.token_hash).where(AccountAction.user_id==user_id,
                     AccountAction.kind==kind,AccountAction.used==0)))
    if hashes:
        db.execute(update(AccountAction).where(AccountAction.token_hash.in_(hashes)).values(used=1))
        cancel_action_mail(db,user_id,hashes)


@router.get('/capabilities')
def capabilities():
    return {'email_recovery_available':mail_available(),'email_verification_available':mail_available()}


@router.post('/recovery/request',status_code=202)
def request_recovery(data:RecoveryRequest,db=Depends(get_db,scope='function')):
    if not mail_available():raise HTTPException(503,'Email recovery is not configured for this service')
    user=db.scalar(select(User).where(User.email==str(data.email).lower()).with_for_update())
    if user:enqueue_action(db,user,'PASSWORD_RESET')
    # Neither token nor account existence is disclosed through this response.
    return {'message':'If the account exists, a recovery email will be queued. Check your inbox.'}


def consume(db,token,kind):
    # Lock the account before its tokens everywhere (request, reset and refresh).
    # The initial lookup identifies the account only; the locked reread below is
    # authoritative after a concurrent request/reset has finished.
    action=db.get(AccountAction,digest(token))
    if not action:
        raise HTTPException(400,'Code is invalid, expired or already used')
    user=db.scalar(select(User).where(User.id==action.user_id).with_for_update().execution_options(populate_existing=True))
    action=db.scalar(select(AccountAction).where(AccountAction.token_hash==digest(token)).with_for_update()
                     .execution_options(populate_existing=True))
    if not user or not action or action.kind!=kind or action.used or as_utc(action.expires_at)<=utcnow():
        raise HTTPException(400,'Code is invalid, expired or already used')
    return action,user


@router.post('/recovery/complete')
def complete_recovery(data:ResetPassword,db=Depends(get_db,scope='function')):
    action,user=consume(db,data.token,'PASSWORD_RESET')
    if user.role=='system_admin':
        from .mfa import decrypt_secret,validate
        step=validate(decrypt_secret(user.mfa_secret),data.otp,user.mfa_last_step) if user.mfa_secret else None
        if step is None:raise HTTPException(401,'Administrator recovery also requires an unused authenticator code')
        user.mfa_last_step=step
    user.password_hash=hasher.hash(data.password)
    user.session_version+=1
    db.execute(update(RefreshToken).where(RefreshToken.user_id==user.id).values(revoked=1))
    invalidate_actions(db,user.id,'PASSWORD_RESET')
    audit(db,user.id,'PASSWORD_RESET_SESSIONS_REVOKED')
    return {'message':'Password updated. Sign in again on each device.'}


@router.post('/email/request-verification',status_code=202)
def request_verification(user=Depends(current_user),db=Depends(get_db,scope='function')):
    if not mail_available():raise HTTPException(503,'Email verification is not configured for this service')
    user=db.scalar(select(User).where(User.id==user.id).with_for_update().execution_options(populate_existing=True))
    if not user.email_verified_at:enqueue_action(db,user,'EMAIL_VERIFY')
    return {'message':'Verification email queued if your address still needs verification.'}


@router.post('/email/verify')
def verify_email(data:ActionToken,user=Depends(current_user),db=Depends(get_db,scope='function')):
    action,account=consume(db,data.token,'EMAIL_VERIFY')
    if action.user_id!=user.id:raise HTTPException(400,'Code is invalid for this account')
    account.email_verified_at=utcnow()
    invalidate_actions(db,account.id,'EMAIL_VERIFY')
    audit(db,user.id,'EMAIL_VERIFIED')
    return {'email_verified':True}
