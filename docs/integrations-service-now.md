# ServiceNow CMDB integration — PGM-10-E06 phase 7

## Scope

`components.adapters.service-now` preserves the bounded **federated-read** Table API surface and adds one deliberately narrow mutating path: **ITAM asset → ServiceNow CMDB upsert**. Read-only remains the default. A connector without both an explicit mutation mapping and an exactly matching active governance policy stays `FEDERATED_READ / EXTERNAL / REJECT / IGNORE / NONE_REQUIRED`, with synchronization execution disabled.

The read contract remains intentionally smaller than the full ServiceNow Table API:

- one configured `*.service-now.com` instance hostname per connector;
- one configured CMDB CI table (`cmdb_ci` or `cmdb_ci_*`) per connector;
- health verification through a one-row bounded read;
- name search only, with a 1–256 character allow-list;
- offset pagination with `0 <= offset <= 1,000,000` and `1 <= limit <= 200`;
- projection limited to `sys_id`, `name`, `sys_class_name` and `sys_updated_on`;
- no arbitrary provider encoded query supplied by an InfraNexum caller.

The outbound path is limited to create-or-partial-update of the configured CMDB table. Remote deletion, inbound import, bidirectional reconciliation and arbitrary ServiceNow scripting remain unavailable.

## Governance and mutation admission

ServiceNow mutation is registered only when all of the following are true for the same connector key:

- provider connector is enabled;
- a ServiceNow mutation mapping exists;
- governance `direction=OUTBOUND`;
- object authority is `INFRANEXUM`;
- conflict strategy is `PREFER_AUTHORITY`;
- deletion policy is `IGNORE`;
- rollback strategy is `MANUAL`;
- `execution-enabled=true`;
- every governed field is `INFRANEXUM`-authoritative;
- the governed local field set exactly equals the configured ServiceNow field mapping.

Any mismatch fails Server composition. The adapter still depends only on the Integrations domain and Core contracts; the provider-specific module does not depend on ITAM or RSOT. The provider-neutral `ConnectorOutboundSource` supplies the governed ITAM projection.

## Stable identity and field mapping

The only supported local identity source is `id`, the canonical InfraNexum UUIDv7. It must map to a ServiceNow **custom column** beginning with `u_`, for example `u_infranexum_id`. This prevents a built-in operational field such as `sys_id` or `sys_updated_on` from being repurposed as the reconciliation key.

All mapped provider columns must be lowercase ServiceNow-style identifiers. `sys_*` columns are reserved and rejected at configuration admission. The configured identity field must be part of the exact governed field set.

Before every write, InfraNexum performs a bounded two-row identity lookup constructed internally as:

```text
<configured-u-field>=<canonical-uuidv7>^ORDERBYsys_id
```

Zero matches result in create, exactly one match results in update, and two matches fail closed as an identity conflict. The caller cannot supply an encoded query.

## Provider protocol

InfraNexum uses the ServiceNow Table API on the configured `cmdb_ci`/`cmdb_ci_*` table:

- `GET /api/now/table/{table}` for health, search and identity lookup;
- `POST /api/now/table/{table}` to create one governed CI;
- `PATCH /api/now/table/{table}/{sys_id}` to update only the governed fields of an existing CI.

`PUT` is deliberately not used for the outbound handler because this phase requires partial field mutation rather than replacement semantics. `DELETE` is not admitted by the transport. Mutation responses are reduced to the validated 32-hex-character `sys_id`; provider payloads do not escape the adapter.

## Configuration

No ServiceNow connector or mutation mapping is configured by default. Example:

```yaml
infranexum:
  integrations:
    enabled: true
    service-now:
      maximum-response-bytes: 2097152
      connectors:
        cmdb-production:
          instance-host: example.service-now.com
          table-name: cmdb_ci_server
          bearer-token-reference: file:/run/secrets/infranexum-service-now-token
          request-timeout: PT15S
          enabled: true
          mutation:
            identity-source-field: id
            batch-size: 50
            field-names:
              id: u_infranexum_id
              asset_type: u_asset_type
    governance:
      cmdb-production:
        direction: OUTBOUND
        authority: INFRANEXUM
        conflict-strategy: PREFER_AUTHORITY
        deletion-policy: IGNORE
        rollback-strategy: MANUAL
        execution-enabled: true
        fields:
          id: INFRANEXUM
          asset_type: INFRANEXUM
```

The hostname, table and custom columns are illustrative deployment values. The custom identity column must exist in the target ServiceNow schema, be writable by the integration account and should have a tenant-side uniqueness constraint or equivalent data policy. InfraNexum still detects duplicate identities independently and refuses to choose one.

