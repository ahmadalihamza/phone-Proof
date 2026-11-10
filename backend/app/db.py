from datetime import datetime, timezone
from fastapi import Request
from sqlalchemy import create_engine, event, text
from sqlalchemy.orm import DeclarativeBase, sessionmaker
from .config import settings


class Base(DeclarativeBase):
    pass


def utcnow():
    return datetime.now(timezone.utc)


engine = create_engine(settings.database_url, pool_pre_ping=True,
                       connect_args={'check_same_thread': False, 'timeout': 30} if settings.database_url.startswith('sqlite') else {})
if engine.dialect.name == 'sqlite':
    @event.listens_for(engine, 'connect')
    def sqlite_setup(connection, record):
        connection.execute('PRAGMA foreign_keys=ON')
        connection.execute('PRAGMA journal_mode=WAL')

SessionLocal = sessionmaker(engine, expire_on_commit=False)


def begin_write(db):
    """Retake SQLite's writer lock after an explicit durable workflow checkpoint."""
    if db.get_bind().dialect.name == 'sqlite':
        db.execute(text('BEGIN IMMEDIATE'))


def get_db(request: Request):
    with SessionLocal() as db:
        try:
            # Serialize SQLite writers; PostgreSQL uses row locks in mutable workflows.
            if request.method in {'POST', 'PUT', 'PATCH', 'DELETE'}:
                begin_write(db)
            yield db
            db.commit()
        except Exception:
            db.rollback()
            raise
