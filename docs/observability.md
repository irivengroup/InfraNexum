# Platform observability — PGM-12-E01

InfraNexum Server establishes one validated correlation context before every MVC or Actuator endpoint. The HTTP boundary is the first executable slice of roadmap epic **PGM-12-E01**. `alpha.0.44` adds a mandatory sensitive-data redaction boundary on top of the correlation and OpenTelemetry layers. OTLP export remains disabled by default and requires explicit configuration; dashboards/runbooks remain a separate follow-up increment.

## HTTP correlation contract

The optional inbound header is `X-Correlation-ID`. When it is absent, the Server generates a locally monotonic RFC 9562 UUIDv7. When it is supplied, it must be the canonical lowercase 36-character UUIDv7 representation. UUIDv4, uppercase/non-canonical representations, empty values and malformed identifiers are rejected with HTTP `400`.

The rejection path is fail-closed and does not reflect or log the caller-supplied value. It returns a fresh server-generated UUIDv7 in both the `X-Correlation-ID` response header and the canonical `application/problem+json` body. This identifier can safely be used to correlate the rejection with Server logs.

For accepted requests, the canonical identifier is:

- stored as a request attribute for application boundaries;
- returned in `X-Correlation-ID` on the response;
- bound to SLF4J MDC under `correlation_id` for the duration of each request dispatch;
- removed or restored in `finally` so servlet threads cannot leak correlation state between requests.

`EntitlementExceptionHandler` consumes this validated request context and never re-reads the raw inbound header.


## Durable background-task propagation

When application code schedules a Core Worker task on a thread carrying a validated `correlation_id`, the Server `TaskCorrelationProvider` captures only that canonical UUIDv7. The value is stored as first-class nullable `worker_task.correlation_id` metadata by PostgreSQL/Oracle migration `0009`; it is not hidden inside task parameters. Tasks created outside a correlated request remain valid with no correlation identifier.

The task's original correlation is immutable under semantic idempotency replay. A later submission using the same task type/idempotency key returns the existing task identifier and does not replace the correlation that caused the original task creation. This preserves causal history across retries, process restarts and execution on another Server node.

Immediately before a handler executes, `WorkerCorrelationBridge` binds the persisted UUIDv7 to MDC key `correlation_id`. The prior worker-thread value is restored in a bounded scope after the handler returns or throws, preventing cross-task leakage. `TaskExecutionContext.correlationId()` exposes the same durable identifier to application code that must create correlated audit/event records. Raw request headers, authorization/security context and unrelated MDC fields are deliberately not propagated. Invalid internal MDC correlation state fails closed at scheduling instead of being silently discarded.

## Structured logging

Console logs default to Spring Boot Elastic Common Schema (ECS) JSON using `logging.structured.format.console=ecs`. Spring Boot includes MDC key/value pairs in its structured JSON output, so the request `correlation_id` becomes a first-class structured field rather than a string-prefix convention.

Runtime overrides:

```text
INFRANEXUM_ENVIRONMENT=local
INFRANEXUM_SERVER_INSTANCE_ID=server-local-1
INFRANEXUM_VERSION=2.0.0-alpha.0.69
```

The console format is fixed to ECS so runtime configuration cannot bypass the structured-value redaction customizer. Every string value is sanitized immediately before JSON serialization; stack trace output is additionally bounded to 8192 characters and 32 throwable frames.


### Sensitive-data redaction policy

`SensitiveDataStructuredLoggingCustomizer` installs one Spring Boot `JsonWriter.ValueProcessor` over the built-in ECS formatter. The processor delegates to the pure-JDK `SensitiveDataRedactor` for every string member, so messages, MDC values, structured fields and stack traces share one deterministic policy. Credential-bearing paths such as `password`, `client_secret`, `access_token`, `authorization`, `cookie`, `api_key`, `credential` and private-key fields are replaced wholesale with `[REDACTED]`. Arbitrary diagnostic text is scanned for common inline key/value credentials, Basic/Bearer authorization, Cookie headers, URI user-info passwords, JWTs and PEM private-key blocks.

