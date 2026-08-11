# InfraNexum repository layout

## Decision

InfraNexum separates **production solution sources** from repository support content.
Everything required as part of the product implementation, packaging, installation,
upgrade or runtime is contained below `src/`. Tests and engineering-only support
remain outside `src/`.

```text
src/
├── applications/     # deployable Server, Web and Agent processes
├── components/       # Core, domain and adapter components
├── engines/          # native DNS/DHCP engines when implemented
├── provisioning/     # PXE, TFTP, imaging and provisioning product assets
├── installer/        # transactional installer product assets
├── deployment/       # runtime roles, traits and topologies
├── distribution/     # migrations and product/release manifests
└── sdk/              # public SDKs and runtime contracts

tests/                # Java, Go, Web and validation regression tests
validation/           # Architecture-as-Code and blocking contract gates
tools/                # build/validation support utilities
docker/               # Docker Desktop/Compose development and test environment
docs/                 # project documentation
.github/               # hosted CI/CD workflows
```

Root build orchestration files (`pom.xml`, `Makefile`, Maven Wrapper and toolchain
catalogues) stay at repository root because they orchestrate the build rather than
forming part of a deployed InfraNexum runtime. The root `docker/` directory follows
the same repository-support rule: it provides the Docker Desktop/Compose development
and test topology, while production deployment remains standalone bare-metal or VM
and must not depend on a container engine. Container deployment assets remain
forbidden below `src/`. Generated evidence and binaries are also outside canonical
product source spaces:

```text
artifacts/validation/
bin/
```

## Test separation invariant

No test implementation is allowed below `src/`.

Java tests are physically external and each Maven module declares an explicit
repository-level `testSourceDirectory`:

```text
tests/java/server/
tests/java/jdbc/
tests/java/core-audit/
tests/java/core-capabilities/
tests/java/core-contracts/
tests/java/core-entitlements/
tests/java/core-events/
tests/java/core-workers/
```

Go tests remain outside the Agent module under `tests/go/agent/`. Because several
same-package tests intentionally exercise unexported runtime invariants,
`tools/materialize_go_tests.py` builds an isolated temporary Go workspace from the
production module and injects only validated `*_test.go` files for `go vet`, race
and coverage execution. Production sources are never modified by this process.

Web tests live under `tests/web/` and import the runtime explicitly from
`src/applications/web/`.

`CHECK-SOURCE-LAYOUT-003` blocks any Java, Go or Web test source placed below
`src/`. `CHECK-SOURCE-LAYOUT-002` blocks legacy product spaces recreated at the
repository root.

## Java module layout

Java packages and Maven artifact identifiers are unchanged, while physical source
roots remain intentionally short:

```text
src/applications/server/main/io/infranexum/...
src/applications/server/resources/...
src/components/core/<module>/main/io/infranexum/...
src/components/core/<module>/resources/...
src/components/adapters/jdbc/main/io/infranexum/adapters/persistence/jdbc/...
```

Tests are not colocated with these product roots; Maven resolves them from the
repository-level `tests/java/...` directories described above. The physical JDBC
adapter remains `src/components/adapters/jdbc`, while logical component ID
`components.adapters.persistence-jdbc`, Java package
`io.infranexum.adapters.persistence.jdbc` and Maven artifact
`infranexum-adapter-persistence-jdbc` remain stable.

## Path-length safety contract

`validation.source_integrity` enforces repository-wide invariants:

- repository-relative canonical paths: **120 characters maximum**;
- individual path components: **80 characters maximum**;
- release archive root prefix: **32 characters maximum**, exactly
  `infranexum-<version>`.

The `src/` containment therefore does not reintroduce the Windows extraction defect:
Java roots and external test roots are intentionally shallow. The gate is
fail-closed: a future source, test, fixture or build-support file that exceeds the
budget blocks the commit candidate and CI before language-specific builds begin.

## Release-manifest reference safety

Moving `distribution/` below `src/` changes its depth relative to repository-level
validation evidence. Source Integrity therefore also validates that:

- `src/distribution/release-manifest.json` references repository `BASELINE.json`;
- validation report references resolve below repository `artifacts/validation/`;
- the release checksum manifest resolves to
  `artifacts/validation/release-files.sha256`.

These checks prevent a future directory move from silently redirecting evidence to
an unintended path below `src/`.

## Published ZIP compatibility

GitHub Actions remains Unix/Linux-only. Windows compatibility is a property of the
published source ZIP, not of the hosted build environment. The blocking
`archive-compatibility` gate therefore runs on Ubuntu and validates the exact
`git archive` payload before publication. It rejects unsafe or non-canonical member
paths, Windows-reserved names/characters, case-insensitive collisions, symbolic
links, multiple archive roots, project paths over 120 characters, archive members
over 160 characters, and any mismatch between archive files and `git ls-files`.

This keeps Windows extraction safety deterministic without introducing a second CI
platform whose shell, line-ending or native-process semantics could diverge from the
production build pipeline.

## Compatibility impact

- Maven reactor modules are addressed from `src/applications/...` and
  `src/components/...`.
- Java tests remain external through explicit Maven `testSourceDirectory` entries.
- Go and Web production commands execute from `src/applications/agent` and
  `src/applications/web`; their tests remain under repository `tests/`.
- Python validation packages retain their `validation.*` import names through
  `PYTHONPATH=.`.
- Database migrations are addressed from `src/distribution/migrations`.
- Runtime APIs, Java packages, event contracts, database schemas and logical
  component identifiers are unchanged.

## Enforcement

Architecture-as-Code uses `source_root = "src"` for product architecture and
separately permits only declared repository support roots. Source Integrity
validates inventory completeness, Git tracking, exact staged snapshots, Git-blob
checksums, Java import closure, Maven reactor/test-source closure, case-insensitive
collisions, product/test separation, release-manifest reference safety and the
path-length budget.
