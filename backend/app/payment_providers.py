"""Provider contracts. No merchant network/API protocol is assumed by this module."""
from dataclasses import dataclass
from datetime import datetime
from decimal import Decimal
from typing import Mapping, Protocol

from fastapi import HTTPException
from sqlalchemy import select

from .config import settings


class ProviderUnavailable(Exception):
    """Uncertain provider outcome; retain the transaction for reconciliation."""


class InvalidWebhook(Exception):
    """Signature, timestamp or authenticated merchant identity is invalid."""


@dataclass(frozen=True)
class PaymentRequest:
    transaction_id: str
    amount: Decimal
    currency: str
    description: str


@dataclass(frozen=True)
class PaymentObservation:
    reference: str
    transaction_id: str
    amount: Decimal
    currency: str
    status: str


@dataclass(frozen=True)
class VerifiedWebhook:
    event_id: str
    reference: str
    transaction_id: str
    occurred_at: datetime


class PaymentProvider(Protocol):
    """Adapters authenticate merchant identity, use bounded timeouts and redact errors.

    Creation must use transaction_id as the provider's idempotency key. Verification
    queries the provider independently of callbacks. Refunds use refund_id as their
    idempotency key and return an ID only after confirmed settlement. Hosted payment
    collection and real provider-specific webhook authentication belong in an adapter.
    """
    def create_payment(self, request: PaymentRequest) -> str: ...
    def verify_payment(self, reference: str) -> PaymentObservation: ...
    def verify_webhook(self, payload: bytes, headers: Mapping[str, str]) -> VerifiedWebhook: ...
    def refund_payment(self, reference: str, amount: Decimal, currency: str, refund_id: str) -> str: ...


class DevelopmentPaymentProvider:
    """A local simulator, with no external gateway or actual movement of money."""
    def __init__(self, db):
        self.db = db

    def create_payment(self, request):
        return 'development-' + request.transaction_id

    def verify_payment(self, reference):
        from .billing import PaymentAttempt, Transaction
        attempt = self.db.scalar(select(PaymentAttempt).where(
            PaymentAttempt.provider == 'development', PaymentAttempt.provider_reference == reference))
        if not attempt:
            raise ProviderUnavailable('Unknown development attempt')
        transaction = self.db.get(Transaction, attempt.transaction_id)
        return PaymentObservation(reference, transaction.id, transaction.amount,
                                  transaction.currency, attempt.development_outcome or 'PENDING')

    def verify_webhook(self, payload, headers):
        # The simulator has an authenticated, development-only settlement route.
        raise InvalidWebhook('Development webhooks are unavailable')

    def refund_payment(self, reference, amount, currency, refund_id):
        return 'development-refund-' + refund_id


def get_provider(name, db) -> PaymentProvider:
    if name == 'development' and settings.app_env == 'development' and settings.development_payments:
        return DevelopmentPaymentProvider(db)
    # Add a genuine merchant adapter only after its protocol and credentials are
    # provisioned. An arbitrary name can never select a simulator in production.
    raise HTTPException(503, 'No merchant payment adapter configured')
