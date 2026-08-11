from __future__ import annotations

import json
import re
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True, order=True)
class PersistenceViolation:
    check_id: str
    path: str
    message: str


class PersistenceChecker:
    """Block drift in JDBC transaction, claim, inbox, and migration contracts."""

    def __init__(self, root: Path) -> None:
        self.root = root.resolve()
        self.violations: list[PersistenceViolation] = []

    def run(self) -> tuple[PersistenceViolation, ...]:
        store_path = self.root / (
            "src/components/adapters/jdbc/main/"
            "io/infranexum/adapters/persistence/jdbc/JdbcTransactionalEventStore.java"
        )
        dialect_path = store_path.with_name("JdbcDatabaseDialect.java")
        transaction_path = self.root / (
            "src/components/core/events/main/"
            "io/infranexum/core/events/EventTransaction.java"
        )
        postgres_path = self.root / "src/distribution/migrations/0003-core-inbox-reservation/postgresql.sql"
        oracle_path = self.root / "src/distribution/migrations/0003-core-inbox-reservation/oracle.sql"
        rollback_pg = postgres_path.parent / "rollback/postgresql.sql"
        rollback_oracle = oracle_path.parent / "rollback/oracle.sql"
        server_config_path = self.root / (
            "src/applications/server/main/io/infranexum/server/persistence/"
            "EventPersistenceConfiguration.java"
        )
        server_manifest_path = self.root / "src/applications/server/MANIFEST.json"
        server_pom_path = self.root / "src/applications/server/pom.xml"
        unavailable_data_source_path = self.root / (
            "src/applications/server/main/io/infranexum/server/persistence/"
            "UnavailableDataSource.java"
        )

        store = self._read(store_path, "CHECK-JDBC-STORE-001")
        dialect = self._read(dialect_path, "CHECK-JDBC-DIALECT-001")
        transaction = self._read(transaction_path, "CHECK-JDBC-UOW-001")
        postgres = self._read(postgres_path, "CHECK-JDBC-MIGRATION-001")
        oracle = self._read(oracle_path, "CHECK-JDBC-MIGRATION-001")
        rollback_postgres = self._read(rollback_pg, "CHECK-JDBC-ROLLBACK-001")
        rollback_ora = self._read(rollback_oracle, "CHECK-JDBC-ROLLBACK-001")
        server_config = self._read(server_config_path, "CHECK-JDBC-SERVER-001")
        server_manifest = self._read(server_manifest_path, "CHECK-JDBC-SERVER-001")
        server_pom = self._read(server_pom_path, "CHECK-JDBC-SERVER-001")
        unavailable_data_source = self._read(unavailable_data_source_path, "CHECK-JDBC-SERVER-001")

        self._check_store(store_path, store)
        self._check_dialect(dialect_path, dialect)
        self._check_transaction(transaction_path, transaction)
        self._check_migration(postgres_path, postgres)
        self._check_migration(oracle_path, oracle)
        self._check_rollback(rollback_pg, rollback_postgres)
        self._check_rollback(rollback_oracle, rollback_ora)
        self._check_server_integration(
            server_config_path, server_config, server_manifest_path, server_manifest,
            server_pom_path, server_pom, unavailable_data_source_path, unavailable_data_source)
        self._check_reactor()
        self._check_policy()
        self._check_gate_order()
        return tuple(sorted(set(self.violations)))

    def _check_store(self, path: Path, text: str | None) -> None:
        if text is None:
            return
        required = {
            "javax.sql.DataSource": "DataSource boundary is required",
            "implements TransactionalEventStore, JdbcConnectionAccess": "event store and connection access ports are required",
            "ThreadLocal<Connection>": "unit-of-work connection must be thread-confined",
            "connection.commit()": "explicit commit is required",
            "connection.rollback()": "explicit rollback is required",
            "FORBIDDEN_NESTED_MARKER": "nested unit-of-work guard marker is required",
        }
        normalized = text.replace(
            'throw new IllegalStateException("nested JDBC units of work are forbidden")',
            "FORBIDDEN_NESTED_MARKER",
        )
        for token, message in required.items():
            if token not in normalized:
                self._add("CHECK-JDBC-STORE-002", path, message)
        commit = text.find("connection.commit()")
        post_commit = text.find("return new TransactionOutcome<>(value, runPostCommitActions")
        if commit < 0 or post_commit < 0 or commit > post_commit:
            self._add("CHECK-JDBC-STORE-003", path, "post-commit actions must run after database commit")
        if re.search(r"import\s+(?:org\.postgresql|oracle\.jdbc)", text):
            self._add("CHECK-JDBC-STORE-004", path, "core JDBC adapter must not import vendor driver classes")

    def _check_dialect(self, path: Path, text: str | None) -> None:
        if text is None:
            return
        required = (
            "POSTGRESQL",
            "ORACLE",
            "FOR UPDATE SKIP LOCKED",
            "ON CONFLICT (consumer_name, event_id) DO NOTHING",
            "connection.setSavepoint()",
            "failure.getErrorCode() == 1",
            "java.sql.Types.OTHER",
        )
        for token in required:
            if token not in text:
                self._add("CHECK-JDBC-DIALECT-002", path, f"missing dialect invariant: {token}")
        if not re.search(r"LIMIT \?\s+FOR UPDATE SKIP LOCKED", text):
            self._add("CHECK-JDBC-DIALECT-003", path, "PostgreSQL LIMIT must precede FOR UPDATE")

    def _check_transaction(self, path: Path, text: str | None) -> None:
        if text is None:
            return
        if "InboxDecision beginInbox(InboxReservation reservation)" not in text:
            self._add("CHECK-JDBC-UOW-002", path, "inbox reservation metadata must be created before handler execution")
        if "void completeInbox(InboxKey key, Instant completedAt)" not in text:
            self._add("CHECK-JDBC-UOW-003", path, "inbox completion must close the accepted reservation")

    def _check_migration(self, path: Path, text: str | None) -> None:
        if text is None:
            return
        upper = text.upper()
        required_literals = {
            "PROCESSING": re.compile(r"'{1,2}PROCESSING'{1,2}"),
            "COMPLETED": re.compile(r"'{1,2}COMPLETED'{1,2}"),
        }
        for token, pattern in required_literals.items():
            if not pattern.search(upper):
                self._add("CHECK-JDBC-MIGRATION-002", path, f"missing inbox state token: {token}")
        for token in ("COMPLETED_AT", "STATUS"):
            if token not in upper:
                self._add("CHECK-JDBC-MIGRATION-002", path, f"missing inbox state token: {token}")
        if required_literals["PROCESSING"].search(upper) and "COMPLETED_AT IS NULL" not in upper:
            self._add("CHECK-JDBC-MIGRATION-003", path, "PROCESSING rows must require a null completion timestamp")

    def _check_rollback(self, path: Path, text: str | None) -> None:
        if text is None:
            return
        upper = text.upper()
        if "PROCESSING" not in upper or "CANNOT ROLL BACK MIGRATION 0003" not in upper:
            self._add("CHECK-JDBC-ROLLBACK-002", path, "rollback must refuse active inbox reservations")

    def _check_server_integration(
        self,
        config_path: Path,
        config: str | None,
        manifest_path: Path,
        manifest: str | None,
        pom_path: Path,
        pom: str | None,
        unavailable_path: Path,
        unavailable: str | None,
    ) -> None:
        if config is not None:
            required = (
                'havingValue = "MEMORY"',
                'havingValue = "POSTGRESQL"',
                'havingValue = "ORACLE"',
                'MEMORY persistence is restricted to STANDALONE region=local site=local',
                'DataSource dataSource',
            )
            for token in required:
                if token not in config:
                    self._add("CHECK-JDBC-SERVER-002", config_path, f"missing Server persistence invariant: {token}")
        if manifest is not None:
            for dependency in ("components.core.events", "components.adapters.persistence-jdbc"):
                if dependency not in manifest:
                    self._add("CHECK-JDBC-SERVER-003", manifest_path, f"Server manifest missing dependency {dependency}")
        if pom is not None:
            for artifact in ("infranexum-core-events", "infranexum-adapter-persistence-jdbc"):
                if artifact not in pom:
                    self._add("CHECK-JDBC-SERVER-004", pom_path, f"Server reactor dependency missing {artifact}")
            if "spring-boot-starter-jdbc" in pom:
                required_guard_tokens = (
                    "memoryDataSource()",
                    "UnavailableDataSource",
                    "JDBC access is unavailable because infranexum.persistence.mode=MEMORY",
                )
                if config is None or any(token not in config for token in required_guard_tokens):
                    self._add(
                        "CHECK-JDBC-SERVER-005", config_path,
                        "JDBC starter requires an explicit fail-closed MEMORY DataSource")
                if unavailable is None or "throw unavailable()" not in unavailable:
                    self._add(
                        "CHECK-JDBC-SERVER-005", unavailable_path,
                        "MEMORY DataSource must fail every accidental JDBC connection")


    def _check_gate_order(self) -> None:
        path = self.root / "Makefile"
        text = self._read(path, "CHECK-JDBC-GATE-001")
        if text is None:
            return
        match = re.search(r"(?m)^persistence-test:(?P<deps>[^\n]*)$", text)
        dependencies = match.group("deps").split() if match is not None else []
        if "persistence-check" not in dependencies or "source-integrity-check" not in dependencies:
            self._add(
                "CHECK-JDBC-GATE-002",
                path,
                "persistence-test must depend on source-integrity-check and persistence-check before fixture execution",
            )

    def _check_reactor(self) -> None:
        path = self.root / "pom.xml"
        text = self._read(path, "CHECK-JDBC-REACTOR-001")
        if text is not None and "src/components/adapters/jdbc" not in text:
            self._add("CHECK-JDBC-REACTOR-002", path, "JDBC adapter module is absent from the Maven reactor")

    def _check_policy(self) -> None:
        path = self.root / "validation/architecture/policy.json"
        try:
            payload = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError) as error:
            self._add("CHECK-JDBC-POLICY-001", path, f"invalid architecture policy: {error}")
            return
        expected = "components/adapters/jdbc"
        if expected not in payload.get("required_manifest_paths", []):
            self._add("CHECK-JDBC-POLICY-002", path, "JDBC adapter manifest is not architecture-enforced")

    def _read(self, path: Path, check_id: str) -> str | None:
        try:
            return path.read_text(encoding="utf-8")
        except OSError as error:
            self._add(check_id, path, f"cannot read required file: {error}")
            return None

    def _add(self, check_id: str, path: Path, message: str) -> None:
        try:
            rendered = path.resolve().relative_to(self.root).as_posix()
        except ValueError:
            rendered = path.resolve().as_posix()
        self.violations.append(PersistenceViolation(check_id, rendered, message))
