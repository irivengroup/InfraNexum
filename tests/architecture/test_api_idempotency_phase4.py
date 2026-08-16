from __future__ import annotations

import json
import re
import unittest
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]


class ApiIdempotencyPhase4ArchitectureTest(unittest.TestCase):
    def test_openapi_idempotency_debt_is_zero_and_exemptions_are_explicit(self) -> None:
        baseline = json.loads((ROOT / "validation/api_contracts/baseline.json").read_text(encoding="utf-8"))
        self.assertEqual([], baseline["debt"]["idempotency"])
        operations: dict[str, dict] = {}
        for path in (ROOT / "src/applications/server/resources/openapi").glob("*.yaml"):
            if path.name == "catalogue.yaml":
                continue
            doc = yaml.safe_load(path.read_text(encoding="utf-8"))
            for item in (doc.get("paths") or {}).values():
                if not isinstance(item, dict):
                    continue
                for operation in item.values():
                    if isinstance(operation, dict) and operation.get("operationId"):
                        operations[operation["operationId"]] = operation
        for operation_id in ("createIamUser", "updateIamRole", "createRsotSchema", "publishRsotSchemaProfile"):
            self.assertEqual("required", operations[operation_id]["x-infranexum-idempotency"])
        self.assertEqual("repeatable", operations["decideIamAuthorization"]["x-infranexum-idempotency"])
        self.assertEqual("repeatable", operations["validateIamPermission"]["x-infranexum-idempotency"])
        self.assertEqual("security-exempt", operations["createLocalSession"]["x-infranexum-idempotency"])
        self.assertEqual("security-exempt", operations["changeLocalPassword"]["x-infranexum-idempotency"])

    def test_runtime_filter_is_after_authz_and_covers_all_required_phase4_operations(self) -> None:
        filter_source = (ROOT / "src/applications/server/main/io/infranexum/server/http/idempotency/ApiIdempotencyFilter.java").read_text(encoding="utf-8")
        policy = (ROOT / "src/applications/server/main/io/infranexum/server/http/idempotency/ApiIdempotencyPolicy.java").read_text(encoding="utf-8")
        self.assertIn("Ordered.HIGHEST_PRECEDENCE + 50", filter_source)
        self.assertIn("AuthenticatedActorContext.ACCOUNT_ATTRIBUTE", filter_source)
        self.assertNotIn("LocalAuthenticationFilter", filter_source)
        self.assertIn("INFRANEXUM_IDEMPOTENCY_CONFLICT", filter_source)
        self.assertIn("INFRANEXUM_IDEMPOTENCY_INDETERMINATE", filter_source)
        required = set(re.findall(r'rule\("([A-Za-z0-9]+)"', policy))
        self.assertEqual(34, len(required))
        self.assertIn("createIamUser", required)
        self.assertIn("deprecateRsotSchemaProfile", required)

    def test_ledger_is_durable_cross_database_and_fail_closed(self) -> None:
        ledger = (ROOT / "src/components/adapters/jdbc/main/io/infranexum/adapters/persistence/jdbc/JdbcApiIdempotencyLedger.java").read_text(encoding="utf-8")
        contract = (ROOT / "src/components/core/contracts/main/io/infranexum/core/contracts/IdempotencyLedger.java").read_text(encoding="utf-8")
        pg = (ROOT / "src/distribution/migrations/0032-core-api-idempotency/postgresql.sql").read_text(encoding="utf-8")
        ora = (ROOT / "src/distribution/migrations/0032-core-api-idempotency/oracle.sql").read_text(encoding="utf-8")
        self.assertIn("INDETERMINATE", contract)
        self.assertIn("infranexum_core.api_idempotency", ledger)
        self.assertIn("INFRANEXUM_CORE_API_IDEMP", ledger)
        self.assertIn("PRIMARY KEY(scope_key, operation_name, idempotency_key)", pg)
        self.assertIn("PK_INX_API_IDEMP", ora)
        self.assertIn("IN_PROGRESS", pg)
        self.assertIn("INDETERMINATE", ora)

    def test_security_sensitive_local_auth_is_not_persisted_for_replay(self) -> None:
        policy = (ROOT / "src/applications/server/main/io/infranexum/server/http/idempotency/ApiIdempotencyPolicy.java").read_text(encoding="utf-8")
        self.assertNotIn("createLocalSession", policy)
        self.assertNotIn("changeLocalPassword", policy)
        self.assertNotIn("revokeCurrentLocalSession", policy)


if __name__ == "__main__":
    unittest.main()
