# Connector governance — PGM-10-E06 execution admission

## Scope

`2.0.0-alpha.0.122` extends the provider-neutral connector governance introduced in `alpha.0.114` and the durable synchronization/compensation runtime introduced in `alpha.0.116` with an explicit **execution admission** boundary.

A Jira Assets or ServiceNow connector can now carry a complete authority mapping for a future mutating flow without making that flow executable. This separates two states that were previously conflated:

- **prepared**: direction, object authority, conflict policy, deletion policy, rollback strategy and field authorities are complete, but `executionEnabled=false`;
- **active**: the same policy has `executionEnabled=true` and an approved `ConnectorSyncHandler` exists for the connector.

The default remains unchanged and backward compatible: every configured Jira Assets and ServiceNow connector without a governance override is `FEDERATED_READ / EXTERNAL / REJECT / IGNORE / NONE_REQUIRED`, with `executionEnabled=false`.

No Jira Assets or ServiceNow mutating handler is delivered or registered by this increment. Consequently, setting `executionEnabled=true` for those providers in the current product causes Server startup to fail closed because no approved handler exists.

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

Example of a prepared inbound Jira Assets authority mapping:

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
    governance:
      jira-prod:
        direction: INBOUND
        authority: EXTERNAL
        conflict-strategy: PREFER_AUTHORITY
        deletion-policy: IGNORE
        rollback-strategy: LOCAL_CHECKPOINT
        execution-enabled: false
        fields:
          name: EXTERNAL
          serial-number: EXTERNAL
```

The example is intentionally **non-executable**. It prepares an explicit authority contract only. The field names must satisfy the canonical connector field-name contract and the map is bounded to 512 fields per connector. Governance cardinality is bounded to 128 policies per Server runtime.

Startup fails when:

- a governance key does not identify a configured Jira Assets or ServiceNow connector;
- a connector key is duplicated across provider registries;
- a mutating policy lacks field mappings or a compatible rollback strategy;
- federated read attempts to enable synchronization execution;
- execution is enabled while the provider connector itself is disabled;
- a synchronization handler is registered for a policy that is read-only or execution-disabled;
- an execution-enabled mutating policy has no registered synchronization handler.

This bidirectional admission check prevents both orphan handlers and partially activated policies.

## Planning and execution semantics

`ConnectorGovernancePlanner` remains the mandatory gate before a durable synchronization run. A mutating plan is denied when `executionEnabled=false`, even when its field mappings and rollback strategy are otherwise valid. The denial reason is explicit:

```text
mutating synchronization execution is disabled by connector policy
```

This makes a prepared policy observable without turning configuration into an implicit provider mutation switch.

Once execution is legitimately admitted in a future provider tranche, the existing `ConnectorSyncEngine` continues to provide the `alpha.0.116` durability guarantees: bounded batches, idempotent replay expectations, active-run fencing, append-only checkpoints, pause/resume and governed compensation. No exactly-once guarantee is inferred.

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

A prepared mapping therefore remains visible and dry-runnable but cannot activate the browser execution workflow. Jira Assets and ServiceNow provider sections themselves remain federated-read-only and contain no create/update/delete/import/sync provider call.

All new UI text is available in DE/EN/ES/FR/IT.

## Security and audit

Provider identity is derived from the configured provider connector rather than accepted from the authority mapping. Secrets remain external through `env:` or absolute `file:` references and are never returned by the governance API.

Governance dry-run audit metadata records the bounded `execution_enabled` state in addition to provider, configured/requested direction, authority and rollback. No provider credential or remote object payload is written to that metadata.

## Rollback

For `alpha.0.122`, rollback of this change is configuration-safe because no new database migration exists:

1. remove the connector entry under `infranexum.integrations.governance`, or restore `FEDERATED_READ` defaults;
2. restart the Server;
3. verify the governance API returns `executionEnabled=false` and the default federated-read policy;
4. verify the Web Sync execution selector contains no Jira Assets or ServiceNow mutator.

Removing a prepared governance block does not require RSOT/ITAM data rollback because the prepared state cannot execute mutations.

## OpenService boundary

PGM-10-E06 still names **OpenService**, but `draft.21` does not provide an authoritative product/API endpoint, authentication or business-schema contract. This increment does not fabricate one. A future OpenService adapter must enter through the same governance and handler-admission contracts after its provider definition becomes authoritative.

## Status

PGM-10-E06 remains **EN COURS**. This increment delivers configurable authority mapping plus explicit execution admission, but activates no provider mutator.

PGM-10-E05 remains formally **NON TERMINÉ** until the exact target JDK25/JaCoCo/PostgreSQL 17/18 gates succeed on the release snapshot.
