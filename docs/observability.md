# Platform observability — PGM-12-E01

InfraNexum Server establishes one validated correlation context before every MVC or Actuator endpoint. This is the first executable slice of roadmap epic **PGM-12-E01**; OpenTelemetry export, cross-thread propagation, masking policies and dashboards remain separate follow-up increments.

## HTTP correlation contract

The optional inbound header is `X-Correlation-ID`. When it is absent, the Server generates a locally monotonic RFC 9562 UUIDv7. When it is supplied, it must be the canonical lowercase 36-character UUIDv7 representation. UUIDv4, uppercase/non-canonical representations, empty values and malformed identifiers are rejected with HTTP `400`.

The rejection path is fail-closed and does not reflect or log the caller-supplied value. It returns a fresh server-generated UUIDv7 in both the `X-Correlation-ID` response header and the canonical `application/problem+json` body. This identifier can safely be used to correlate the rejection with Server logs.

For accepted requests, the canonical identifier is:

- stored as a request attribute for application boundaries;
- returned in `X-Correlation-ID` on the response;
- bound to SLF4J MDC under `correlation_id` for the duration of each request dispatch;
- removed or restored in `finally` so servlet threads cannot leak correlation state between requests.

`EntitlementExceptionHandler` consumes this validated request context and never re-reads the raw inbound header.

## Structured logging

Console logs default to Spring Boot Elastic Common Schema (ECS) JSON using `logging.structured.format.console=ecs`. Spring Boot includes MDC key/value pairs in its structured JSON output, so the request `correlation_id` becomes a first-class structured field rather than a string-prefix convention.

Runtime overrides:

```text
INFRANEXUM_LOG_FORMAT=ecs
INFRANEXUM_ENVIRONMENT=local
INFRANEXUM_SERVER_INSTANCE_ID=server-local-1
INFRANEXUM_VERSION=2.0.0-alpha.0.41
```

`INFRANEXUM_LOG_FORMAT` exists for controlled operational overrides; production standards should keep a machine-readable structured format.

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

This increment does **not** claim completion of PGM-12-E01. Remaining scope includes at least OpenTelemetry traces/export, context propagation across asynchronous/background execution boundaries, systematic sensitive-data masking policy and tests, platform dashboards, retention/export configuration and target-environment validation.
