from __future__ import annotations

import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
SERVER = ROOT / "src/applications/server/main/io/infranexum/server"
OBSERVABILITY = SERVER / "observability"
APPLICATION = ROOT / "src/applications/server/resources/application.yaml"
COMPOSE = ROOT / "docker/compose.yaml"


class ObservabilityArchitectureTest(unittest.TestCase):
    """Keep the first PGM-12-E01 HTTP observability contract deterministic and secret-safe."""

    def test_every_http_request_has_canonical_uuidv7_correlation_context(self) -> None:
        filter_source = (OBSERVABILITY / "CorrelationIdFilter.java").read_text(encoding="utf-8")
        context = (OBSERVABILITY / "CorrelationContext.java").read_text(encoding="utf-8")
        self.assertIn('HEADER_NAME = "X-Correlation-ID"', context)
        self.assertIn('MDC_KEY = "correlation_id"', context)
        self.assertIn("DomainIdentifier.parse(normalized)", filter_source)
        self.assertIn("parsed.toString().equals(normalized)", filter_source)
        self.assertIn("response.setHeader(CorrelationContext.HEADER_NAME", filter_source)
        self.assertIn("MDC.put(CorrelationContext.MDC_KEY", filter_source)
        self.assertIn("MDC.remove(CorrelationContext.MDC_KEY)", filter_source)

    def test_invalid_correlation_is_fail_closed_and_never_reflected(self) -> None:
        filter_source = (OBSERVABILITY / "CorrelationIdFilter.java").read_text(encoding="utf-8")
        self.assertIn("SC_BAD_REQUEST", filter_source)
        self.assertIn("INFRANEXUM_INVALID_CORRELATION_ID", filter_source)
        self.assertIn("infranexum.http.correlation.rejected", filter_source)
        self.assertNotIn("+ supplied", filter_source)
        self.assertNotIn("logger.warn", filter_source)

    def test_structured_logs_are_default_and_compose_can_override_them(self) -> None:
        application = APPLICATION.read_text(encoding="utf-8")
        compose = COMPOSE.read_text(encoding="utf-8")
        self.assertIn("console: ${INFRANEXUM_LOG_FORMAT:ecs}", application)
        self.assertIn("environment: ${INFRANEXUM_ENVIRONMENT:local}", application)
        self.assertIn("INFRANEXUM_LOG_FORMAT", compose)
        self.assertIn("INFRANEXUM_ENVIRONMENT", compose)

    def test_entitlement_problem_uses_validated_context_not_raw_header(self) -> None:
        handler = (SERVER / "platform/entitlements/EntitlementExceptionHandler.java").read_text(encoding="utf-8")
        self.assertIn("CorrelationContext.traceId(request)", handler)
        self.assertNotIn("getHeader(\"X-Correlation-ID\")", handler)

    def test_current_openapi_surface_documents_correlation_contract(self) -> None:
        openapi = (ROOT / "src/applications/server/resources/openapi/platform-entitlements.yaml").read_text(
            encoding="utf-8"
        )
        self.assertIn("name: X-Correlation-ID", openapi)
        self.assertIn("canonical lowercase RFC 9562 UUIDv7", openapi)
        self.assertIn("'400':", openapi)
        self.assertIn("7[0-9a-f]{3}-[89ab]", openapi)


    def test_worker_correlation_crosses_only_durable_validated_boundary(self) -> None:
        bridge = (OBSERVABILITY / "WorkerCorrelationBridge.java").read_text(encoding="utf-8")
        scheduler = (ROOT / "src/components/core/workers/main/io/infranexum/core/workers/TaskScheduler.java").read_text(encoding="utf-8")
        record = (ROOT / "src/components/core/workers/main/io/infranexum/core/workers/TaskRecord.java").read_text(encoding="utf-8")
        jdbc = (ROOT / "src/components/adapters/jdbc/main/io/infranexum/adapters/persistence/jdbc/JdbcTaskStore.java").read_text(encoding="utf-8")
        migration = (ROOT / "src/distribution/migrations/0009-core-worker-correlation/postgresql.sql").read_text(encoding="utf-8")
        self.assertIn("implements TaskCorrelationProvider, TaskExecutionScopeFactory", bridge)
        self.assertIn("MDC.get(CorrelationContext.MDC_KEY)", bridge)
        self.assertIn("context.correlationId()", bridge)
        self.assertIn("correlationProvider.current()", scheduler)
        self.assertIn("DomainIdentifier correlationId", record)
        self.assertIn("correlation_id", jdbc)
        self.assertIn("ck_inx_worker_correlation_v7", migration)
        self.assertNotIn("Authorization", bridge)
        self.assertNotIn("SecurityContext", bridge)

    def test_worker_correlation_scope_restores_mdc_instead_of_leaking_between_tasks(self) -> None:
        bridge = (OBSERVABILITY / "WorkerCorrelationBridge.java").read_text(encoding="utf-8")
        worker = (ROOT / "src/components/core/workers/main/io/infranexum/core/workers/TaskWorker.java").read_text(encoding="utf-8")
        self.assertIn("return () -> restore(previous)", bridge)
        self.assertIn("try (scope)", worker)
        self.assertIn("MDC.remove(CorrelationContext.MDC_KEY)", bridge)


if __name__ == "__main__":
    unittest.main()
