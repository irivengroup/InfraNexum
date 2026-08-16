# PGM-10-E05 — Connector SDK v1 (phase 1)

> **Runtime status (alpha.0.101):** the durable Server phase is now implemented in `docs/integrations-connector-runtime.md`: signed webhook admission, PostgreSQL/Oracle inbox, DLQ, audited replay/resume, bounded retry/suspension and per-connector metrics. The SDK remains independently versioned `1.0.0`. Formal epic closure still requires the exact target JDK 25 + live PostgreSQL CI gate.


## Status and scope

`2.0.0-alpha.0.100` opens PGM-10-E05 with the stable authoring and certification boundary required before a connector runtime can accept external packages. This phase does **not** declare the epic delivered: durable incoming webhook admission, durable inbox/DLQ operations, controlled replay and per-connector runtime metrics remain phase 2 of PGM-10-E05.

The implementation reuses the transaction/event guarantees already delivered by PGM-02-E03 instead of creating a parallel event store. No provider-specific connector, credential store, database schema, public Server endpoint or authorization bypass is introduced in phase 1.

## Supported authoring contract

The canonical Python SDK lives under `src/sdk/python` and is versioned independently as `infranexum-connector-sdk 1.0.0`. It targets Python `>=3.13,<3.14` and has no runtime dependency outside the standard library.

A connector implementation derives from `Connector`, exposes a validated manifest and implements `execute(context, request) -> ConnectorResult`. The runtime-owned context contains only bounded identifiers, the deadline, granted capabilities and metadata. The SDK intentionally exposes no JDBC/database handle, secret value, unrestricted filesystem object or unrestricted network client.

The normalized invocation contract supports the roadmap modes `pull`, `push`, `webhook`, `batch`, `streaming` and `federated-read`. Requests carry an operation token, an idempotency key, bounded payload metadata and an optional checkpoint. Results are explicit `success`, `retry` or `failure` outcomes; retry delays are bounded and cannot be attached to successful outcomes.

## Connector manifest v1

`src/sdk/python/infranexum_connector_sdk/schemas/connector-manifest.schema.json` is the machine-readable `infranexum.connector-manifest/v1` shape. `ConnectorManifest` adds semantic certification checks that JSON Schema alone cannot express.

Every package must declare:

- stable package identity and Semantic Versioning version;
- SDK contract version and minimum SDK version, which must not be newer than the certifier SDK;
- certification level and isolation requirement;
- provider product and explicit supported versions; wildcard compatibility is rejected;
- connector execution modes, required InfraNexum capabilities and permissions;
- **secret declarations only** (`name`, `purpose`, `required`); secret values are rejected by the strict manifest shape;
- exact HTTPS egress destinations with DNS host and bounded port; wildcard, localhost and IP-literal targets are rejected;
- synchronization direction, field authority, conflict strategy and deletion policy; bidirectional synchronization requires field-level authority mappings;
- idempotency, checkpointing, bounded attempts/backoff and `controlled` replay semantics;
- webhook direction and HMAC-SHA256/timestamp policy;
- data fields with purpose and classification (`public`, `internal`, `confidential`, `restricted`);
- versioned data contracts, runtime limits and an owner/support end date.

Unknown fields fail certification. Manifests are size-bounded to 1 MiB when loaded through the SDK/CLI, canonicalized deterministically, SHA-256 fingerprinted and exposed as deeply immutable mappings/tuples after validation.

## Offline certification

Certification parses metadata only and never imports connector code:

```bash
PYTHONPATH=src/sdk/python python -m infranexum_connector_sdk path/to/connector-manifest.json
```

Machine-readable output is the default. `--output text` provides an operator-oriented summary. Exit code `0` means the manifest satisfies the v1 contract; exit code `2` means certification failed. Read failures, malformed/non-UTF-8 JSON and oversized manifests fail closed.

The CI gate `make sdk-test sdk-check` executes branch-aware coverage with a threshold of 98%, verifies SDK/manifest contract metadata, builds the pure-Python wheel twice with a fixed `SOURCE_DATE_EPOCH`, requires byte-for-byte reproducibility and imports the built wheel rather than relying only on the source tree.

## Webhook cryptographic primitives

`WebhookSigner` and `WebhookVerifier` implement the draft.21 HMAC-SHA256 envelope over:

`<unix-seconds>.<delivery-id>.<raw-body>`

The contract uses a minimum 32-byte secret, a maximum 1 MiB body, a timezone-aware timestamp, constant-time signature comparison and a bounded clock-skew window (default five minutes, maximum one hour). `InMemoryReplayGuard` is thread-safe and bounded but exists only for tests/local connector development. It must not be used as the production replay boundary because process restart would erase reservations.

Production incoming webhooks will bind this signature check to the durable Core inbox in phase 2 so `(connector, delivery-id)` deduplication, audit, retry state and replay survive process/node failure.

## Security and compatibility invariants

- No exactly-once guarantee is claimed; delivery is at-least-once plus idempotency/deduplication.
- No infinite retry is permitted by the manifest contract.
- No connector may declare unrestricted egress or embed a credential value in its manifest.
- Community/validated packages must declare runtime isolation.
- Bidirectional synchronization is rejected without explicit authority ownership.
- SDK contract `1.0.0` remains independent from product alpha versioning; incompatible SDK evolution requires a new manifest contract version rather than silent reinterpretation.
- Non-native package execution remains outside the Server process and is not activated by this phase.

## Remaining PGM-10-E05 work

The epic remains **IN PROGRESS** after `alpha.0.100`. The next phase must integrate, on top of the existing Core event store:

1. authenticated incoming webhook admission mapped to a connector instance and command, never direct database writes;
2. durable connector inbox/deduplication and connector execution checkpoints;
3. operational DLQ query surface and audited, authorization-controlled replay;
4. bounded retry scheduling and connector suspension policy;
5. connector health, latency, backlog, error/retry/DLQ metrics with bounded cardinality;
6. production replay protection durable across Server nodes/restarts;
7. contract/API/CLI/Web exposure only where capabilities and profile entitlements permit it.

PGM-10-E06 and DNS/DHCP epics that depend on PGM-10-E05 remain blocked until those runtime exit criteria are closed.
