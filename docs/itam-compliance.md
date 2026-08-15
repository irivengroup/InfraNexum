# ITAM warranty, support and software-license compliance — PGM-07-E03

## Scope

`PGM-07-E03` completes the contractual-readiness layer required by the InfraNexum ITAM asset lifecycle. It does not create a second Partner, Organization, IAM or RSOT authority: all external identifiers remain weak references validated through application ports, while ITAM owns warranty, software-license, third-party-support and contractual evidence state.

The implementation is available through capability `itam.compliance` only when its dependency chain `itam.partners → itam.assets → itam.compliance` is effective. Server HTTP remains disabled unless `INFRANEXUM_ITAM_COMPLIANCE_API_ENABLED=true`; Web publication is independently fail-closed through `INFRANEXUM_WEB_ITAM_COMPLIANCE_ENABLED=true` and requires both Partner and Asset publication.

## Canonical producer correction

PGM-07-E03 requires the hardware manufacturer or software publisher to match the contractual evidence attached to an asset. `Asset` therefore has a nullable `producerPartnerId`:

- new governed acquisitions may provide it directly;
- pre-E03 assets remain readable and retain their exact persisted state;
- no migration invents or backfills a producer;
- `POST /api/v1/itam/assets/{assetId}/producer` performs the explicit versioned correction;
- the legacy Java `CreateAssetCommand`, `Asset.acquired` and `Asset.restore` signatures remain source-compatible and delegate with a null producer;
- operational readiness remains fail-closed until the producer is governed.

Hardware producers must resolve to an active authorized Partner with the `manufacturer` role. Software producers must resolve to an active authorized Partner with the `software_publisher` role for the owning organization and effective date.

## Manufacturer warranty

A warranty owns the following governed data:

- asset and canonical manufacturer Partner;
- governed warranty type;
- coverage level;
- warranty start/end dates;
- separate manufacturer support end date;
- optional contract/certificate reference;
- evidence reference and source;
- verifier and verification timestamp;
- optimistic version, status, audit actors/timestamps and change reason.

Statuses are `draft`, `active`, `expired`, `cancelled` and `superseded`. Activation is the verification step: an active/expired verified state cannot exist without verifier and timestamp. A warranty cannot cover an asset outside its contractual period, and expiration is explicit and only possible after the contractual end date.

Warranty types are governed catalogue values rather than free text. Catalogue reads and administration are organization-scoped.

## Software-license contract

The software contract stores contractual entitlement metadata rather than activation secrets:

- canonical publisher Partner;
- contract number and license model;
- usage rights;
- entitlement quantity;
- start/end dates;
- publisher support end date;
- evidence reference/source and verifier;
- versioned status/history and reason.

`licenseKey`, `productKey`, `serialKey`, `activationKey` and equivalent raw secret material are outside PGM-07-E03. The HTTP/OpenAPI/Web/CLI contracts do not expose such fields, unknown JSON properties are rejected, and the Web/CLI boundaries explicitly reject common raw-key names. Secret-bearing licensing material remains blocked until `PGM-13-E02 — Secret Service/PKI/KMS` provides an approved protected storage path.

## Third-party support authorization

A third-party support provider is the canonical Partner aggregate with role `third_party_support_provider`; there is no free-text provider identity.

A support authorization scopes the provider by:

- organization;
- supported manufacturer Partners;
- supported RSOT object/product types;
- optional subdivision/geographic scopes;
- service hours and IANA timezone;
- supported service/SLA levels;
- escalation contact types;
- validity period;
- governed status/version/audit state.

Activation requires the effective date to be inside the authorization period. Coverage eligibility requires all scopes to match: provider, manufacturer, object type, subdivision where restricted, service level and date.

Suspending an active authorization is fail-closed: every active coverage referencing it is moved transactionally to `review_required` and emits `itam.support_coverage.review_required.v1`. No new coverage can rely on a suspended authorization.

## Support coverage

A support coverage snapshots the authorization scope relevant to the asset:

- asset, provider and authorization identifiers;
- contract reference and coverage type;
- service/SLA level;
- start/end dates;
- manufacturer, object type, organization and subdivision snapshot;
- proof reference;
- optimistic version/status/audit state.

Third-party coverage may extend supportability after manufacturer warranty expiry only when the authorization and coverage are both active and still cover the exact asset scope. It never changes manufacturer warranty/support dates.

## Operational readiness

`AssetOperationalReadinessPolicy` is now backed by `ComplianceApplicationService` rather than a temporary gate:

