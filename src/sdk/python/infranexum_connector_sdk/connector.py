"""Object-oriented contracts implemented by InfraNexum connector packages."""

from __future__ import annotations

from abc import ABC, abstractmethod
from typing import Mapping, Any

from .models import ConnectorContext, ConnectorRequest, ConnectorResult


class ConnectorError(RuntimeError):
    """Base exception for connector-controlled failures safe to classify by type."""


class ConnectorConfigurationError(ConnectorError):
    """Raised when a connector cannot start with its governed configuration."""


class ConnectorTransientError(ConnectorError):
    """Raised for a retryable external failure; runtime policy owns retry timing."""


class ConnectorPermanentError(ConnectorError):
    """Raised for a non-retryable provider or contract failure."""


class Connector(ABC):
    """Stable v1 connector contract.

    Implementations receive only the governed execution context and operation request.
    Secrets and unrestricted network/filesystem handles are intentionally absent from
    this interface; production runtime adapters inject declared capabilities through
    isolated boundaries.
    """

    @property
    @abstractmethod
    def manifest(self) -> Mapping[str, Any]:
        """Return the connector manifest as a read-only mapping."""

    @abstractmethod
    def execute(self, context: ConnectorContext, request: ConnectorRequest) -> ConnectorResult:
        """Execute one idempotent operation and return a normalized result."""
