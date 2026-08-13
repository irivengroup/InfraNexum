# Domains

InfraNexum bounded contexts are added only from approved Domain Contract Packs and architecture decisions.

Implemented foundations in this release:

- `organization` — organization/subdivision authority and scope lifecycle;
- `identity-local` — local credentials and sessions;
- `identity-access` — IAM users, memberships, groups, roles, permissions and deny-by-default RBAC;
- `rsot` — canonical identity, authority-matrix and context-map foundation for PGM-06-E01.

Bounded contexts exchange identifiers and behavior only through public contracts/ports. Business tables and cross-context foreign keys are forbidden; external identifiers are stored as validated weak references.
