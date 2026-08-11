from __future__ import annotations

import hashlib
import json
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Any

EXPECTED_FIELDS = (
    "eventId",
    "eventType",
    "schemaVersion",
    "occurredAt",
    "source",
    "correlationId",
    "causationId",
    "payload",
)
EXPECTED_OUTBOX_COLUMNS = {
    "event_id",
    "event_type",
    "schema_version",
    "occurred_at",
    "source",
    "correlation_id",
    "causation_id",
    "payload_json",
}


@dataclass(frozen=True, order=True)
class EventingViolation:
    check_id: str
    path: str
    message: str


class EventingChecker:
    """Blocks drift between the event contract, Java envelope and migration model."""

    def __init__(self, root: Path) -> None:
        self.root = root.resolve()
        self.violations: list[EventingViolation] = []

    def run(self) -> tuple[EventingViolation, ...]:
        pack_path = self.root / "src/components/core/events/event-contract-pack.json"
        schema_path = self.root / "src/components/core/events/event-envelope.schema.json"
        java_path = self.root / "src/components/core/events/main/io/infranexum/core/events/EventEnvelope.java"
        model_path = self.root / "src/distribution/migrations/0002-core-transactional-events/logical-model.json"
        postgresql_path = self.root / "src/distribution/migrations/0002-core-transactional-events/postgresql.sql"
        oracle_path = self.root / "src/distribution/migrations/0002-core-transactional-events/oracle.sql"

        pack = self._load_json(pack_path, "CHECK-EVENT-PACK-001")
        schema = self._load_json(schema_path, "CHECK-EVENT-SCHEMA-001")
        model = self._load_json(model_path, "CHECK-EVENT-MODEL-001")
        self._check_pack(pack_path, pack, schema_path)
        self._check_schema(schema_path, schema)
        self._check_java(java_path)
        self._check_model(model_path, model)
        self._check_sql(postgresql_path)
        self._check_sql(oracle_path)
        return tuple(sorted(set(self.violations)))

    def _check_pack(self, path: Path, payload: Any, schema_path: Path) -> None:
        if not isinstance(payload, dict):
            return
        if payload.get("schema") != "infranexum.event-contract-pack/v1":
            self._add("CHECK-EVENT-PACK-002", path, "unexpected contract-pack schema")
        if tuple(payload.get("envelope_fields", ())) != EXPECTED_FIELDS:
            self._add("CHECK-EVENT-PACK-003", path, "envelope_fields must match the normative order")
        if payload.get("delivery_guarantee") != "at-least-once":
            self._add("CHECK-EVENT-PACK-004", path, "delivery guarantee must remain at-least-once")
        if payload.get("scope_rule") != "each-bounded-context-owns-its-unit-of-work-and-local-outbox-tables":
            self._add("CHECK-EVENT-PACK-005", path, "bounded-context-local outbox ownership is required")
        declared = payload.get("envelope_schema_sha256")
        if not schema_path.is_file() or not isinstance(declared, str):
            self._add("CHECK-EVENT-PACK-006", path, "envelope schema and checksum are required")
        elif hashlib.sha256(schema_path.read_bytes()).hexdigest() != declared:
            self._add("CHECK-EVENT-PACK-007", path, "envelope schema checksum mismatch")

    def _check_schema(self, path: Path, payload: Any) -> None:
        if not isinstance(payload, dict):
            return
        properties = payload.get("properties")
        if payload.get("additionalProperties") is not False:
            self._add("CHECK-EVENT-SCHEMA-002", path, "additionalProperties must be false")
        if tuple(payload.get("required", ())) != EXPECTED_FIELDS:
            self._add("CHECK-EVENT-SCHEMA-003", path, "required fields must match the normative order")
        if not isinstance(properties, dict) or tuple(properties) != EXPECTED_FIELDS:
            self._add("CHECK-EVENT-SCHEMA-004", path, "schema properties must match the normative order")
        elif properties.get("payload", {}).get("oneOf") != [{"type": "object"}, {"type": "array"}]:
            self._add("CHECK-EVENT-SCHEMA-005", path, "payload must be an object or array")

    def _check_java(self, path: Path) -> None:
        try:
            text = path.read_text(encoding="utf-8")
        except OSError as error:
            self._add("CHECK-EVENT-JAVA-001", path, f"cannot read Java envelope: {error}")
            return
        match = re.search(r"public record EventEnvelope\((.*?)\) \{", text, re.DOTALL)
        if not match:
            self._add("CHECK-EVENT-JAVA-002", path, "EventEnvelope record declaration not found")
            return
        fields = []
        for declaration in match.group(1).split(","):
            tokens = declaration.strip().split()
            if tokens:
                fields.append(tokens[-1])
        if tuple(fields) != EXPECTED_FIELDS:
            self._add("CHECK-EVENT-JAVA-003", path, f"Java envelope fields drifted: {fields}")

    def _check_model(self, path: Path, payload: Any) -> None:
        if not isinstance(payload, dict):
            return
        objects = payload.get("objects")
        if not isinstance(objects, list):
            self._add("CHECK-EVENT-MODEL-002", path, "objects must be an array")
            return
        by_name = {item.get("logical_name"): item for item in objects if isinstance(item, dict)}
        outbox = by_name.get("core.outbox_event")
        inbox = by_name.get("core.inbox_receipt")
        if not isinstance(outbox, dict):
            self._add("CHECK-EVENT-MODEL-003", path, "core.outbox_event is required")
        else:
            columns = {item.get("name") for item in outbox.get("columns", []) if isinstance(item, dict)}
            if not EXPECTED_OUTBOX_COLUMNS <= columns:
                self._add("CHECK-EVENT-MODEL-004", path, "outbox is missing normative envelope columns")
        if not isinstance(inbox, dict) or inbox.get("primary_key") != ["consumer_name", "event_id"]:
            self._add("CHECK-EVENT-MODEL-005", path, "inbox deduplication primary key must be consumer_name,event_id")

    def _check_sql(self, path: Path) -> None:
        try:
            text = path.read_text(encoding="utf-8").lower()
        except OSError as error:
            self._add("CHECK-EVENT-SQL-001", path, f"cannot read SQL migration: {error}")
            return
        required = {
            "event_id",
            "event_type",
            "schema_version",
            "occurred_at",
            "event_source",
            "correlation_id",
            "causation_id",
            "payload_json",
        }
        missing = sorted(token for token in required if token not in text)
        if missing:
            self._add("CHECK-EVENT-SQL-002", path, f"SQL is missing envelope columns: {missing}")
        forbidden = sorted(token for token in ("aggregate_id", "metadata_json") if token in text)
        if forbidden:
            self._add("CHECK-EVENT-SQL-003", path, f"SQL contains non-normative envelope columns: {forbidden}")

    def _load_json(self, path: Path, check_id: str) -> Any:
        try:
            return json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError) as error:
            self._add(check_id, path, f"invalid JSON: {error}")
            return None

    def _add(self, check_id: str, path: Path, message: str) -> None:
        try:
            rendered = path.resolve().relative_to(self.root).as_posix()
        except ValueError:
            rendered = path.resolve().as_posix()
        self.violations.append(EventingViolation(check_id, rendered, message))