The policy does not attempt to classify arbitrary business data and therefore must not be used as permission to log payloads. InfraNexum instrumentation continues to forbid task parameters, HTTP headers, security context and arbitrary MDC fields in spans. RFC Problem details emitted by Entitlements are passed through the same redactor before the response is serialized.

## OpenTelemetry tracing and controlled OTLP export

The Server includes Spring Boot's managed OpenTelemetry tracing starter. Trace propagation is restricted to **W3C Trace Context** and Micrometer baggage propagation is disabled, so InfraNexum does not copy arbitrary baggage values into logs, spans or downstream calls. Spring Boot's OpenTelemetry SDK environment-variable mapping is also disabled; the supported runtime contract is the explicit `INFRANEXUM_OTEL_*` configuration surface.

Product defaults are deliberately conservative:

```text
INFRANEXUM_OTEL_ENABLED=true
INFRANEXUM_OTEL_EXPORT_ENABLED=false
INFRANEXUM_OTEL_METRICS_EXPORT_ENABLED=false
INFRANEXUM_OTEL_SAMPLING_PROBABILITY=0.1
INFRANEXUM_OTEL_EXPORT_ENDPOINT=http://127.0.0.1:4318/v1/traces
INFRANEXUM_OTEL_METRICS_EXPORT_URL=http://127.0.0.1:4318/v1/metrics
INFRANEXUM_OTEL_CONNECT_TIMEOUT=5s
INFRANEXUM_OTEL_EXPORT_TIMEOUT=10s
```

Enabling tracing does not imply network export. Trace OTLP export must be activated explicitly with `INFRANEXUM_OTEL_EXPORT_ENABLED=true`; metrics OTLP export is independently opt-in through `INFRANEXUM_OTEL_METRICS_EXPORT_ENABLED=true`. Both require a collector endpoint appropriate to the deployment. Credentials must not be embedded in the endpoint URI or committed to repository configuration. The configured SDK bounds trace attribute length/count, event/link counts, exporter queue size, batch size and export timeouts.

Spring-managed HTTP observations provide trace/span identifiers in the logging context. InfraNexum additionally creates one fixed-name `CONSUMER` span around each durable Worker handler invocation. The Worker span is tagged only with the validated task type and, when present, the durable UUIDv7 `infranexum.correlation.id`; task parameters and arbitrary MDC state are never attached by this bridge. The span scope and the correlation MDC scope are both closed in `finally` paths before a pooled Worker thread can execute another task.

The durable UUIDv7 correlation remains the restart/node-stable causal identifier. `alpha.0.43` does not persist a W3C `traceparent` into `worker_task`; a Worker execution that occurs after its originating trace context has disappeared therefore starts a new trace that remains discoverable through the durable correlation attribute. Persisted trace-parent continuation is intentionally deferred until its retention/privacy model is specified.

## Metrics

Actuator already instruments HTTP exchanges. InfraNexum additionally publishes fixed-cardinality counters:

```text
infranexum.http.correlation.generated
infranexum.http.correlation.rejected
```

No request identifier, URI, user-controlled header value or secret is used as a metric tag.

## Developer runtime smoke

`./docker/dev-compose.sh smoke` and `.\docker\dev-compose.ps1 smoke` verify all of the following against the running Server:

1. readiness is `UP`;
2. Workers readiness metric is exposed;
3. a valid caller UUIDv7 is returned unchanged in `X-Correlation-ID`;
4. a malformed correlation value receives HTTP `400`;
5. the malformed value is not reflected in the problem body;
6. the rejection response receives a fresh server-generated UUIDv7 correlation header.

## Remaining PGM-12-E01 scope

This increment does **not** claim completion of PGM-12-E01. Remaining scope includes persisted trace-parent continuation only if its retention/privacy model is approved, platform dashboards/runbooks, retention/export configuration and target-environment OTLP validation.
