# Connector governance — PGM-10-E06 execution admission

## Scope

`2.0.0-alpha.0.122` extends the provider-neutral connector governance introduced in `alpha.0.114` and the durable synchronization/compensation runtime introduced in `alpha.0.116` with an explicit **execution admission** boundary.

A Jira Assets or ServiceNow connector can now carry a complete authority mapping for a future mutating flow without making that flow executable. This separates two states that were previously conflated:

- **prepared**: direction, object authority, conflict policy, deletion policy, rollback strategy and field authorities are complete, but `executionEnabled=false`;
- **active**: the same policy has `executionEnabled=true` and an approved `ConnectorSyncHandler` exists for the connector.

The default remains unchanged and backward compatible: every configured Jira Assets and ServiceNow connector without a governance override is `FEDERATED_READ / EXTERNAL / REJECT / IGNORE / NONE_REQUIRED`, with `executionEnabled=false`.

Provider mutating handlers are registered only through their provider-specific catalogs when a configured mutation mapping matches an active policy exactly: `ConfiguredJiraAssetsSyncHandlerCatalog` for Jira Assets and `ConfiguredServiceNowSyncHandlerCatalog` for ServiceNow. A policy whose direction/authority/conflict/deletion/rollback/field set does not match its provider mapping fails Server startup closed.

## Policy model

Every governed connector has one immutable `ConnectorGovernancePolicy` containing:

- connector key and provider identity;
- `ConnectorSyncDirection`;
- `ConnectorDataAuthority`;
- `ConnectorConflictStrategy`;
- `ConnectorDeletionPolicy`;
- `ConnectorRollbackStrategy`;
- `executionEnabled`;
- zero or more `ConnectorFieldAuthority` entries.

`FEDERATED_READ` is non-mutating and requires external authority, ignored deletion propagation, `NONE_REQUIRED` rollback, no field mappings and `executionEnabled=false`.

`INBOUND`, `OUTBOUND` and `BIDIRECTIONAL` are mutating directions. A mutating policy is valid only when field-level authority mappings and a compatible rollback strategy are complete. A valid mutating policy may nevertheless remain **prepared** with execution disabled.

## Configuration

Governance is configured under `infranexum.integrations.governance`, keyed by an existing Jira Assets or ServiceNow connector key. Provider identity is deliberately **not** configurable in the governance block; the Server derives it from the provider registry so configuration cannot spoof a provider.

Example of an executable outbound Jira Assets authority mapping:

```yaml
infranexum:
  integrations:
    jira-assets:
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

The example is executable only because the provider mapping and governance are exact. The IDs are illustrative deployment values. Jira mutation requires `identity-source-field=id` and `rollback-strategy=MANUAL`; every configured mutation field must be governed by `INFRANEXUM`. With `deletion-policy=IGNORE`, no tombstone mapping may be configured. Field names satisfy the canonical connector field-name contract and governance cardinality remains bounded.

Controlled remote deletion is an explicit **tombstone update**, never a physical provider delete. To admit it, configure both the provider marker and `deletion-policy=TOMBSTONE`:

```yaml
mutation:
  tombstone:
    attribute-id: "155"
    value: disposed
governance:
  jira-prod:
    deletion-policy: TOMBSTONE
```

The tombstone attribute cannot be the immutable identity attribute. A synchronization run must also explicitly request `propagateDeletions=true`; the Web checkbox is disabled by default and is enabled only for a `TOMBSTONE` policy.

Equivalent ServiceNow admission uses an explicit local-to-CMDB field mapping and an immutable custom identity column:

```yaml
infranexum:
  integrations:
    service-now:
      connectors:
        cmdb-production:
          instance-host: example.service-now.com
          table-name: cmdb_ci_server
          bearer-token-reference: file:/run/secrets/service-now-token
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

ServiceNow also requires `identity-source-field=id`; its provider identity target must be a custom `u_*` column, and `sys_*` columns are excluded from mutation mappings. The Server requires exact equality between the local mapping keys and the governed `INFRANEXUM` fields. Controlled tombstoning uses the same exact admission contract:

```yaml
mutation:
  tombstone:
    field-name: u_infranexum_state
    value: disposed
governance:
  cmdb-production:
    deletion-policy: TOMBSTONE
```

The tombstone field cannot overwrite the custom identity column. `MANUAL` deletion policy does not register an automatic propagation handler.

Startup fails when:

- a governance key does not identify a configured Jira Assets or ServiceNow connector;
- a connector key is duplicated across provider registries;
- a mutating policy lacks field mappings or a compatible rollback strategy;
- federated read attempts to enable synchronization execution;
- execution is enabled while the provider connector itself is disabled;
- a tombstone mapping is configured under `IGNORE`, or `TOMBSTONE` is selected without an explicit provider tombstone mapping;
- a synchronization handler is registered for a policy that is read-only or execution-disabled;
- an execution-enabled mutating policy has no registered synchronization handler.

