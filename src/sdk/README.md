# SDK

`src/sdk` contains supported client/extension authoring contracts. PGM-10-E05 activates the first production-facing SDK surface: the **Python connector SDK v1**.

## Connector SDK v1

- `python/infranexum_connector_sdk/schemas/connector-manifest.schema.json` is the machine-readable manifest contract.
- `python/infranexum_connector_sdk/` is the dependency-free Python 3.13 authoring SDK.
- manifests are versioned, canonicalized and certified offline without importing third-party connector code;
- permissions, capabilities, secret names, egress destinations, provider versions, field authority, data classification, retry bounds and support lifecycle are explicit;
- wildcard provider compatibility, wildcard egress and secret values are rejected;
- community/validated connectors must declare runtime isolation;
- delivery is explicitly idempotent, checkpoint-aware and replay-controlled; no exactly-once guarantee is claimed;
- HMAC-SHA256 webhook helpers implement timestamp and delivery-id replay protection.

The SDK intentionally does **not** execute non-native packages inside the Server process and does not persist connector credentials. Sandboxed package execution remains governed by the extension/runtime work that follows. Durable webhook inbox, DLQ replay and bounded retry/suspension are implemented by the Server runtime; provider-specific integrations build on that boundary without moving third-party credentials or domain authority into the SDK.
