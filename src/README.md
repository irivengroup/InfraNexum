# Source tree

All InfraNexum implementation sources are grouped below this directory.

```text
src/
├── applications/   # deployable Server, Web and Agent processes
├── components/     # Core, domain and adapter components
├── engines/        # native DNS/DHCP engines when implemented
├── provisioning/   # PXE, TFTP, imaging and provisioning sources
├── installer/      # transactional installer sources
├── deployment/     # deployment traits, roles and topology sources
├── distribution/   # migrations, release manifests and distribution sources
├── sdk/            # public SDK sources and contracts
├── tests/          # cross-component and offline smoke tests
├── validation/     # Architecture-as-Code and contract gates
└── tools/          # build and validation support scripts
```

Repository-level build descriptors, documentary baselines, ownership, toolchain locks and CI workflows remain at the repository root because they orchestrate the complete source tree rather than belonging to one implementation component.

The Architecture-as-Code gate `CHECK-ARCH-SRC-002` rejects implementation source files placed outside `src/`.
