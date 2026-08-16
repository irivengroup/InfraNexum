"""Strict parser and certification checks for connector manifests."""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date
from hashlib import sha256
from importlib.resources import files
from ipaddress import ip_address
import json
import re
from types import MappingProxyType
from typing import Any, Mapping

from .models import ConnectorMode, compare_semver, require_identifier, require_semver, require_text, require_token
from .version import MANIFEST_CONTRACT_VERSION, MANIFEST_SCHEMA, SDK_VERSION

_HOST = re.compile(r"^(?=.{1,253}$)(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\.)*[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?$")
_ALLOWED_LEVELS = frozenset({"community", "validated", "certified", "trusted"})
_ALLOWED_DIRECTIONS = frozenset({"inbound", "outbound", "bidirectional"})
_ALLOWED_AUTHORITY = frozenset({"external", "infranexum", "manual"})
_ALLOWED_CONFLICT = frozenset({"reject", "manual", "prefer-authority"})
_ALLOWED_DELETION = frozenset({"ignore", "tombstone", "manual"})
_ALLOWED_CLASSIFICATIONS = frozenset({"public", "internal", "confidential", "restricted"})
MAX_MANIFEST_BYTES = 1_048_576

_ALLOWED_KEYS = frozenset({
    "schema", "id", "name", "version", "sdk", "certification", "provider", "modes",
    "capabilities", "permissions", "secrets", "egress", "authority", "delivery", "webhook",
    "data", "contracts", "limits", "support",
})


@dataclass(frozen=True, slots=True)
class ManifestReport:
    """Deterministic certification report for one connector manifest."""

    valid: bool
    digest_sha256: str
    errors: tuple[str, ...]
    warnings: tuple[str, ...]
    connector_id: str | None
    connector_version: str | None

    def as_dict(self) -> dict[str, Any]:
        return {
            "schema": "infranexum.connector-certification-report/v1",
            "valid": self.valid,
            "digestSha256": self.digest_sha256,
            "connectorId": self.connector_id,
            "connectorVersion": self.connector_version,
            "errors": list(self.errors),
            "warnings": list(self.warnings),
        }


class ConnectorManifest:
    """Validated immutable connector manifest backed by canonical JSON."""

    def __init__(self, document: Mapping[str, Any]) -> None:
        report = validate_manifest(document)
        if not report.valid:
            raise ValueError("invalid connector manifest: " + "; ".join(report.errors))
        canonical = canonical_json(document)
        self._data = _freeze_json(json.loads(canonical))
        self._digest = report.digest_sha256

    @property
    def data(self) -> Mapping[str, Any]:
        return self._data

    @property
    def connector_id(self) -> str:
        return str(self._data["id"])

    @property
    def version(self) -> str:
        return str(self._data["version"])

    @property
    def digest_sha256(self) -> str:
        return self._digest

    @classmethod
    def from_json(cls, value: str | bytes) -> "ConnectorManifest":
        if not isinstance(value, (str, bytes)):
            raise TypeError("connector manifest JSON must be str or bytes")
        encoded_size = len(value) if isinstance(value, bytes) else len(value.encode("utf-8"))
        if encoded_size > MAX_MANIFEST_BYTES:
            raise ValueError("connector manifest exceeds 1048576 bytes")
        try:
            document = json.loads(value)
        except (json.JSONDecodeError, UnicodeDecodeError) as exc:
            raise ValueError("connector manifest is not valid JSON") from exc
        if not isinstance(document, dict):
            raise ValueError("connector manifest root must be an object")
        return cls(document)


def manifest_schema() -> Mapping[str, Any]:
    """Load the packaged connector-manifest v1 JSON Schema immutably."""
    resource = files("infranexum_connector_sdk").joinpath("schemas/connector-manifest.schema.json")
    document = json.loads(resource.read_text(encoding="utf-8"))
    if not isinstance(document, dict):
        raise RuntimeError("packaged connector manifest schema root must be an object")
    return _freeze_json(document)


def canonical_json(document: Mapping[str, Any]) -> str:
    """Render stable UTF-8 JSON used by certification digests.

    Validated manifests are exposed as deeply immutable mappings/tuples.  The
    canonical encoder therefore normalizes those read-only containers back to
    plain JSON containers without weakening the public immutability contract.
    """
    return json.dumps(_json_value(document), ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False)


