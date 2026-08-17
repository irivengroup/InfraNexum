# Jira Assets integration — PGM-10-E06 phase 1

## Scope and authority

`2.0.0-alpha.0.108` introduces the first provider-specific slice of PGM-10-E06. The connector is deliberately **federated read only**:

- provider: `jira-assets`;
- direction: `FEDERATED_READ`;
- authority: `EXTERNAL`;
- local write authority: none;
- local persistence of remote objects: none;
- remote deletion propagation: not applicable in this phase because no provider object is copied locally.

This implements the INT-ADV-003 requirement for federated read without copy while preserving the INT-ADV-007 authority boundary. It does not claim bidirectional synchronization. Any later import or synchronization phase must define field-level authority, conflict handling, deletion semantics, checkpoints and rollback before activation.

## Provider protocol

The native adapter targets Jira Service Management Assets Cloud through the fixed Atlassian API host `https://api.atlassian.com`.

Health uses the object-schema list API with a one-item page. Object search uses the current `POST /object/aql` contract with `startAt`, `maxResults` and `includeAttributes=false`. The deprecated navlist AQL route is intentionally not used.

Only the following remote object metadata is projected to callers:

- object id;
- global id;
- object key;
- label;
- object type id;
- object type name.

Provider attributes are intentionally excluded. This minimizes transferred data and prevents an implicit field-authority model from appearing before it is specified.

The connector expects an authorization token whose effective Jira scopes permit the operations used. The schema health operation requires Assets schema read access; AQL search requires Assets object read access. Token issuance/consent remains an operator responsibility and must follow the Atlassian application model selected for the deployment.

## Configuration

The Server configuration is externalized under `infranexum.integrations.jira-assets`. No connector is configured by default.

```yaml
infranexum:
  integrations:
    enabled: true
    jira-assets:
      maximum-response-bytes: 2097152
      connectors:
        jira-assets.prod:
          cloud-id: "<atlassian-cloud-id>"
          workspace-id: "<assets-workspace-id>"
          bearer-token-reference: "file:/run/secrets/jira_assets_oauth_token"
          request-timeout: PT15S
          enabled: true
```

The angle-bracket values above are configuration examples, not shipped defaults. Real identifiers and tokens must be provided outside source control. `bearer-token-reference` accepts only `env:NAME` or `file:/absolute/path`.

For the Web, publication is independently controlled with:

```text
INFRANEXUM_WEB_INTEGRATIONS_CONNECTORS_ENABLED=true
```

The browser receives only the boolean capability publication. Jira tenant identifiers and provider credentials remain Server-side.

## Security boundaries

The adapter enforces:

- absolute HTTPS URI;
- exact egress host `api.atlassian.com`;
- no HTTP redirect following;
- GET/POST methods only;
- request timeout in `(0 s, 60 s]`;
- default response limit 2 MiB, configurable up to 8 MiB;
- AQL length 1..4,096 printable characters;
- offset 0..1,000,000 and page size 1..200;
- defensive copies around transport buffers;
- external secret resolution only;
- zeroing of the resolved bearer-token byte buffer after each request;
- generic public errors for authentication, throttling, provider unavailability and protocol failures.

The Web client uses the authenticated InfraNexum session and CSRF token for POST AQL requests. It never constructs an Atlassian `Authorization` header.

## API surface

The Server publishes:

- `GET /api/v1/integrations/providers/jira-assets?offset=&limit=` — configured connector catalogue;
- `GET /api/v1/integrations/providers/jira-assets/{connectorKey}/health` — provider health probe;
- `POST /api/v1/integrations/providers/jira-assets/{connectorKey}/objects/search?offset=&limit=` — federated AQL search.

All three operations require:

- capability `integrations.connectors`;
- permission `integrations.connector.read`.

The two collection/search operations expose the standard InfraNexum offset-pagination contract. The AQL POST is semantically read-only and repeatable; it is CSRF-protected for browser sessions but does not require a mutation idempotency key.

## Failure and offline behavior

A provider failure never causes a local fallback write or stale implicit synchronization:

- 401/403 -> authentication failure;
- 429 -> rate-limited failure;
- 5xx/network interruption -> unavailable failure;
- malformed/unexpected payload -> protocol failure;
- disabled connector -> unavailable failure.

No automatic retry loop is introduced in this synchronous federated-read phase. The operator/caller receives an explicit failure and may retry according to the calling workflow. This avoids converting provider throttling or an outage into unbounded load.

An air-gapped deployment can keep the adapter installed while leaving every Jira Assets connector disabled/unconfigured. The absence of SaaS reachability therefore does not prevent InfraNexum startup.

## Rollback

Rollback is configuration-only because this phase persists no Jira object locally:

1. set the affected connector `enabled: false`, or remove its connector definition;
2. restart/reload according to the normal Server configuration deployment procedure;
3. verify the connector is absent/disabled through the catalogue and that no Jira Assets requests are emitted;
4. revoke the provider token at the issuer when retiring the integration.

No RSOT/ITAM data rollback is required because this phase performs no local import or mutation.

## Non-goals of phase 1

This candidate does not implement:

- Jira Assets write-back;
- field mappings or transformations;
- local object import;
- bidirectional synchronization;
- provider webhooks for Jira Assets;
- field-level authority/conflict resolution;
- ServiceNow;
- OpenService;
- notification connectors.

Those capabilities remain subsequent PGM-10-E06 work and must reuse the existing connector SDK/runtime governance rather than bypass it.
