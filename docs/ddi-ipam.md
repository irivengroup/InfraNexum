# DDI / IPAM — PGM-08-E01

InfraNexum models **DDI** as the parent component. This increment implements only its **IPAM** bounded context; DNS and DHCP remain separate roadmap increments.

## Authority and hierarchy

The governed hierarchy is `VRF → network block/prefix/subnet → pool → address`. VLAN/VXLAN is a logical-network association rather than a parent of IP address space. Organisation/Subdivision, RSOT and DCIM identifiers remain weak cross-context references validated by application ports; DDI-owned references are enforced internally.

## Allocation and conflict invariants

- Network CIDRs cannot overlap inside the same Organisation and VRF while active.
- Pool ranges must be contained by an active subnet and cannot overlap another active pool of that subnet.
- An active/reserved address is unique inside its VRF.
- Automatic allocation requires a pool. The pool is locked transactionally and advances a durable cursor; allocation never scans an unbounded IPv6 prefix.
- Network-overlap decisions are serialized by the routing environment and use fixed-width sortable address keys with indexed SQL predicates rather than bounded catalogue reads.
- Reservations are explicit address records with `RESERVED` lifecycle status; releases remain durable history rather than destructive deletion.

## Interfaces

The Server publishes `/api/v1/ddi/ipam/...` with 15 OpenAPI 3.1 operations, `problem+json`, idempotency keys and optimistic `If-Match` mutations. The Server CLI exposes the same governed operations under `ddi ipam` with password files, JSON output and `--dry-run` for mutations.

The Web workspace is a first-level `#/ddi` administration route. It provides VRF, VLAN, Network, Pool and Address catalogues. Organisation → Subdivision → Site and all cross-context identifiers are selected from real catalogues; no entity UUID is entered as free text.

## Database evolution

- `0030-ddi-ipam-foundation`: PostgreSQL/Oracle IPAM storage, overlap keys/indexes, idempotency and lifecycle constraints.
- `0031-identity-access-ddi-ipam-permissions`: 12 Organisation-scoped atomic permissions and platform-administrator bootstrap grants.

Rollback scripts remove only objects owned by these migrations. Live apply/verify/rollback remains a target-environment promotion gate.
