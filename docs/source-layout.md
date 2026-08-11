# InfraNexum repository layout

## Decision

InfraNexum uses the same shallow top-level organization established for the legacy 2.0 architecture. Product spaces are direct children of the repository root; there is no enclosing `src/` directory.

```text
applications/    # deployable Server, Web and Agent processes
components/      # Core, domain and adapter components
engines/         # native DNS/DHCP engines when implemented
provisioning/    # PXE, TFTP, imaging and provisioning
installer/       # transactional installer
deployment/     # roles, traits and topologies
distribution/   # migrations and release/source manifests
sdk/            # public SDKs and contracts
tests/          # cross-component and offline smoke tests
validation/     # Architecture-as-Code and blocking contract gates
tools/          # build and validation support
```

Generated evidence and binaries remain outside canonical source spaces:

```text
artifacts/validation/
bin/
```

## Java module layout

Java packages and Maven artifact identifiers are unchanged, but the physical Maven source roots are intentionally shortened:

```text
applications/server/main/io/infranexum/...
applications/server/test/io/infranexum/...
components/core/<module>/main/io/infranexum/...
components/core/<module>/test/io/infranexum/...
components/adapters/jdbc/main/io/infranexum/adapters/persistence/jdbc/...
components/adapters/jdbc/test/io/infranexum/adapters/persistence/jdbc/...
```

The parent `pom.xml` defines `main`, `test` and `resources` as inherited Maven source roots. The physical adapter directory is shortened to `components/adapters/jdbc`, while the logical component ID `components.adapters.persistence-jdbc`, Java package `io.infranexum.adapters.persistence.jdbc` and Maven artifact `infranexum-adapter-persistence-jdbc` remain stable.

## Path-length safety contract

`validation.source_integrity` enforces two repository-wide invariants:

- repository-relative canonical paths: **120 characters maximum**;
- individual path components: **80 characters maximum**.

The release archive also uses the short root prefix `infranexum-<version>` instead of a descriptive suffix. With the current longest canonical path at 116 characters, this leaves substantial room for Windows checkout and extraction prefixes while staying below legacy `MAX_PATH` environments.

The gate is fail-closed: a future source, test, fixture or build-support file that exceeds either limit blocks the commit candidate and CI before language-specific builds begin.

## Compatibility impact

- Maven modules are addressed directly from `applications/...` and `components/...`.
- Go and Web commands execute from `applications/agent` and `applications/web`.
- Python validation packages retain their `validation.*` import names through `PYTHONPATH=.`.
- Database migrations are addressed from `distribution/migrations`.
- CI cache keys and working directories use the shallow paths.
- Runtime APIs, Java packages, event contracts, database schemas and component logical identifiers are unchanged.

## Enforcement

Architecture-as-Code treats the repository root as the canonical source root but only permits code inside explicitly governed spaces. Source Integrity additionally validates inventory completeness, Git tracking, exact staged snapshots, Git-blob checksums, Java import closure, Maven reactor closure, case-insensitive collisions and the path-length budget.
