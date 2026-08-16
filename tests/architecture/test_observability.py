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
        self.assertIn("HttpStatus.BAD_REQUEST", filter_source)
        self.assertIn("INFRANEXUM_INVALID_CORRELATION_ID", filter_source)
        self.assertIn("problems.write(response, problem)", filter_source)
        self.assertIn("infranexum.http.correlation.rejected", filter_source)
        self.assertNotIn("+ supplied", filter_source)
        self.assertNotIn("logger.warn", filter_source)

    def test_structured_logs_are_fixed_to_ecs_and_redacted_before_json_serialization(self) -> None:
        application = APPLICATION.read_text(encoding="utf-8")
        compose = COMPOSE.read_text(encoding="utf-8")
        customizer = (OBSERVABILITY / "SensitiveDataStructuredLoggingCustomizer.java").read_text(encoding="utf-8")
        redactor = (OBSERVABILITY / "SensitiveDataRedactor.java").read_text(encoding="utf-8")
        self.assertIn("console: ecs", application)
        self.assertNotIn("INFRANEXUM_LOG_FORMAT", application)
        self.assertNotIn("INFRANEXUM_LOG_FORMAT", compose)
        self.assertIn("SensitiveDataStructuredLoggingCustomizer", application)
        self.assertIn("max-length: 8192", application)
        self.assertIn("applyingValueProcessor", customizer)
        self.assertIn("path.toUnescapedString()", customizer)
        self.assertIn('REDACTED = "[REDACTED]"', redactor)
        self.assertIn("PRIVATE_KEY", redactor)
        self.assertIn("AUTH_SCHEME", redactor)
        self.assertIn("environment: ${INFRANEXUM_ENVIRONMENT:local}", application)
        self.assertIn("INFRANEXUM_ENVIRONMENT", compose)

    def test_entitlement_problem_uses_validated_context_not_raw_header(self) -> None:
        handler = (SERVER / "platform/entitlements/EntitlementExceptionHandler.java").read_text(encoding="utf-8")
        support = (SERVER / "http/ApiProblemSupport.java").read_text(encoding="utf-8")
        self.assertIn("problems.response", handler)
        self.assertIn("CorrelationContext.traceId(request)", support)
        self.assertNotIn("getHeader(\"X-Correlation-ID\")", handler)
        self.assertNotIn("getHeader(\"X-Correlation-ID\")", support)
        self.assertIn('redactor.redact("problem", source)', support)

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
        self.assertIn("restore(previous)", bridge)
        self.assertIn("traceScope.close()", bridge)
        self.assertIn("createdSpan.end()", bridge)
        self.assertIn("try (scope)", worker)
        self.assertIn("MDC.remove(CorrelationContext.MDC_KEY)", bridge)

    def test_opentelemetry_is_w3c_only_with_export_disabled_by_default(self) -> None:
        application = APPLICATION.read_text(encoding="utf-8")
        server_pom = (ROOT / "src/applications/server/pom.xml").read_text(encoding="utf-8")
        compose = COMPOSE.read_text(encoding="utf-8")
        self.assertIn("spring-boot-starter-opentelemetry", server_pom)
        self.assertIn("type: W3C", application)
        self.assertIn("baggage:\n      enabled: false", application)
        self.assertIn("INFRANEXUM_OTEL_EXPORT_ENABLED:false", application)
        self.assertIn("INFRANEXUM_OTEL_METRICS_EXPORT_ENABLED:false", application)
        self.assertIn("map-environment-variables: false", application)
        self.assertIn("parent-based-trace-id-ratio", application)
        self.assertIn("max-attribute-value-length", application)
        self.assertIn("INFRANEXUM_OTEL_EXPORT_ENABLED", compose)
        self.assertIn("INFRANEXUM_OTEL_METRICS_EXPORT_ENABLED", compose)
        self.assertIn("INFRANEXUM_OTEL_SAMPLING_PROBABILITY", compose)

    def test_worker_execution_has_bounded_consumer_span_without_task_payload_tags(self) -> None:
        bridge = (OBSERVABILITY / "WorkerCorrelationBridge.java").read_text(encoding="utf-8")
        self.assertIn('WORKER_SPAN_NAME = "infranexum.worker.execute"', bridge)
        self.assertIn("Span.Kind.CONSUMER", bridge)
        self.assertIn('TASK_TYPE_TAG = "infranexum.worker.task.type"', bridge)
        self.assertIn('CORRELATION_TAG = "infranexum.correlation.id"', bridge)
        self.assertIn("createdSpan.end()", bridge)
        self.assertIn("traceScope.close()", bridge)
        self.assertNotIn("context.parameters()", bridge)

    def test_manual_span_attributes_are_allowlisted_and_never_use_payload_or_headers(self) -> None:
        java_sources = list((ROOT / "src").rglob("*.java"))
        tag_calls = []
        for source in java_sources:
            text = source.read_text(encoding="utf-8")
            if ".tag(" in text:
                tag_calls.append((source.relative_to(ROOT).as_posix(), text))
        self.assertEqual(1, len(tag_calls))
        path, bridge = tag_calls[0]
        self.assertTrue(path.endswith("WorkerCorrelationBridge.java"))
        self.assertIn('TASK_TYPE_TAG = "infranexum.worker.task.type"', bridge)
        self.assertIn('CORRELATION_TAG = "infranexum.correlation.id"', bridge)
        for forbidden in ("password", "secret", "token", "Authorization", "Cookie", "parameters()"):
            self.assertNotIn(forbidden, bridge)


if __name__ == "__main__":
    unittest.main()
