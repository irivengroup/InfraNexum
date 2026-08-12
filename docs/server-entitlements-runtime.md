# Server authoritative entitlements runtime

## Purpose

This runtime is the single Server authority for Lite evaluation lifecycle and signed Pro/Enterprise activation. It composes durable persistence, independent temporal evidence, capability evaluation, quota policy and HTTP enforcement.

## Startup sequence

1. Spring validates platform, persistence and entitlement configuration.
2. The external HMAC integrity key is loaded and permission-checked.
3. Paid profiles load the external Ed25519 trust store; Lite loads no commercial key.
4. JDBC repositories and the independent proof store are constructed.
5. Before servlet-port binding, `EntitlementRuntimeAuthority.initializeAndRequireStartup()` loads and verifies the durable state.
6. The resulting entitlement snapshot is atomically applied to capabilities and quotas.
7. Hard-stop decisions abort startup; otherwise the HTTP server may bind.

The startup guard deliberately uses `WebServerFactoryCustomizer`, not a post-start application runner.

## Runtime enforcement

- GET and HEAD remain readable when the lifecycle permits startup.
- POST, PUT, PATCH and DELETE below `/api/**` pass through the entitlement mutation guard.
- Access refusal returns HTTP 403 with `application/problem+json`.
- Missing or unavailable authority returns HTTP 503 with `application/problem+json`.
- The optional `X-Correlation-ID` is accepted only as canonical lowercase UUIDv7; malformed/non-v7 values fail closed with HTTP 400 and are never reflected.
- The evaluation status endpoint uses `Cache-Control: no-store`.

## Refresh and failure policy

The scheduler refreshes durable state at the configured interval. Any verification, database or independent-proof failure closes the application context. The runtime does not continue with an unverified stale decision.

## Configuration

| Environment variable | Purpose |
|---|---|
| `INFRANEXUM_PROFILE` | `LITE`, `PRO` or `ENTERPRISE` |
| `INFRANEXUM_PERSISTENCE_MODE` | `POSTGRESQL` or `ORACLE` when entitlements are enabled |
| `INFRANEXUM_DATABASE_URL` | JDBC connection URL |
| `INFRANEXUM_DATABASE_USERNAME` | external database identity |
| `INFRANEXUM_DATABASE_PASSWORD` | external database secret |
| `INFRANEXUM_CUSTOMER_ID` | expected customer binding |
| `INFRANEXUM_ACTIVATION_TRUST_STORE` | external trust-store path for paid profiles |
| `INFRANEXUM_INTEGRITY_KEY_FILE` | Base64 HMAC key file, 32–64 decoded bytes |
| `INFRANEXUM_INTEGRITY_PROOF_DIRECTORY` | persistent independent-proof directory |
| `INFRANEXUM_ACTIVATION_MAX_BYTES` | accepted manifest upper bound |
| `INFRANEXUM_ENTITLEMENT_REFRESH_INTERVAL` | ISO-8601 refresh duration |

No secret is accepted from a checked-in default value.

## Public endpoint

```text
GET /api/v1/platform/evaluation/status
```

The response summarizes lifecycle phase, activation state and temporal boundaries. It excludes signed manifest bytes, public keys, HMAC material, database credentials and independent proof payloads.

## Deliberately internal operation

`ActivationAdministrationService` provides preflight and coordinated import inside the application boundary. It is not currently exposed through HTTP because IAM authorization and append-only audit have not yet been implemented.

## Residual risks

- Full Spring Boot 4.1/Jackson 3 integration remains to be executed under the Java 25 Maven reactor.
- Real PostgreSQL/Oracle transaction and locking semantics remain to be certified.
- A coordinated rollback of both the database and local independent proof store to the same prior generation cannot be detected without an external monotonic anchor.
- Fresh-install identity provisioning remains an installer responsibility and is not yet implemented.