- hardware requires a canonical producer and verified manufacturer warranty matching that producer; during the active warranty period it is ready;
- after warranty expiry, an active authorized third-party coverage matching producer, object type, geography, SLA and date may continue hardware supportability;
- software requires a canonical publisher and an active verified software-license contract matching that publisher;
- disabling any required capability causes readiness to return false;
- a rejected readiness transition does not alter asset state/version/custody.

The policy is consumed by `IN_STOCK`, `ASSIGNED` and `DEPLOYED`; acquisition and evidence correction remain possible so incomplete imported assets can be brought into compliance without bypassing the gate.

## Alerts and expiry

Contract deadlines are evaluated independently from record `updated_at`. Default thresholds are:

```text
J-180, J-120, J-90, J-60, J-30, J-15, J-7, J-1
```

They are configured with:

```text
INFRANEXUM_ITAM_COMPLIANCE_ALERT_THRESHOLDS=180,120,90,60,30,15,7,1
INFRANEXUM_ITAM_COMPLIANCE_ALERT_INTERVAL=PT1H
```

Thresholds must be unique, strictly descending, between 1 and 3650 days, and contain 1–32 values. Invalid configuration fails service construction rather than silently reverting to defaults.

Distinct alerts exist for warranty end, manufacturer support end, license end, publisher support end and third-party support end. Database alert deduplication ensures one event per kind/record/due-date/threshold.

Explicit expiration transitions emit the corresponding warranty/license/support events after the contractual end date.

## Versioned evidence history

Every warranty, software-license and support-coverage insert/update writes an append-only compliance revision containing:

- record type/id/version/status;
- proof reference;
- required reason;
- immutable JSON snapshot;
- recorder/timestamp.

This record is separate from the cross-cutting audit journal. It preserves the contractual version and evidence that justified each state. The transverse audit/outbox remains authoritative for platform security/audit events.

## Authorization

PGM-07-E03 adds organization-scoped atomic permissions:

```text
itam.warranty.read
itam.warranty.manage
itam.support_coverage.read
itam.support_coverage.manage
itam.support_catalog.manage
itam.license.read
itam.license.manage
```

The existing `itam.audit.read` permission is reused rather than duplicated. HTTP and CLI resolve the organization from the governed asset/authorization request and run the same scoped RBAC/ABAC guard. No organizational permission is promoted to platform scope merely for implementation convenience.

## API, CLI and Web

OpenAPI 3.1 defines the native E03 operations under `src/applications/server/resources/openapi/itam-compliance.yaml`, including warranties, software licenses, support authorizations, support coverages, warranty types, alerts and version history. Mutations require bounded `Idempotency-Key`; updates use optimistic version/`If-Match`; failures use `application/problem+json`.

The Server CLI exposes the same use cases using local authentication secret files, deterministic text/JSON output and `--dry-run` for mutations. Large request bodies are loaded from JSON files rather than shell-escaped inline payloads.

The browser client provides the same capability gate, CSRF protection, bounded timeout, idempotency and ETag transport. Raw license-key fields are rejected client-side before network submission; the Server still remains authoritative.

## Persistence and migrations

`0023-itam-warranty-support-license` adds PostgreSQL/Oracle structures for:

- nullable `Asset.producer_partner_id` upgrade column;
- warranty-type catalogue;
- warranties;
- software-license contracts;
- support-provider authorizations and normalized scope tables;
- support coverages;
- append-only compliance revisions;
- mutation idempotency and alert deduplication.

Only ITAM-owned references use database foreign keys. Partner, Organization, IAM and RSOT references stay weak and are validated at the application boundary.

`0024-identity-access-itam-compliance-permissions` owns the seven E03 IAM permissions and bootstrap assignment to `system.platform_admin` for upgrade continuity.

### Upgrade

Apply paired migrations in order:

```text
0022 → 0023 → 0024
```

No automatic producer backfill occurs. Existing assets remain valid but cannot pass E03 operational readiness until a governed producer is corrected and the required warranty/license evidence is activated.

### Rollback

Application rollback must first stop E03 writes and verify that reverting the release will not leave E02 operational assets relying exclusively on E03 evidence. Database rollback is then performed in reverse order:

```text
0024 → 0023
```

The rollback scripts are bounded to E03-owned schema/permissions. As with every contractual migration, live PostgreSQL/Oracle apply/verify/rollback must be executed in the target environment before release promotion.

## Security boundary

The implementation deliberately rejects or avoids:

- free-text manufacturers, publishers or support providers;
- raw software activation secrets;
- executable expressions/scripts in contractual data;
- cross-context database foreign keys;
- silent producer backfills;
- silently ignored JSON properties;
- operational asset promotion without verified evidence;
- support coverage after authorization suspension without review;
- alerts inferred from modification timestamps.

`PGM-13-E02` remains a prerequisite for any future feature that persists secret license material.