## Authentication and secrets

InfraNexum accepts only a pre-provisioned bearer token referenced by `env:` or `file:`. The Server secret provider restricts `file:` to absolute, regular, non-symlink files and bounds resolved secrets to 32..4096 bytes. Resolved bearer-token buffers are zeroed after each request. No bearer token, OAuth client secret, refresh token or ServiceNow credential is returned to the browser.

The ServiceNow machine identity must receive only the Table API/ACL rights required for the configured table and governed fields. Token acquisition and renewal remain external until the InfraNexum Secret Service/PKI/KMS boundary is delivered.

## Network, payload and query controls

The JDK transport enforces:

- HTTPS only and `*.service-now.com` host pinning;
- no userinfo, explicit port or redirect following;
- Table API path only;
- HTTP methods limited to `GET`, `POST` and `PATCH`;
- GET bodies forbidden; mutation request bodies bounded to 256 KiB;
- per-connector request timeout in `(0s, 60s]`;
- response bodies 2 MiB by default, configurable up to 8 MiB;
- mutation cardinality 1..64 fields and value length at most 4096 printable characters;
- 401/403 → authentication/authorization failure;
- 429 → retryable provider rate limiting;
- 5xx/transport failure → retryable provider unavailability;
- other mutation rejections → permanent provider/protocol failure without remote response-body disclosure.

Federated name search still generates only `nameLIKE<validated-term>^ORDERBYsys_id` and continues to set bounded `sysparm_fields`, `sysparm_limit`, `sysparm_offset`, `sysparm_exclude_reference_link`, `sysparm_display_value=false` and `sysparm_no_count=true`.

## Incremental source and checkpoint semantics

ServiceNow reuses the provider-neutral durable synchronization runtime and the same ITAM outbound keyset source already admitted for Jira Assets. Records are ordered by `(updated_at, id)` and continuation uses the existing `updated_at|UUIDv7` cursor/checkpoint. No offset scan and no new database migration are introduced by this phase.

A batch with `propagateDeletions=true` is rejected as a governance mismatch. A deleted outbound record is counted as rejected and never converted into a ServiceNow delete.

## Failure, retry and compensation

InfraNexum does not claim exactly-once ServiceNow execution. Replay is convergent because every attempt searches the immutable local UUID before deciding create versus patch.

- `429`, provider `5xx` and transport unavailability are retryable; the run pauses without requesting compensation.
- permanent provider/protocol/mapping failures fail the run; if an earlier record in the batch was written, `compensationRequired=true`.
- identity duplication is permanent and fail-closed.
- rollback strategy for this phase is `MANUAL`; no automatic inverse CMDB mutation is fabricated.
- remote deletion is never attempted.

Operational rollback is to disable connector execution, preserve checkpoints, inspect target CIs by the configured InfraNexum identity column, perform approved manual correction and revoke the provider token if the integration is being retired.

## InfraNexum API and Web

The provider-specific HTTP surface remains read-only and unchanged:

```text
GET  /api/v1/integrations/providers/service-now
GET  /api/v1/integrations/providers/service-now/{connectorKey}/health
POST /api/v1/integrations/providers/service-now/{connectorKey}/configuration-items/search
```

All three operations retain capability `integrations.connectors` and permission `integrations.connector.read`. Outbound mutation is exposed only through the existing generic governed synchronization operations (`execute`, `resume`, `compensate`), with their existing RBAC and idempotency requirements.

The browser continues to use only the authenticated same-origin InfraNexum API and never constructs a ServiceNow authorization header. Governance and synchronization state are shown through the generic Integrations workspace.

## Operational verification

For a non-production connector, verify at minimum:

1. Server startup fails if an enabled connector token cannot be resolved.
2. A prepared mutation policy with `execution-enabled=false` remains non-executable.
3. An active policy without an exact mutation mapping fails startup.
4. Duplicate remote values for the configured identity column fail closed.
5. First replay creates at most one CI and later replay patches the same `sys_id`.
6. Only configured fields are present in POST/PATCH payloads.
7. 429/5xx do not request compensation and preserve replayability.
8. A permanent second-record failure after a successful first write marks manual compensation required.
9. No DELETE request can be emitted.
10. The Web browser contains no ServiceNow bearer credential.

## Status

PGM-10-E06 remains **EN COURS**. Jira Assets and ServiceNow now each have one governed ITAM OUTBOUND upsert path. Inbound/bidirectional synchronization, controlled remote deletion, live-provider certification and OpenService remain outside this phase.
