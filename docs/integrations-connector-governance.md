# Connector governance — PGM-10-E06 phase 4

## Scope

This phase makes the connector authority model an executable Server policy instead of leaving it only in SDK/provider documentation. It is deliberately provider-neutral and applies to the configured Jira Assets and ServiceNow connectors already present in InfraNexum.

It does **not** execute mutating synchronization. It provides the policy registry, fail-closed planning boundary and operator-visible dry-run that must exist before any import, write-back or bidirectional synchronization can be implemented safely.

The current provider policies remain non-mutating:

| Provider | Direction | Authority | Conflict | Deletion | Rollback |
|---|---|---|---|---|---|
| Jira Assets | `FEDERATED_READ` | `EXTERNAL` | `REJECT` | `IGNORE` | `NONE_REQUIRED` |
| ServiceNow | `FEDERATED_READ` | `EXTERNAL` | `REJECT` | `IGNORE` | `NONE_REQUIRED` |

Consequently, asking the dry-run planner to import data into InfraNexum, write data to the provider, propagate deletions or perform bidirectional synchronization is denied. This is intentional and fail-closed.

## Policy model

Every governed connector has one immutable `ConnectorGovernancePolicy` composed of:

- provider identity and connector key;
- `ConnectorSyncDirection`;
- `ConnectorDataAuthority`;
- `ConnectorConflictStrategy`;
- `ConnectorDeletionPolicy`;
- `ConnectorRollbackStrategy`;
- optional field-level authority mappings.

Supported synchronization directions are:

- `FEDERATED_READ`: provider data can be read without creating an InfraNexum copy;
- `INBOUND`: a future mutating flow may change InfraNexum state from an external source;
- `OUTBOUND`: a future mutating flow may change provider state from InfraNexum;
- `BIDIRECTIONAL`: a future flow may mutate both sides.

`FEDERATED_READ` is non-mutating. The other three directions are considered mutating and are subject to the stricter planning invariants below.

## Fail-closed invariants

A policy or plan is rejected when it is ambiguous or cannot be recovered safely.

A federated-read policy requires:

- `EXTERNAL` authority;
- `REJECT` or another explicitly configured conflict policy, with current providers using `REJECT`;
- deletion policy `IGNORE`;
- rollback `NONE_REQUIRED`;
- no field-level mutation mapping.

A mutating policy requires:

- explicit governed fields;
- a rollback strategy other than `NONE_REQUIRED`;
- local mutation to declare a strategy capable of restoring/checkpointing local state (`LOCAL_CHECKPOINT`, `DUAL_COMPENSATION` or `MANUAL`);
- remote mutation to declare a strategy capable of compensating remote state (`REMOTE_COMPENSATION`, `DUAL_COMPENSATION` or `MANUAL`);
- an explicit deletion policy if deletion propagation is requested.

The dry-run planner additionally denies:

- a requested direction that differs from the connector's configured direction;
- mutating synchronization with no requested governed fields;
- fields outside the connector's governed field set;
- deletion propagation when the policy says `IGNORE`;
- field mappings on a non-mutating federated-read request;
- any mutating request whose rollback declaration is `NONE_REQUIRED`.

A denial is a planning result, not a partial execution. No provider or local state is modified by the dry-run endpoint.

## Registry and connector identity

The Server builds one `ConfiguredConnectorGovernanceRegistry` from the provider definitions already loaded by `IntegrationRuntimeProperties`.

Connector keys are globally unique across the governance registry. If Jira Assets and ServiceNow are configured with the same connector key, Server composition fails rather than allowing an ambiguous policy lookup.

The registry is deterministic: policies are returned ordered by connector key and unknown keys fail with the public connector-governance not-found problem contract.

## API and authorization

All governance routes require capability `integrations.connectors` and existing permission `integrations.connector.read` at PLATFORM scope:

```text
GET  /api/v1/integrations/governance
GET  /api/v1/integrations/governance/{connectorKey}
POST /api/v1/integrations/governance/{connectorKey}/sync-plan
```

The collection is offset-paginated. The sync-plan operation is repeatable and does not mutate product/provider state, therefore it does not introduce a mutation idempotency contract.

Dry-run planning is audited using the actor and correlation context. Audit metadata is intentionally bounded to governance metadata such as provider, configured/requested direction, authority and rollback strategy; provider bearer tokens and other credentials are excluded.

Unregistered verbs and paths remain deny-by-default in the Server authorization resolver.

## Web behavior

The Integrations workspace exposes a **Connector Governance** section when `integrations.connectors` is available. For each configured connector it shows:

- connector key;
- provider;
- synchronization direction;
- authority;
- conflict strategy;
- deletion policy;
- rollback strategy;
- a dry-run action.

The Web client uses the authenticated same-origin session and CSRF protection for the dry-run POST. It never receives Jira/ServiceNow bearer tokens, secret references or provider Authorization headers.

The UI is translated through the existing DE/EN/ES/FR/IT catalogue.

## Current rollback semantics

`ConnectorRollbackStrategy` is a **governance declaration and admission precondition** in this phase. It is not yet an execution engine.

For the existing `FEDERATED_READ` providers, `NONE_REQUIRED` is correct because no local or remote object is mutated. For a future mutating provider policy, declaring a rollback strategy is mandatory before the planner can admit the direction, but the actual checkpoint, compensation, verification and operator rollback workflow must be implemented and tested in the later mutating-sync tranche before such a policy can be activated.

This distinction prevents a documentation-only rollback label from being mistaken for production rollback capability.

## OpenService boundary

PGM-10-E06 names **OpenService**, but the `draft.21` material available to this implementation still does not identify an authoritative OpenService product/API, endpoint model, authentication contract or business schema. Connector Governance therefore remains provider-neutral and does not fabricate an OpenService adapter.

An OpenService implementation requires an authoritative product decision/provider contract. Until that exists, InfraNexum can provide the governance framework into which that connector will later register, but cannot claim the provider itself as implemented.

## Operational verification

For a non-production deployment:

1. Configure zero Jira/ServiceNow connectors and verify the Server starts with an empty governance collection.
2. Configure one Jira Assets connector and one ServiceNow connector with distinct keys.
3. Verify the governance collection returns deterministic `FEDERATED_READ / EXTERNAL / REJECT / IGNORE / NONE_REQUIRED` policies.
4. Verify an unauthorized actor cannot list/read/plan governance.
5. Dry-run `FEDERATED_READ` with no fields/deletion propagation and verify `ALLOW`.
6. Dry-run `INBOUND`, `OUTBOUND` and `BIDIRECTIONAL` against those connectors and verify `DENY` with bounded reasons.
7. Request deletion propagation and verify denial while deletion policy is `IGNORE`.
8. Configure duplicate connector keys across providers in a test environment and verify Server composition fails closed.
9. Verify audit records exist for dry-run operations but contain no provider credentials.
10. Verify the browser network trace contains no provider Authorization header, bearer token or secret reference.

## Remaining PGM-10-E06 work

This phase satisfies the **runtime authority/sync-direction policy and rollback-strategy admission model**. PGM-10-E06 remains **EN COURS** because the following are not delivered here:

- actual mutating synchronization execution;
- durable synchronization checkpoints;
- compensation/rollback execution and verification;
- controlled deletion propagation;
- provider-specific field mappings for mutating flows;
- an OpenService adapter based on an authoritative provider contract.

No claim of bidirectional synchronization or executable connector rollback is made by this release.
