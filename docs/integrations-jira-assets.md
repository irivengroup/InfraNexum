# Jira Assets integration — PGM-10-E06 phase 6

## Scope and authority

Jira Assets remains **federated read by default**. A connector without an explicit mutation mapping and matching active governance policy is still `FEDERATED_READ / EXTERNAL / REJECT / IGNORE / NONE_REQUIRED` with synchronization execution disabled.

Phase 6 adds one deliberately narrow mutating path: **ITAM asset → Jira Assets upsert**. It is admitted only when all of the following are true for the same connector key:

- provider connector is enabled;
- a Jira mutation mapping exists;
- governance `direction=OUTBOUND`;
- object authority is `INFRANEXUM`;
- conflict strategy is `PREFER_AUTHORITY`;
- deletion policy is `IGNORE`;
- rollback strategy is `MANUAL`;
- `execution-enabled=true`;
- every governed field is `INFRANEXUM`-authoritative;
- the governed field set exactly equals the configured Jira attribute mapping.

Any mismatch fails Server composition. ServiceNow has no mutating handler in this phase. OpenService is not fabricated because the current roadmap sources name it without defining an authoritative provider/API contract.

## Provider protocol

The adapter is restricted to `https://api.atlassian.com`. Federated read uses `POST /object/aql` and projects only minimal object metadata. The governed outbound path uses the provider object endpoints:

- `POST /object/create` for a missing remote identity;
- `PUT /object/{id}` for an existing unique remote identity.

Before every write, InfraNexum searches by the immutable local asset UUID. The configured `identity-source-field` is therefore restricted to `id`; arbitrary operator-selected free-text identity fields are not accepted at this boundary.

The mutation payload contains `objectTypeId` and provider attribute IDs from explicit configuration. Provider attribute IDs, object type IDs and local field names are validated before any remote request. Remote provider attributes are still not exposed through the federated-read API.

## Incremental source and checkpoint semantics

The outbound source reads `infranexum_itam.asset` through a stable keyset ordered by `(updated_at, id)`. Migration `0041-itam-asset-sync-cursor` adds the supporting PostgreSQL/Oracle index without changing asset data.

The durable cursor is `updated_at|UUIDv7`. The source never uses offset pagination and projects only the governed fields required by the Jira mapping. A successful batch returns the last `(updated_at, id)` pair to the provider-neutral synchronization engine, which persists the normal revisioned checkpoint.

Deletion propagation is intentionally unsupported for this first mutator. A batch context with `propagateDeletions=true` is rejected by the handler, and a deleted outbound record is never converted into a Jira delete operation.

## Configuration

No Jira connector or mutation mapping is configured by default. A complete example is:

```yaml
infranexum:
  integrations:
    enabled: true
    jira-assets:
      maximum-response-bytes: 2097152
      connectors:
        jira-prod:
          cloud-id: example-cloud
          workspace-id: example-workspace
          bearer-token-reference: file:/run/secrets/jira-assets-token
          request-timeout: PT15S
          enabled: true
          mutation:
            object-type-id: "23"
            identity-attribute-name: "InfraNexum ID"
            identity-source-field: id
            batch-size: 50
            attribute-ids:
              id: "135"
              asset_type: "144"
    governance:
      jira-prod:
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

The identifiers are examples only. Real tenant identifiers, object type IDs, attribute IDs and tokens belong to deployment configuration, never source control. `bearer-token-reference` accepts only `env:NAME` or `file:/absolute/path`.

The field mapping is exact: adding a governed field without a corresponding Jira attribute, or configuring an attribute that is not governed, prevents startup rather than silently dropping data.

## Security boundaries

The adapter enforces:

- absolute HTTPS and exact egress host `api.atlassian.com`;
- redirects disabled;
- only GET/POST/PUT transport methods;
- bounded request timeout and response size;
- bounded AQL length and printable content, with control characters rejected before whitespace normalization;
- external secret resolution only and zeroing of resolved bearer-token buffers;
- bounded mutation mapping and provider identifiers;
- UUIDv7 local identity for write reconciliation;
- no browser/provider credential crossover;
- generic public provider errors that do not reflect remote payloads.

The Web continues to call only InfraNexum APIs using same-origin session and CSRF protections. It never constructs an Atlassian `Authorization` header.

## Failure, retry and rollback behavior

InfraNexum does not claim exactly-once provider execution. The upsert is designed for safe replay: after an uncertain outcome, the next attempt searches the immutable local UUID before deciding create versus update.

- `429`, provider `5xx` and transport unavailability are retryable; the run pauses and **does not** request compensation, even if earlier records in that batch were written.
- permanent provider or mapping failures fail the run; if an earlier record was already written, `compensationRequired=true`.
- because this phase is governed with `rollback-strategy=MANUAL`, no automatic remote inverse mutation is fabricated. Operator compensation is explicit.
- remote deletion is never attempted.

The rollback procedure for an operational incident is to disable execution or the connector, preserve the durable checkpoints, inspect Jira objects by the InfraNexum identity attribute, and perform the approved manual correction. Revoking the provider token remains appropriate when retiring the integration.

## API and Web surface

The existing provider-specific HTTP surface remains read-only:

- `GET /api/v1/integrations/providers/jira-assets`;
- `GET /api/v1/integrations/providers/jira-assets/{connectorKey}/health`;
- `POST /api/v1/integrations/providers/jira-assets/{connectorKey}/objects/search`.

Outbound mutation is not exposed as a new Jira-specific endpoint. It runs only through the generic governed synchronization operations (`execute`, `resume`, `compensate`) and their existing RBAC/idempotency boundary.

## Remaining PGM-10-E06 work

This phase does not implement bidirectional Jira synchronization, inbound Jira import, provider webhooks, remote deletion propagation, ServiceNow mutation or OpenService. Those require their own explicit authority, identity, conflict and rollback contracts before a mutating handler can be registered.
