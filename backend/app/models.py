import uuid
from sqlalchemy import String, Text, Integer, DateTime, ForeignKey, JSON, UniqueConstraint, event
from sqlalchemy.orm import Mapped, mapped_column
from .db import Base, utcnow


def uid():
    return str(uuid.uuid4())


class User(Base):
    __tablename__ = 'users'
    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=uid)
    email: Mapped[str] = mapped_column(String(254), unique=True)
    password_hash: Mapped[str] = mapped_column(Text)
    role: Mapped[str] = mapped_column(String(30), default='buyer')
    organization_id: Mapped[str | None] = mapped_column(String(36), nullable=True)
    created_at: Mapped[object] = mapped_column(DateTime(timezone=True), default=utcnow)
    mfa_secret: Mapped[str | None] = mapped_column(Text, nullable=True)
    mfa_last_step: Mapped[int] = mapped_column(Integer, default=-1)
    email_verified_at: Mapped[object | None] = mapped_column(DateTime(timezone=True), nullable=True)
    session_version: Mapped[int] = mapped_column(Integer, default=0, server_default='0')


class AccountAction(Base):
    __tablename__ = 'account_actions'
    token_hash: Mapped[str] = mapped_column(String(64), primary_key=True)
    user_id: Mapped[str] = mapped_column(ForeignKey('users.id'), index=True)
    kind: Mapped[str] = mapped_column(String(30))
    expires_at: Mapped[object] = mapped_column(DateTime(timezone=True))
    used: Mapped[int] = mapped_column(Integer, default=0)
    created_at: Mapped[object] = mapped_column(DateTime(timezone=True), default=utcnow)


class MailOutbox(Base):
    __tablename__ = 'mail_outbox'
    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=uid)
    user_id: Mapped[str] = mapped_column(ForeignKey('users.id'), index=True)
    payload: Mapped[str | None] = mapped_column(Text, nullable=True)
    state: Mapped[str] = mapped_column(String(20), default='PENDING')
    attempts: Mapped[int] = mapped_column(Integer, default=0)
    available_at: Mapped[object] = mapped_column(DateTime(timezone=True), default=utcnow)
    last_error: Mapped[str | None] = mapped_column(String(80), nullable=True)
    created_at: Mapped[object] = mapped_column(DateTime(timezone=True), default=utcnow)
    sent_at: Mapped[object | None] = mapped_column(DateTime(timezone=True), nullable=True)


class RefreshToken(Base):
    __tablename__ = 'refresh_tokens'
    token_hash: Mapped[str] = mapped_column(String(64), primary_key=True)
    user_id: Mapped[str] = mapped_column(ForeignKey('users.id'), index=True)
    family: Mapped[str] = mapped_column(String(36), index=True)
    expires_at: Mapped[object] = mapped_column(DateTime(timezone=True))
    used: Mapped[int] = mapped_column(Integer, default=0)
    revoked: Mapped[int] = mapped_column(Integer, default=0)
    mfa: Mapped[int] = mapped_column(Integer, default=0)


class Inspection(Base):
    __tablename__ = 'inspections'
    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=uid)
    owner_id: Mapped[str] = mapped_column(ForeignKey('users.id'), index=True)
    status: Mapped[str] = mapped_column(String(30), default='CREATED')
    commercial_status: Mapped[str] = mapped_column(String(30), default='FREE')
    device_profile: Mapped[dict] = mapped_column(JSON, default=dict)
    capabilities: Mapped[dict] = mapped_column(JSON, default=dict)
    definitions: Mapped[list] = mapped_column(JSON, default=list)
    score_rule_version: Mapped[int] = mapped_column(Integer, default=2, server_default='1')
    scanner_device_id: Mapped[str | None] = mapped_column(String(100), nullable=True)
    capture_nonce: Mapped[str | None] = mapped_column(String(100), nullable=True)
    pairing_hash: Mapped[str | None] = mapped_column(String(64), nullable=True)
    pairing_expiry: Mapped[object | None] = mapped_column(DateTime(timezone=True), nullable=True)
    created_at: Mapped[object] = mapped_column(DateTime(timezone=True), default=utcnow)
    finalized_at: Mapped[object | None] = mapped_column(DateTime(timezone=True), nullable=True)
    imei_last4: Mapped[str | None] = mapped_column(String(4), nullable=True)
    imei_hash: Mapped[str | None] = mapped_column(String(64), nullable=True)
    pta_status: Mapped[str] = mapped_column(String(30), default='NOT_VERIFIED')


class Result(Base):
    __tablename__ = 'diagnostic_results'
    id: Mapped[str] = mapped_column(String(36), primary_key=True)
    inspection_id: Mapped[str] = mapped_column(ForeignKey('inspections.id'), index=True)
    check_id: Mapped[str] = mapped_column(String(150))
    payload: Mapped[dict] = mapped_column(JSON)
    payload_hash: Mapped[str] = mapped_column(String(64))
    received_at: Mapped[object] = mapped_column(DateTime(timezone=True), default=utcnow)


class Report(Base):
    __tablename__ = 'reports'
    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=uid)
    inspection_id: Mapped[str] = mapped_column(ForeignKey('inspections.id'), unique=True)
    public_token: Mapped[str] = mapped_column(String(100), unique=True)
    payload: Mapped[dict] = mapped_column(JSON)
    canonical_json: Mapped[str] = mapped_column(Text)
    hash: Mapped[str] = mapped_column(String(64))
    signature: Mapped[str] = mapped_column(Text)
    key_version: Mapped[str] = mapped_column(String(50))
    public_key: Mapped[str] = mapped_column(Text)
    created_at: Mapped[object] = mapped_column(DateTime(timezone=True), default=utcnow)


class Revocation(Base):
    __tablename__ = 'report_revocations'
    report_id: Mapped[str] = mapped_column(ForeignKey('reports.id'), primary_key=True)
    reason: Mapped[str] = mapped_column(String(300))
    created_at: Mapped[object] = mapped_column(DateTime(timezone=True), default=utcnow)


class Audit(Base):
    __tablename__ = 'audit_events'
    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=uid)
    actor_id: Mapped[str | None] = mapped_column(String(36), nullable=True)
    event: Mapped[str] = mapped_column(String(80))
    inspection_id: Mapped[str | None] = mapped_column(String(36), nullable=True)
    data: Mapped[dict] = mapped_column(JSON, default=dict)
    created_at: Mapped[object] = mapped_column(DateTime(timezone=True), default=utcnow)


def immutable(mapper, connection, target):
    raise ValueError('Append-only record cannot be edited or deleted')


for model in (Result, Report, Audit, Revocation):
    event.listen(model, 'before_update', immutable)
    event.listen(model, 'before_delete', immutable)