This bidirectional admission check prevents both orphan handlers and partially activated policies.

## Planning and execution semantics

`ConnectorGovernancePlanner` remains the mandatory gate before a durable synchronization run. A mutating plan is denied when `executionEnabled=false`, even when its field mappings and rollback strategy are otherwise valid. The denial reason is explicit:

```text
mutating synchronization execution is disabled by connector policy
```

This makes a prepared policy observable without turning configuration into an implicit provider mutation switch.

Once Jira Assets or ServiceNow execution is legitimately admitted, the existing `ConnectorSyncEngine` provides the `alpha.0.116` durability guarantees: bounded batches, idempotent replay expectations, active-run fencing, append-only checkpoints, pause/resume and governed compensation. No exactly-once guarantee is inferred. The Jira handler receives the exact governed field set and deletion-propagation flag in its batch context and refuses any mismatch.

## Operational notification coupling

Critical lifecycle transitions may be routed into the existing durable signed-webhook notification outbox through `infranexum.integrations.notifications.sync-endpoint-keys`. This subscription is opt-in and startup-validates every target as a configured enabled notification endpoint. The sync mutation remains authoritative: notification admission occurs only after the durable run result and audit have been produced, and a notification failure cannot roll back or rewrite that connector-sync result.

Automatic events are limited to `PAUSED`, `FAILED`, `COMPENSATED` and `COMPENSATION_FAILED`. Their payload deliberately excludes actor identity, raw cursors/checkpoints, provider credentials, idempotency keys, request hashes and field lists. Re-admission of the same durable run projection is idempotent through the notification outbox natural key.

## API and Web behavior

The governance API remains:

```text
GET  /api/v1/integrations/governance
GET  /api/v1/integrations/governance/{connectorKey}
POST /api/v1/integrations/governance/{connectorKey}/sync-plan
```

`ConnectorGovernancePolicy` responses now include the required boolean `executionEnabled`. The API remains protected by capability `integrations.connectors` and the existing connector-read permission; no new mutation endpoint, RBAC permission or capability is introduced by this increment.

The Integrations workspace shows the execution-admission state in the governance table. The generic Sync **Execute** control is populated only from policies that simultaneously satisfy:

- `mutating=true`;
- `executionEnabled=true`;
- direction `INBOUND`, `OUTBOUND` or `BIDIRECTIONAL`.

A prepared mapping therefore remains visible and dry-runnable but cannot activate execution. Jira Assets and ServiceNow provider-specific pages remain federated-read-only; provider mutation is reached only through the generic governed Sync workflow.

All new UI text is available in DE/EN/ES/FR/IT.

## Security and audit

Provider identity is derived from the configured provider connector rather than accepted from the authority mapping. Secrets remain external through `env:` or absolute `file:` references and are never returned by the governance API.

Governance dry-run audit metadata records the bounded `execution_enabled` state in addition to provider, configured/requested direction, authority and rollback. No provider credential or remote object payload is written to that metadata.

## Rollback

For the Jira and ServiceNow OUTBOUND mutators, disabling future execution is configuration-safe, while already written provider objects require the configured manual recovery workflow. Migration `0041` is only an additive ITAM continuation index and contains no business-data rewrite:

1. set `execution-enabled=false`, remove the provider `mutation` block, or restore `FEDERATED_READ` defaults;
2. restart the Server and verify the provider handler is absent from the sync registry;
3. retain synchronization checkpoints for audit/recovery;
4. correct any already-written Jira/ServiceNow objects through the approved manual compensation procedure;
5. roll back migration `0041` only if the shared ITAM continuation index itself must be removed; no ITAM row changes are required.

## OpenService boundary

PGM-10-E06 still names **OpenService**, but `draft.21` does not provide an authoritative product/API endpoint, authentication or business-schema contract. This increment does not fabricate one. A future OpenService adapter must enter through the same governance and handler-admission contracts after its provider definition becomes authoritative.

## Status

PGM-10-E06 remains **EN COURS**. Jira Assets and ServiceNow each expose one OUTBOUND upsert path under exact governance, controlled `DISPOSED` tombstone projection is available through explicit per-run opt-in, and critical sync states can enter the durable notification outbox through explicit subscriptions. Inbound/bidirectional provider flows, live-provider certification and OpenService remain unavailable until their contracts are explicitly defined and implemented.

PGM-10-E05 remains formally **NON TERMINÉ** until the exact target JDK25/JaCoCo/PostgreSQL 17/18 gates succeed on the release snapshot.
