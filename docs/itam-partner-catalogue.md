# PGM-07-E01 — ITAM Partner catalogue

## Purpose

InfraNexum owns one canonical `Partner` aggregate for organizations that participate in the ITAM lifecycle. Manufacturer, publisher, supplier and support catalogues are role-filtered views of that same aggregate; they are not separate authorities.

## Roles and lifecycle

Allowed roles are `manufacturer`, `software_publisher`, `supplier`, `third_party_support_provider`, `integrator` and `recycler`. A partner starts in `DRAFT`, can be submitted to `PENDING_APPROVAL`, then authorized to `ACTIVE`. Active partners can be suspended or retired; suspended partners can be re-authorized or retired. `RETIRED` is terminal. Selection by downstream ITAM workflows requires an active partner whose validity period covers the effective date.

## Governance and integrity

Each partner is governed by an Organization and optionally one of its Subdivisions. These identifiers are weak cross-context references validated through Organization public ports; the ITAM schema contains no foreign key into Organization or IAM tables. Code uniqueness, normalized identity tokens, external identifiers, contacts, aliases and accreditation periods are validated before persistence. Mutations use optimistic aggregate versions and idempotency keys. `itam.partners.max` is enforced from the effective capability quota plan.

## Authorization

The six atomic permissions are `itam.partner.read`, `itam.partner.create`, `itam.partner.update`, `itam.partner.approve`, `itam.partner.suspend` and `itam.audit.read`. HTTP partner routes use controller-scoped authorization because the governing Organization is known only after request binding or aggregate lookup. `ScopedAuthorizationGuard` applies RBAC and, when enabled, ABAC against that real scope and rejects unsupported obligations fail-closed.

## HTTP contract

The OpenAPI source of truth is `src/applications/server/resources/openapi/itam-partners.yaml`. The implemented surface is:

- `GET /api/v1/itam/partners` with bounded filters and stable cursor pagination;
- `POST /api/v1/itam/partners`;
- `POST /api/v1/itam/partners/{partnerId}/submit-approval`;
- `POST /api/v1/itam/partners/{partnerId}/authorize`;
- `POST /api/v1/itam/partners/{partnerId}/suspend`.

Creates and lifecycle mutations require `Idempotency-Key`; lifecycle mutations additionally require `If-Match: "ver-N"`. Errors are returned as `application/problem+json`. The Server endpoint is disabled unless `INFRANEXUM_ITAM_PARTNER_API_ENABLED=true`.

## CLI and Web

The Server CLI exposes `itam partner list`, `create`, `submit-approval`, `authorize` and `suspend`. Authentication secrets are read from an absolute password file; mutations support `--dry-run`; structured output is available with `--output json`.

The browser client `ItamPartnerClient` is available only when the public runtime configuration explicitly publishes `itamPartnersEnabled=true`. Mutations require the same-origin CSRF token, an 8–200 character idempotency key and, for transitions, a positive optimistic version. Transition reasons follow the Server contract of 2–1024 printable characters.

## Persistence and rollback

Migration `0019-itam-partner-foundation` owns the ITAM Partner tables for PostgreSQL and Oracle. Migration `0020-identity-access-itam-partner-permissions` owns the IAM permission seeds and bootstrap grants. Both migrations ship paired verification and rollback assets. A rollback must first ensure no later migration or downstream ITAM feature depends on these contracts, then execute `0020` rollback before `0019`; production rollback additionally requires the project-standard database backup and restoration controls.

## Explicitly deferred

Warranty records, licence/support contracts, entitlement coverages and downstream asset matching are not part of `PGM-07-E01`. They must consume the canonical Partner catalogue rather than recreate a competing partner authority.
