# InfraNexum source-root layout

## Decision

All implementation source directories and files are grouped below the repository-level `src/` directory.

The canonical product spaces remain unchanged and are now rooted as follows:

```text
src/applications
src/components
src/engines
src/provisioning
src/installer
src/deployment
src/distribution
src/sdk
```

Tests, executable validation gates and build-support scripts are also source material and therefore reside below `src/tests`, `src/validation` and `src/tools`.

Generated evidence and binaries are deliberately kept outside the source tree:

```text
artifacts/validation
bin
```

## Compatibility impact

- Maven modules are addressed through `src/...` paths from the root reactor.
- Go and Web commands execute from `src/applications/agent` and `src/applications/web`.
- Python validation packages retain their `validation.*` import names through `PYTHONPATH=src`.
- Database migrations are addressed from `src/distribution/migrations`.
- CI cache keys and working directories use the new canonical paths.
- Component identifiers such as `applications.server` and `components.core.events` are unchanged; only physical repository paths changed.

## Enforcement

Architecture-as-Code validates the configured `source_root` and blocks:

- a missing or escaping source root;
- any implementation source file outside `src/`;
- missing structural spaces below `src/`;
- source files violating component language boundaries.

This is a repository-layout change only. Public runtime APIs, event contracts, database logical models and package namespaces are unchanged.