def _freeze_json(value: Any) -> Any:
    """Return a deeply immutable representation of a decoded JSON value."""
    if isinstance(value, dict):
        return MappingProxyType({key: _freeze_json(item) for key, item in value.items()})
    if isinstance(value, list):
        return tuple(_freeze_json(item) for item in value)
    return value


def _json_value(value: Any) -> Any:
    """Convert immutable JSON-compatible containers to encoder-safe values."""
    if isinstance(value, Mapping):
        return {str(key): _json_value(item) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [_json_value(item) for item in value]
    return value


def validate_manifest(document: Mapping[str, Any]) -> ManifestReport:
    """Validate the v1 connector manifest without loading connector code."""
    errors: list[str] = []
    warnings: list[str] = []
    connector_id: str | None = None
    connector_version: str | None = None
    if not isinstance(document, Mapping):
        return ManifestReport(False, sha256(b"null").hexdigest(), ("manifest root must be an object",), (), None, None)
    try:
        canonical = canonical_json(document)
    except (TypeError, ValueError) as exc:
        return ManifestReport(False, sha256(b"").hexdigest(), (f"manifest is not canonical JSON: {exc}",), (), None, None)
    digest = sha256(canonical.encode("utf-8")).hexdigest()
    unknown = sorted(set(document) - _ALLOWED_KEYS)
    if unknown:
        errors.append("unknown top-level fields: " + ", ".join(unknown))
    _expect(document.get("schema") == MANIFEST_SCHEMA, errors, f"schema must be {MANIFEST_SCHEMA}")
    try:
        connector_id = require_identifier(document.get("id"), "id")
    except (TypeError, ValueError) as exc:
        errors.append(str(exc))
    try:
        require_text(document.get("name"), "name", 160)
    except (TypeError, ValueError) as exc:
        errors.append(str(exc))
    try:
        connector_version = require_semver(document.get("version"), "version")
    except (TypeError, ValueError) as exc:
        errors.append(str(exc))
    _validate_sdk(document.get("sdk"), errors)
    level = _validate_certification(document.get("certification"), errors)
    _validate_provider(document.get("provider"), errors)
    modes = _validate_modes(document.get("modes"), errors)
    _validate_tokens(document.get("capabilities"), "capabilities", errors)
    _validate_tokens(document.get("permissions"), "permissions", errors)
    _validate_secrets(document.get("secrets"), errors)
    _validate_egress(document.get("egress"), errors)
    direction = _validate_authority(document.get("authority"), errors)
    _validate_delivery(document.get("delivery"), errors)
    _validate_webhook(document.get("webhook"), modes, errors)
    _validate_data(document.get("data"), errors)
    _validate_contracts(document.get("contracts"), errors)
    _validate_limits(document.get("limits"), errors)
    _validate_support(document.get("support"), errors)
    if direction == "bidirectional" and not document.get("authority", {}).get("fields"):
        errors.append("bidirectional authority requires field-level authority mappings")
    isolation = document.get("certification", {}).get("requiresIsolation") if isinstance(document.get("certification"), Mapping) else None
    if level in {"community", "validated"} and isolation is not True:
        errors.append("community/validated connectors must declare requiresIsolation=true")
    if level == "community":
        warnings.append("community connector requires runtime isolation and is not production-certified")
    return ManifestReport(not errors, digest, tuple(sorted(set(errors))), tuple(sorted(set(warnings))), connector_id, connector_version)


def _validate_sdk(value: Any, errors: list[str]) -> None:
    if not isinstance(value, Mapping):
        errors.append("sdk must be an object")
        return
    if set(value) - {"contractVersion", "minimumVersion"}:
        errors.append("sdk contains unknown fields")
    if value.get("contractVersion") != MANIFEST_CONTRACT_VERSION:
        errors.append(f"sdk.contractVersion must be {MANIFEST_CONTRACT_VERSION}")
    try:
        minimum_version = require_semver(value.get("minimumVersion"), "sdk.minimumVersion")
        if compare_semver(minimum_version, SDK_VERSION) > 0:
            errors.append(f"sdk.minimumVersion {minimum_version} requires a newer SDK than {SDK_VERSION}")
    except (TypeError, ValueError) as exc:
        errors.append(str(exc))


def _validate_certification(value: Any, errors: list[str]) -> str | None:
    if not isinstance(value, Mapping):
        errors.append("certification must be an object")
        return None
    if set(value) - {"level", "requiresIsolation", "evidence"}:
        errors.append("certification contains unknown fields")
    level = value.get("level")
    if level not in _ALLOWED_LEVELS:
        errors.append("invalid certification.level")
        level = None
    if not isinstance(value.get("requiresIsolation"), bool):
        errors.append("certification.requiresIsolation must be boolean")
    evidence = value.get("evidence", [])
    if not isinstance(evidence, list) or len(evidence) > 32 or any(not isinstance(item, str) or not re.fullmatch(r"sha256:[0-9a-f]{64}", item) for item in evidence):
        errors.append("certification.evidence must contain at most 32 sha256:<digest> values")
    return level


def _validate_provider(value: Any, errors: list[str]) -> None:
    if not isinstance(value, Mapping):
        errors.append("provider must be an object")
        return
    if set(value) - {"product", "supportedVersions"}:
        errors.append("provider contains unknown fields")
    try:
        require_text(value.get("product"), "provider.product", 160)
    except (TypeError, ValueError) as exc:
        errors.append(str(exc))
    versions = value.get("supportedVersions")
    if not isinstance(versions, list) or not versions or len(versions) > 64:
        errors.append("provider.supportedVersions must contain 1..64 entries")
    else:
        for item in versions:
            try:
                text = require_text(item, "provider.supportedVersions entry", 128)
                if text == "*":
                    errors.append("provider.supportedVersions must not use wildcard compatibility")
            except (TypeError, ValueError) as exc:
                errors.append(str(exc))


def _validate_modes(value: Any, errors: list[str]) -> frozenset[str]:
    if not isinstance(value, list) or not value or len(value) > len(ConnectorMode):
        errors.append("modes must contain 1..6 unique connector modes")
        return frozenset()
    if len(set(value)) != len(value):
        errors.append("modes must be unique")
    allowed = {item.value for item in ConnectorMode}
    invalid = [str(item) for item in value if item not in allowed]
    if invalid:
        errors.append("invalid modes: " + ", ".join(sorted(invalid)))
    return frozenset(item for item in value if item in allowed)


def _validate_tokens(value: Any, field: str, errors: list[str]) -> None:
    if not isinstance(value, list) or len(value) > 256 or len(set(map(str, value))) != len(value):
        errors.append(f"{field} must be a unique list with at most 256 entries")
        return
    for item in value:
        try:
            require_token(item, f"{field} entry")
        except (TypeError, ValueError) as exc:
            errors.append(str(exc))


def _validate_secrets(value: Any, errors: list[str]) -> None:
    if not isinstance(value, list) or len(value) > 64:
        errors.append("secrets must be a list with at most 64 declarations")
        return
    names: set[str] = set()
    for item in value:
        if not isinstance(item, Mapping) or set(item) - {"name", "purpose", "required"}:
            errors.append("each secret declaration must contain only name, purpose and required")
            continue
        try:
            name = require_token(item.get("name"), "secret.name", 80)
            if name in names:
                errors.append("secret names must be unique")
            names.add(name)
            require_text(item.get("purpose"), "secret.purpose", 240)
        except (TypeError, ValueError) as exc:
            errors.append(str(exc))
        if not isinstance(item.get("required"), bool):
            errors.append("secret.required must be boolean")


def _validate_egress(value: Any, errors: list[str]) -> None:
    if not isinstance(value, list) or len(value) > 128:
        errors.append("egress must be a list with at most 128 destinations")
        return
    seen: set[tuple[str, str, int]] = set()
    for item in value:
        if not isinstance(item, Mapping) or set(item) - {"scheme", "host", "port"}:
            errors.append("each egress destination must contain only scheme, host and port")
            continue
        scheme, host, port = item.get("scheme"), item.get("host"), item.get("port")
        if scheme != "https":
            errors.append("egress.scheme must be https")
        if not isinstance(host, str) or "*" in host or not _HOST.fullmatch(host) or host.lower() == "localhost":
            errors.append("egress.host must be an exact DNS hostname without wildcards")
        else:
            try:
                ip_address(host)
                errors.append("egress.host must use a DNS hostname, not an IP literal")
            except ValueError:
                pass
        if not isinstance(port, int) or isinstance(port, bool) or port < 1 or port > 65535:
            errors.append("egress.port must be between 1 and 65535")
        if isinstance(host, str) and isinstance(port, int):
            key = (str(scheme), host.lower(), port)
            if key in seen:
                errors.append("egress destinations must be unique")
            seen.add(key)


def _validate_authority(value: Any, errors: list[str]) -> str | None:
    if not isinstance(value, Mapping):
        errors.append("authority must be an object")
        return None
    if set(value) - {"direction", "conflictStrategy", "deletionPolicy", "fields"}:
        errors.append("authority contains unknown fields")
    direction = value.get("direction")
    if direction not in _ALLOWED_DIRECTIONS:
        errors.append("invalid authority.direction")
        direction = None
    if value.get("conflictStrategy") not in _ALLOWED_CONFLICT:
        errors.append("invalid authority.conflictStrategy")
    if value.get("deletionPolicy") not in _ALLOWED_DELETION:
        errors.append("invalid authority.deletionPolicy")
    fields = value.get("fields")
    if not isinstance(fields, list) or len(fields) > 512:
        errors.append("authority.fields must be a list with at most 512 mappings")
    else:
        names: set[str] = set()
        for item in fields:
            if not isinstance(item, Mapping) or set(item) - {"field", "authority"}:
                errors.append("authority field mappings contain invalid fields")
                continue
            try:
                name = require_token(item.get("field"), "authority.field", 160)
                if name in names:
                    errors.append("authority.field mappings must be unique")
                names.add(name)
            except (TypeError, ValueError) as exc:
                errors.append(str(exc))
            if item.get("authority") not in _ALLOWED_AUTHORITY:
                errors.append("invalid field authority")
    return direction


def _validate_delivery(value: Any, errors: list[str]) -> None:
    if not isinstance(value, Mapping):
        errors.append("delivery must be an object")
        return
    if set(value) - {"idempotencyRequired", "checkpointing", "replay", "maximumAttempts", "initialBackoffSeconds", "maximumBackoffSeconds"}:
        errors.append("delivery contains unknown fields")
    if value.get("idempotencyRequired") is not True:
        errors.append("delivery.idempotencyRequired must be true")
    if not isinstance(value.get("checkpointing"), bool):
        errors.append("delivery.checkpointing must be boolean")
    if value.get("replay") != "controlled":
        errors.append("delivery.replay must be controlled")
    attempts = value.get("maximumAttempts")
    initial = value.get("initialBackoffSeconds")
    maximum = value.get("maximumBackoffSeconds")
    if not isinstance(attempts, int) or isinstance(attempts, bool) or not 1 <= attempts <= 20:
        errors.append("delivery.maximumAttempts must be between 1 and 20")
    if not isinstance(initial, int) or isinstance(initial, bool) or not 1 <= initial <= 3600:
        errors.append("delivery.initialBackoffSeconds must be between 1 and 3600")
    if not isinstance(maximum, int) or isinstance(maximum, bool) or not 1 <= maximum <= 86400:
        errors.append("delivery.maximumBackoffSeconds must be between 1 and 86400")
    if isinstance(initial, int) and isinstance(maximum, int) and maximum < initial:
        errors.append("delivery.maximumBackoffSeconds must be >= initialBackoffSeconds")


def _validate_webhook(value: Any, modes: frozenset[str], errors: list[str]) -> None:
    if not isinstance(value, Mapping):
        errors.append("webhook must be an object")
        return
    if set(value) - {"incoming", "outgoing", "signature", "maximumClockSkewSeconds"}:
        errors.append("webhook contains unknown fields")
    incoming, outgoing = value.get("incoming"), value.get("outgoing")
    if not isinstance(incoming, bool) or not isinstance(outgoing, bool):
        errors.append("webhook incoming/outgoing must be boolean")
    if value.get("signature") != "hmac-sha256":
        errors.append("webhook.signature must be hmac-sha256")
    skew = value.get("maximumClockSkewSeconds")
    if not isinstance(skew, int) or isinstance(skew, bool) or not 1 <= skew <= 3600:
        errors.append("webhook.maximumClockSkewSeconds must be between 1 and 3600")
    if "webhook" in modes and not (incoming is True or outgoing is True):
        errors.append("webhook mode requires incoming or outgoing webhook support")
    if "webhook" not in modes and (incoming is True or outgoing is True):
        errors.append("webhook support requires webhook mode")


def _validate_data(value: Any, errors: list[str]) -> None:
    if not isinstance(value, Mapping) or set(value) - {"fields"}:
        errors.append("data must contain only fields")
        return
    fields = value.get("fields")
    if not isinstance(fields, list) or len(fields) > 1024:
        errors.append("data.fields must be a list with at most 1024 entries")
        return
    names: set[str] = set()
    for item in fields:
        if not isinstance(item, Mapping) or set(item) - {"name", "purpose", "classification"}:
            errors.append("data field declaration contains invalid fields")
            continue
        try:
            name = require_token(item.get("name"), "data field name", 160)
            if name in names:
                errors.append("data field names must be unique")
            names.add(name)
            require_text(item.get("purpose"), "data field purpose", 240)
        except (TypeError, ValueError) as exc:
            errors.append(str(exc))
        if item.get("classification") not in _ALLOWED_CLASSIFICATIONS:
            errors.append("invalid data field classification")


def _validate_contracts(value: Any, errors: list[str]) -> None:
    if not isinstance(value, list) or not value or len(value) > 128:
        errors.append("contracts must contain 1..128 entries")
        return
    seen: set[str] = set()
    for item in value:
        if not isinstance(item, Mapping) or set(item) - {"name", "version"}:
            errors.append("contract declaration contains invalid fields")
            continue
        try:
            name = require_token(item.get("name"), "contract.name", 160)
            if name in seen:
                errors.append("contract names must be unique")
            seen.add(name)
            require_semver(item.get("version"), "contract.version")
        except (TypeError, ValueError) as exc:
            errors.append(str(exc))


def _validate_limits(value: Any, errors: list[str]) -> None:
    if not isinstance(value, Mapping) or set(value) - {"timeoutSeconds", "maximumConcurrency", "maximumPayloadBytes"}:
        errors.append("limits contains invalid fields")
        return
    timeout = value.get("timeoutSeconds")
    concurrency = value.get("maximumConcurrency")
    payload = value.get("maximumPayloadBytes")
    if not isinstance(timeout, int) or isinstance(timeout, bool) or not 1 <= timeout <= 3600:
        errors.append("limits.timeoutSeconds must be between 1 and 3600")
    if not isinstance(concurrency, int) or isinstance(concurrency, bool) or not 1 <= concurrency <= 1024:
        errors.append("limits.maximumConcurrency must be between 1 and 1024")
    if not isinstance(payload, int) or isinstance(payload, bool) or not 1 <= payload <= 1_048_576:
        errors.append("limits.maximumPayloadBytes must be between 1 and 1048576")


def _validate_support(value: Any, errors: list[str]) -> None:
    if not isinstance(value, Mapping) or set(value) - {"owner", "endOfSupport"}:
        errors.append("support contains invalid fields")
        return
    try:
        require_text(value.get("owner"), "support.owner", 160)
    except (TypeError, ValueError) as exc:
        errors.append(str(exc))
    eos = value.get("endOfSupport")
    if not isinstance(eos, str) or not re.fullmatch(r"[0-9]{4}-[0-9]{2}-[0-9]{2}", eos):
        errors.append("support.endOfSupport must be YYYY-MM-DD")
        return
    try:
        date.fromisoformat(eos)
    except ValueError:
        errors.append("support.endOfSupport must be a real calendar date")


def _expect(condition: bool, errors: list[str], message: str) -> None:
    if not condition:
        errors.append(message)
