"""Immutable connector SDK value objects and validation helpers."""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timedelta
from enum import StrEnum
from types import MappingProxyType
from typing import Any, Mapping
import re

_SEMVER = re.compile(r"^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?$")
_TOKEN = re.compile(r"^[a-z][a-z0-9]*(?:[._:-][a-z0-9][a-z0-9-]*)*$")
_ID = re.compile(r"^[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*$")
_DELIVERY_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,159}$")


def require_text(value: str, field_name: str, maximum: int) -> str:
    """Normalize one bounded non-empty text field and reject control characters."""
    if not isinstance(value, str):
        raise TypeError(f"{field_name} must be a string")
    normalized = value.strip()
    if not normalized or len(normalized) > maximum or any(ord(ch) < 32 or ord(ch) == 127 for ch in normalized):
        raise ValueError(f"invalid {field_name}")
    return normalized


def require_identifier(value: str, field_name: str = "identifier") -> str:
    """Validate a stable connector/package identifier."""
    normalized = require_text(value, field_name, 128)
    if not _ID.fullmatch(normalized):
        raise ValueError(f"invalid {field_name}")
    return normalized


def require_token(value: str, field_name: str = "token", maximum: int = 160) -> str:
    """Validate a lower-case permission/capability token."""
    normalized = require_text(value, field_name, maximum)
    if not _TOKEN.fullmatch(normalized):
        raise ValueError(f"invalid {field_name}")
    return normalized


def require_semver(value: str, field_name: str = "version") -> str:
    """Validate Semantic Versioning 2.0 syntax without external dependencies."""
    normalized = require_text(value, field_name, 128)
    match = _SEMVER.fullmatch(normalized)
    if not match:
        raise ValueError(f"invalid {field_name}")
    prerelease = match.group(4)
    if prerelease:
        for identifier in prerelease.split("."):
            if identifier.isdigit() and len(identifier) > 1 and identifier.startswith("0"):
                raise ValueError(f"invalid {field_name}")
    return normalized



def compare_semver(left: str, right: str) -> int:
    """Compare two validated Semantic Versioning 2.0 values.

    Build metadata is intentionally ignored for precedence, exactly as required
    by SemVer. Numeric prerelease identifiers sort before non-numeric ones.
    """
    left_value = require_semver(left, "left version")
    right_value = require_semver(right, "right version")
    left_match = _SEMVER.fullmatch(left_value)
    right_match = _SEMVER.fullmatch(right_value)
    assert left_match is not None and right_match is not None
    left_core = tuple(int(left_match.group(index)) for index in (1, 2, 3))
    right_core = tuple(int(right_match.group(index)) for index in (1, 2, 3))
    if left_core != right_core:
        return 1 if left_core > right_core else -1
    return _compare_prerelease(left_match.group(4), right_match.group(4))


def _compare_prerelease(left: str | None, right: str | None) -> int:
    if left == right:
        return 0
    if left is None:
        return 1
    if right is None:
        return -1
    left_parts = left.split(".")
    right_parts = right.split(".")
    for left_part, right_part in zip(left_parts, right_parts):
        if left_part == right_part:
            continue
        left_numeric = left_part.isdigit()
        right_numeric = right_part.isdigit()
        if left_numeric and right_numeric:
            return 1 if int(left_part) > int(right_part) else -1
        if left_numeric != right_numeric:
            return -1 if left_numeric else 1
        return 1 if left_part > right_part else -1
    return 1 if len(left_parts) > len(right_parts) else -1

def require_delivery_id(value: str) -> str:
    """Validate an external delivery identifier used for deduplication."""
    normalized = require_text(value, "delivery_id", 160)
    if not _DELIVERY_ID.fullmatch(normalized):
        raise ValueError("invalid delivery_id")
    return normalized


