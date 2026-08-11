from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True, order=True)
class AuditViolation:
    check_id: str
    path: str
    message: str


class AuditChecker:
    """Block drift in append-only audit, integrity-chain, export and persistence contracts."""

    MODULE = Path("src/components/core/audit")
    JAVA = MODULE / "main/io/infranexum/core/audit"
    JDBC = Path("src/components/adapters/jdbc")
    JDBC_JAVA = JDBC / "main/io/infranexum/adapters/persistence/jdbc"
    MIGRATION = Path("src/distribution/migrations/0005-core-audit")
    WORKFLOW = Path(".github/workflows/foundation.yml")

    def __init__(self, root: Path) -> None:
        self.root = root.resolve()
        self.violations: list[AuditViolation] = []

    def run(self) -> tuple[AuditViolation, ...]:
        self._required_files()
        self._contract_pack()
        self._java_contracts()
        self._persistence_contracts()
        self._migration_contracts()
        self._wiring()
        return tuple(sorted(set(self.violations)))

    def _required_files(self) -> None:
        required = (
            self.MODULE / "MANIFEST.json",
            self.MODULE / "pom.xml",
            self.MODULE / "audit-contract-pack.json",
            self.JAVA / "AuditEntry.java",
            self.JAVA / "AuditJournal.java",
            self.JAVA / "AuditCanonicalizer.java",
            self.JAVA / "AuditExportService.java",
            self.JAVA / "AuditExportVerifier.java",
            self.JAVA / "AuditPurgeTombstone.java",
            self.JDBC_JAVA / "JdbcAuditJournal.java",
            self.MIGRATION / "migration.yaml",
            self.MIGRATION / "postgresql.sql",
            self.MIGRATION / "oracle.sql",
            self.MIGRATION / "verify.sql.yaml",
            self.MIGRATION / "rollback/postgresql.sql",
            self.MIGRATION / "rollback/oracle.sql",
        )
        for path in required:
            if not (self.root / path).is_file():
                self._add("CHECK-AUD-FILES-001", self.root / path, "required audit artifact is missing")

    def _contract_pack(self) -> None:
        path = self.root / self.MODULE / "audit-contract-pack.json"
        payload = self._json(path, "CHECK-AUD-PACK-001")
        if payload is None:
            return
        expected = {
            "schema": "infranexum.audit-contract-pack/v1",
            "append_only": True,
            "chain_digest": "SHA-256",
            "export_signature": "Ed25519",
        }
        for key, value in expected.items():
            if payload.get(key) != value:
                self._add("CHECK-AUD-PACK-002", path, f"invalid audit contract field {key}")
        required_fields = payload.get("entry_fields")
        required = {"actorId", "action", "targetType", "targetId", "authorizationDecision",
                    "timestamp", "correlationId", "result", "origin"}
        if not isinstance(required_fields, list) or not required.issubset(set(required_fields)):
            self._add("CHECK-AUD-PACK-003", path, "audit entry contract omits mandatory fields")

    def _java_contracts(self) -> None:
        entry = self._text(self.root / self.JAVA / "AuditEntry.java", "CHECK-AUD-JAVA-001")
        canonical = self._text(self.root / self.JAVA / "AuditCanonicalizer.java", "CHECK-AUD-JAVA-002")
        export = self._text(self.root / self.JAVA / "AuditExportService.java", "CHECK-AUD-JAVA-003")
        verifier = self._text(self.root / self.JAVA / "AuditExportVerifier.java", "CHECK-AUD-JAVA-004")
        purge = self._text(self.root / self.JAVA / "AuditPurgeTombstone.java", "CHECK-AUD-JAVA-005")
        requirements = (
            (entry, self.JAVA / "AuditEntry.java", ("SENSITIVE_KEY", "MAX_METADATA_BYTES", "authorizationDecision", "correlationId")),
            (canonical, self.JAVA / "AuditCanonicalizer.java", ('MessageDigest.getInstance("SHA-256")', "GENESIS_HASH", "canonicalEntry")),
            (export, self.JAVA / "AuditExportService.java", ('Signature.getInstance("Ed25519")', "ZipEntry.STORED", "setTime(0L)", "manifest.properties")),
            (verifier, self.JAVA / "AuditExportVerifier.java", ('Signature.getInstance("Ed25519")', "MessageDigest.isEqual")),
            (purge, self.JAVA / "AuditPurgeTombstone.java", ("two distinct approvers", "proofSha256")),
        )
        for text, path, tokens in requirements:
            if text is None:
                continue
            for token in tokens:
                if token not in text:
                    self._add("CHECK-AUD-JAVA-006", self.root / path, f"missing audit invariant: {token}")

    def _persistence_contracts(self) -> None:
        journal_path = self.root / self.JDBC_JAVA / "JdbcAuditJournal.java"
        journal = self._text(journal_path, "CHECK-AUD-JDBC-001")
        if journal is not None:
            for token in ("TRANSACTION_READ_COMMITTED", "FOR UPDATE", "last_sequence = ?", "immutable_flag", "AuditCanonicalizer.hash"):
                if token not in journal:
                    self._add("CHECK-AUD-JDBC-002", journal_path, f"missing JDBC audit invariant: {token}")
            upper = journal.upper()
            if "DELETE FROM" in upper or "UPDATE INFRANEXUM_CORE.AUDIT_ENTRY" in upper or "UPDATE INFRANEXUM_CORE_AUDIT_ENTRY" in upper:
                self._add("CHECK-AUD-JDBC-003", journal_path, "audit journal must never mutate or delete persisted entries")

    def _migration_contracts(self) -> None:
        pg_path = self.root / self.MIGRATION / "postgresql.sql"
        ora_path = self.root / self.MIGRATION / "oracle.sql"
        for path, rejection in (
            (pg_path, "RAISE EXCEPTION 'InfraNexum Core Audit is append-only'"),
            (ora_path, "RAISE_APPLICATION_ERROR(-20005, 'InfraNexum Core Audit is append-only')"),
        ):
            text = self._text(path, "CHECK-AUD-MIG-001")
            if text is None:
                continue
            lower = text.lower()
            for token in ("audit_chain_head", "audit_entry", "audit_purge_tombstone", "previous_hash", "entry_hash", "update or delete"):
                if token not in lower:
                    self._add("CHECK-AUD-MIG-002", path, f"missing append-only migration invariant: {token}")
            if rejection not in text:
                self._add("CHECK-AUD-MIG-003", path, "immutable trigger must reject mutation with the reserved audit error")
        for path in (self.root / self.MIGRATION / "rollback/postgresql.sql", self.root / self.MIGRATION / "rollback/oracle.sql"):
            text = self._text(path, "CHECK-AUD-MIG-004")
            if text is not None and "audit" in text.lower():
                lower = text.lower()
                if "select count(" not in lower and "exists (" not in lower:
                    self._add("CHECK-AUD-MIG-005", path, "audit rollback must refuse destruction when evidence exists")

    def _wiring(self) -> None:
        checks = (
            (Path("pom.xml"), "<module>src/components/core/audit</module>"),
            (self.JDBC / "pom.xml", "infranexum-core-audit"),
            (self.JDBC / "MANIFEST.json", "components.core.audit"),
            (Path("validation/architecture/policy.json"), "components/core/audit"),
            (Path("Makefile"), "audit-test"),
            (Path("Makefile"), "java-audit-smoke"),
            (self.WORKFLOW, "audit-test"),
            (self.WORKFLOW, "make postgresql-test-schema"),
        )
        for relative, token in checks:
            text = self._text(self.root / relative, "CHECK-AUD-WIRE-001")
            if text is not None and token not in text:
                self._add("CHECK-AUD-WIRE-002", self.root / relative, f"audit wiring is missing: {token}")

    def _json(self, path: Path, check_id: str) -> dict | None:
        try:
            payload = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError) as error:
            self._add(check_id, path, f"invalid JSON: {error}")
            return None
        if not isinstance(payload, dict):
            self._add(check_id, path, "JSON root must be an object")
            return None
        return payload

    def _text(self, path: Path, check_id: str) -> str | None:
        try:
            return path.read_text(encoding="utf-8")
        except OSError as error:
            self._add(check_id, path, f"cannot read file: {error}")
            return None

    def _add(self, check_id: str, path: Path, message: str) -> None:
        try:
            rendered = path.resolve().relative_to(self.root).as_posix()
        except ValueError:
            rendered = path.resolve().as_posix()
        self.violations.append(AuditViolation(check_id, rendered, message))
