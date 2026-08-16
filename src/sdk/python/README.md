# InfraNexum Connector SDK for Python

The SDK is the stable connector-authoring boundary introduced by PGM-10-E05. It has no runtime dependency outside the Python 3.13 standard library and can be bundled in air-gapped installations.

A connector package declares a `infranexum.connector-manifest/v1` manifest, implements the object-oriented `Connector` contract and returns normalized `ConnectorResult` values. The SDK does not expose database handles, secret values, unrestricted filesystem access or unrestricted network clients. Those privileges remain runtime-owned and are granted only from an approved manifest.

Offline certification never imports connector code:

```bash
PYTHONPATH=src/sdk/python python -m infranexum_connector_sdk path/to/connector.json
```

Webhook helpers implement the draft.21 `X-InfraNexum-Signature`, timestamp and delivery-id contract using HMAC-SHA256, constant-time comparison, a bounded body size and an optional replay guard. `InMemoryReplayGuard` is intentionally a local/reference adapter only; production webhook acceptance must use the durable inbox phase of PGM-10-E05.