def immutable_mapping(value: Mapping[str, Any] | None, field_name: str, maximum_items: int = 128) -> Mapping[str, Any]:
    """Return a shallow immutable copy after validating bounded string keys."""
    if value is None:
        return MappingProxyType({})
    if not isinstance(value, Mapping) or len(value) > maximum_items:
        raise ValueError(f"invalid {field_name}")
    copied: dict[str, Any] = {}
    for key, item in value.items():
        copied[require_token(str(key), f"{field_name} key", 128)] = item
    return MappingProxyType(copied)


class ConnectorMode(StrEnum):
    """Supported integration execution modes from the draft.21 contract."""

    PULL = "pull"
    PUSH = "push"
    WEBHOOK = "webhook"
    BATCH = "batch"
    STREAMING = "streaming"
    FEDERATED_READ = "federated-read"


class ConnectorOutcome(StrEnum):
    """Normalized connector invocation result."""

    SUCCESS = "success"
    RETRY = "retry"
    FAILURE = "failure"


@dataclass(frozen=True, slots=True)
class ConnectorContext:
    """Governed execution context supplied by InfraNexum to a connector."""

    connector_instance_id: str
    correlation_id: str
    deadline: datetime
    capabilities: frozenset[str] = field(default_factory=frozenset)
    metadata: Mapping[str, Any] = field(default_factory=dict)

    def __post_init__(self) -> None:
        object.__setattr__(self, "connector_instance_id", require_text(self.connector_instance_id, "connector_instance_id", 160))
        object.__setattr__(self, "correlation_id", require_text(self.correlation_id, "correlation_id", 160))
        if self.deadline.tzinfo is None or self.deadline.utcoffset() is None:
            raise ValueError("deadline must be timezone-aware")
        object.__setattr__(self, "capabilities", frozenset(require_token(item, "capability") for item in self.capabilities))
        object.__setattr__(self, "metadata", immutable_mapping(self.metadata, "metadata", 64))


@dataclass(frozen=True, slots=True)
class ConnectorRequest:
    """Idempotent invocation request passed to a connector implementation."""

    mode: ConnectorMode
    operation: str
    idempotency_key: str
    payload: Mapping[str, Any]
    checkpoint: str | None = None

    def __post_init__(self) -> None:
        if not isinstance(self.mode, ConnectorMode):
            object.__setattr__(self, "mode", ConnectorMode(self.mode))
        object.__setattr__(self, "operation", require_token(self.operation, "operation"))
        object.__setattr__(self, "idempotency_key", require_text(self.idempotency_key, "idempotency_key", 200))
        object.__setattr__(self, "payload", immutable_mapping(self.payload, "payload", 512))
        if self.checkpoint is not None:
            object.__setattr__(self, "checkpoint", require_text(self.checkpoint, "checkpoint", 4096))


@dataclass(frozen=True, slots=True)
class ConnectorResult:
    """Normalized connector outcome with bounded retry and checkpoint metadata."""

    outcome: ConnectorOutcome
    output: Mapping[str, Any] = field(default_factory=dict)
    checkpoint: str | None = None
    retry_after: timedelta | None = None
    error_code: str | None = None

    def __post_init__(self) -> None:
        if not isinstance(self.outcome, ConnectorOutcome):
            object.__setattr__(self, "outcome", ConnectorOutcome(self.outcome))
        object.__setattr__(self, "output", immutable_mapping(self.output, "output", 512))
        if self.checkpoint is not None:
            object.__setattr__(self, "checkpoint", require_text(self.checkpoint, "checkpoint", 4096))
        if self.retry_after is not None:
            if self.retry_after <= timedelta(0) or self.retry_after > timedelta(hours=24):
                raise ValueError("retry_after must be within (0, 24h]")
        if self.error_code is not None:
            object.__setattr__(self, "error_code", require_token(self.error_code, "error_code"))
        if self.outcome is ConnectorOutcome.RETRY and self.retry_after is None:
            raise ValueError("retry outcome requires retry_after")
        if self.outcome is ConnectorOutcome.SUCCESS and (self.retry_after is not None or self.error_code is not None):
            raise ValueError("success outcome cannot include retry/error state")
