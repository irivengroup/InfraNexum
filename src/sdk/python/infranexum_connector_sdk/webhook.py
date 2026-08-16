"""HMAC-SHA256 webhook signing, verification and bounded replay protection."""

from __future__ import annotations

from collections import OrderedDict
from datetime import datetime, timedelta, timezone
from hashlib import sha256
import hmac
from threading import RLock
from typing import Protocol

from .models import require_delivery_id

MAX_WEBHOOK_BODY_BYTES = 1_048_576
MIN_SECRET_BYTES = 32


class WebhookVerificationError(ValueError):
    """Raised when a webhook cannot pass authenticity or replay validation."""


class ReplayGuard(Protocol):
    """Replay reservation boundary; production runtimes should use durable inbox state."""

    def reserve(self, delivery_id: str, expires_at: datetime, now: datetime) -> bool:
        """Reserve a delivery once, returning False when it was already accepted."""


class InMemoryReplayGuard:
    """Thread-safe bounded reference replay guard for SDK tests and local connectors."""

    def __init__(self, maximum_entries: int = 10_000) -> None:
        if maximum_entries < 1 or maximum_entries > 1_000_000:
            raise ValueError("maximum_entries must be between 1 and 1000000")
        self._maximum_entries = maximum_entries
        self._entries: OrderedDict[str, datetime] = OrderedDict()
        self._lock = RLock()

    def reserve(self, delivery_id: str, expires_at: datetime, now: datetime) -> bool:
        delivery_id = require_delivery_id(delivery_id)
        _aware(expires_at, "expires_at")
        _aware(now, "now")
        if expires_at <= now:
            raise ValueError("expires_at must be after now")
        with self._lock:
            self._purge(now)
            if delivery_id in self._entries:
                return False
            if len(self._entries) >= self._maximum_entries:
                self._entries.popitem(last=False)
            self._entries[delivery_id] = expires_at
            return True

    def _purge(self, now: datetime) -> None:
        expired = [key for key, expires in self._entries.items() if expires <= now]
        for key in expired:
            self._entries.pop(key, None)


class WebhookSigner:
    """Creates the canonical InfraNexum HMAC webhook signature."""

    @staticmethod
    def sign(secret: bytes, body: bytes, delivery_id: str, timestamp: datetime) -> str:
        key = _secret(secret)
        payload = _payload(body, require_delivery_id(delivery_id), timestamp)
        return "sha256=" + hmac.new(key, payload, sha256).hexdigest()


class WebhookVerifier:
    """Verifies timestamp, HMAC signature and optional replay reservation."""

    def __init__(self, tolerance: timedelta = timedelta(minutes=5), replay_guard: ReplayGuard | None = None) -> None:
        if tolerance <= timedelta(0) or tolerance > timedelta(hours=1):
            raise ValueError("tolerance must be within (0, 1h]")
        self._tolerance = tolerance
        self._replay_guard = replay_guard

    def verify(
        self,
        secret: bytes,
        body: bytes,
        delivery_id: str,
        timestamp: datetime,
        signature: str,
        now: datetime | None = None,
    ) -> None:
        current = now or datetime.now(timezone.utc)
        _aware(current, "now")
        _aware(timestamp, "timestamp")
        delivery_id = require_delivery_id(delivery_id)
        if abs(current - timestamp) > self._tolerance:
            raise WebhookVerificationError("webhook timestamp outside tolerance")
        expected = WebhookSigner.sign(secret, body, delivery_id, timestamp)
        supplied = signature.strip() if isinstance(signature, str) else ""
        if len(supplied) != 71 or not supplied.startswith("sha256=") or not hmac.compare_digest(expected, supplied):
            raise WebhookVerificationError("invalid webhook signature")
        if self._replay_guard is not None:
            expires = current + self._tolerance
            if not self._replay_guard.reserve(delivery_id, expires, current):
                raise WebhookVerificationError("webhook delivery replayed")


def parse_unix_timestamp(value: str) -> datetime:
    """Parse the integer Unix-seconds form used by X-InfraNexum-Timestamp."""
    if not isinstance(value, str) or not value.isdigit() or len(value) > 12:
        raise WebhookVerificationError("invalid webhook timestamp")
    try:
        timestamp = int(value)
        return datetime.fromtimestamp(timestamp, tz=timezone.utc)
    except (OverflowError, OSError, ValueError) as exc:
        raise WebhookVerificationError("invalid webhook timestamp") from exc


def _secret(value: bytes) -> bytes:
    if not isinstance(value, bytes) or len(value) < MIN_SECRET_BYTES:
        raise ValueError(f"webhook secret must contain at least {MIN_SECRET_BYTES} bytes")
    return value


def _payload(body: bytes, delivery_id: str, timestamp: datetime) -> bytes:
    if not isinstance(body, bytes):
        raise TypeError("body must be bytes")
    if len(body) > MAX_WEBHOOK_BODY_BYTES:
        raise WebhookVerificationError("webhook body exceeds 1048576 bytes")
    _aware(timestamp, "timestamp")
    epoch_seconds = int(timestamp.timestamp())
    return str(epoch_seconds).encode("ascii") + b"." + delivery_id.encode("utf-8") + b"." + body


def _aware(value: datetime, field: str) -> None:
    if not isinstance(value, datetime) or value.tzinfo is None or value.utcoffset() is None:
        raise ValueError(f"{field} must be timezone-aware")
