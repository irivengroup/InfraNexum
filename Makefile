SHELL := /bin/bash
.SHELLFLAGS := -eu -o pipefail -c

.PHONY: sdk-test sdk-check api-contract-test api-contract-check compose-contract-test compose-config compose-build compose-up compose-down compose-smoke compose-backup compose-restore compose-rollback compose-reset compose-logs postgresql-test-schema archive-compatibility-test archive-compatibility-check source-integrity-test source-integrity-check source-integrity-precommit source-integrity-hook-install source-integrity-update source-checksum-update architecture-test architecture-check toolchain-test toolchain-check migration-test migration-check eventing-test eventing-check persistence-test persistence-check capabilities-test capabilities-check entitlements-test entitlements-check audit-test audit-check java-contract-smoke java-eventing-smoke java-audit-smoke java-jdbc-smoke java-jdbc-workers-smoke java-capabilities-smoke java-api-capability-smoke java-entitlements-smoke java-entitlement-runtime-smoke java-activation-operations-smoke java-workers-smoke java-observability-smoke java-rsot-smoke java-schema-registry-smoke java-itam-partner-smoke java-itam-asset-smoke java-itam-compliance-smoke java-dcim-facility-smoke java-dcim-physical-smoke java-ddi-ipam-smoke java-integrations-smoke java-policy-smoke agent-vet agent-test agent-build web-test web-smoke web-verify java-module-verify java-test verify-foundation verify clean-generated

PYTHON ?= python3
GO ?= go
JAVAC ?= javac
JAVA ?= java

REPOSITORY_ROOT := .
PRODUCT_ROOT := src
APPLICATION_ROOT := $(PRODUCT_ROOT)/applications
COMPONENT_ROOT := $(PRODUCT_ROOT)/components
TEST_ROOT := tests
VALIDATION_ROOT := validation
TOOLS_ROOT := tools
MIGRATION_ROOT := src/distribution/migrations
REPORT_ROOT := artifacts/validation
REPORT_ROOT_ABS := $(abspath $(REPORT_ROOT))
AGENT_ROOT := $(APPLICATION_ROOT)/agent
WEB_ROOT := $(APPLICATION_ROOT)/web
SDK_PYTHON_ROOT := $(PRODUCT_ROOT)/sdk/python
SDK_PYTHON_PACKAGE := $(SDK_PYTHON_ROOT)/infranexum_connector_sdk
SDK_SOURCE_DATE_EPOCH ?= 315532800
DOCKER_ROOT := docker
DOCKER_COMPOSE_SH := ./$(DOCKER_ROOT)/dev-compose.sh

# JDBC adapters implement ports owned by these bounded contexts. Keep the
# offline smoke compilation aligned with the Maven dependency graph so adding
# an adapter cannot silently break the mandatory javac smoke targets.
JDBC_DOMAIN_SOURCES := \
	$(COMPONENT_ROOT)/domains/identity-local/main/io/infranexum/identity/local/domain/*.java \
	$(COMPONENT_ROOT)/domains/identity-local/main/io/infranexum/identity/local/ports/*.java \
	$(COMPONENT_ROOT)/domains/identity-access/main/io/infranexum/identity/access/domain/*.java \
	$(COMPONENT_ROOT)/domains/identity-access/main/io/infranexum/identity/access/ports/*.java \
	$(COMPONENT_ROOT)/domains/organization/main/io/infranexum/organization/domain/*.java \
	$(COMPONENT_ROOT)/domains/organization/main/io/infranexum/organization/ports/*.java \
	$(COMPONENT_ROOT)/domains/rsot/main/io/infranexum/rsot/domain/*.java \
	$(COMPONENT_ROOT)/domains/rsot/main/io/infranexum/rsot/ports/*.java \
	$(COMPONENT_ROOT)/domains/itam/main/io/infranexum/itam/partner/domain/*.java \
	$(COMPONENT_ROOT)/domains/itam/main/io/infranexum/itam/partner/application/*.java \
	$(COMPONENT_ROOT)/domains/itam/main/io/infranexum/itam/partner/ports/*.java \
	$(COMPONENT_ROOT)/domains/itam/main/io/infranexum/itam/asset/domain/*.java \
	$(COMPONENT_ROOT)/domains/itam/main/io/infranexum/itam/asset/application/*.java \
	$(COMPONENT_ROOT)/domains/itam/main/io/infranexum/itam/asset/ports/*.java \
	$(COMPONENT_ROOT)/domains/itam/main/io/infranexum/itam/compliance/domain/*.java \
	$(COMPONENT_ROOT)/domains/itam/main/io/infranexum/itam/compliance/application/*.java \
	$(COMPONENT_ROOT)/domains/itam/main/io/infranexum/itam/compliance/ports/*.java \
	$(COMPONENT_ROOT)/domains/dcim/main/io/infranexum/dcim/facility/domain/*.java \
	$(COMPONENT_ROOT)/domains/dcim/main/io/infranexum/dcim/facility/application/*.java \
	$(COMPONENT_ROOT)/domains/dcim/main/io/infranexum/dcim/facility/ports/*.java \
	$(COMPONENT_ROOT)/domains/dcim/main/io/infranexum/dcim/physical/domain/*.java \
	$(COMPONENT_ROOT)/domains/dcim/main/io/infranexum/dcim/physical/ports/*.java \
	$(COMPONENT_ROOT)/domains/ddi/main/io/infranexum/ddi/ipam/domain/*.java \
	$(COMPONENT_ROOT)/domains/ddi/main/io/infranexum/ddi/ipam/application/*.java \
	$(COMPONENT_ROOT)/domains/ddi/main/io/infranexum/ddi/ipam/ports/*.java \
	$(COMPONENT_ROOT)/domains/integrations/main/io/infranexum/integrations/*.java


# Python validation packages live at repository root outside the src/ product boundary.
# PYTHONPATH=. keeps validation imports deterministic on every runner.
define PY_COVERAGE
	mkdir -p $(REPORT_ROOT); \
	coverage_file="$$(mktemp)"; \
	trap 'rm -f "$$coverage_file"' EXIT; \
	COVERAGE_FILE="$$coverage_file" PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=$(REPOSITORY_ROOT) $(PYTHON) -m coverage run --branch --source=$(1) -m unittest discover -s $(2) -p 'test_*.py'; \
	COVERAGE_FILE="$$coverage_file" PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=$(REPOSITORY_ROOT) $(PYTHON) -m coverage report --fail-under=98 > $(3); \
	cat $(3)
endef



sdk-test: source-integrity-check
	@mkdir -p $(REPORT_ROOT); \
	coverage_file="$$(mktemp)"; \
	trap 'rm -f "$$coverage_file"' EXIT; \
	COVERAGE_FILE="$$coverage_file" PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=$(SDK_PYTHON_ROOT) $(PYTHON) -m coverage run --branch --source=$(SDK_PYTHON_PACKAGE) -m unittest discover -s $(TEST_ROOT)/sdk_python -p 'test_*.py'; \
	COVERAGE_FILE="$$coverage_file" PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=$(SDK_PYTHON_ROOT) $(PYTHON) -m coverage report --fail-under=98 > $(REPORT_ROOT)/connector-sdk-coverage.txt; \
	cat $(REPORT_ROOT)/connector-sdk-coverage.txt

sdk-check:
	@build_dir="$$(mktemp -d)"; \
	trap 'rm -rf "$$build_dir"' EXIT; \
	PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=$(SDK_PYTHON_ROOT) $(PYTHON) -c 'import json, pathlib, tomllib; from infranexum_connector_sdk.version import SDK_VERSION; pyproject=tomllib.loads(pathlib.Path("$(SDK_PYTHON_ROOT)/pyproject.toml").read_text(encoding="utf-8")); schema=json.loads(pathlib.Path("$(SDK_PYTHON_PACKAGE)/schemas/connector-manifest.schema.json").read_text(encoding="utf-8")); assert pyproject["project"]["version"] == SDK_VERSION; assert schema["$$id"] == "urn:infranexum:schema:connector-manifest:v1"; assert schema["additionalProperties"] is False'; \
	cp -R $(SDK_PYTHON_ROOT) "$$build_dir/source-a"; \
	cp -R $(SDK_PYTHON_ROOT) "$$build_dir/source-b"; \
	mkdir -p "$$build_dir/wheels-a" "$$build_dir/wheels-b"; \
	SOURCE_DATE_EPOCH=$(SDK_SOURCE_DATE_EPOCH) $(PYTHON) -m pip wheel --disable-pip-version-check --no-deps --no-build-isolation "$$build_dir/source-a" --wheel-dir "$$build_dir/wheels-a" >/dev/null; \
	SOURCE_DATE_EPOCH=$(SDK_SOURCE_DATE_EPOCH) $(PYTHON) -m pip wheel --disable-pip-version-check --no-deps --no-build-isolation "$$build_dir/source-b" --wheel-dir "$$build_dir/wheels-b" >/dev/null; \
	wheel_a="$$(find "$$build_dir/wheels-a" -maxdepth 1 -type f -name 'infranexum_connector_sdk-1.0.0-*.whl' -print -quit)"; \
	wheel_b="$$(find "$$build_dir/wheels-b" -maxdepth 1 -type f -name 'infranexum_connector_sdk-1.0.0-*.whl' -print -quit)"; \
	test -n "$$wheel_a" -a -n "$$wheel_b"; \
	cmp "$$wheel_a" "$$wheel_b"; \
	PYTHONDONTWRITEBYTECODE=1 PYTHONPATH="$$wheel_a" $(PYTHON) -c 'from infranexum_connector_sdk import SDK_VERSION, manifest_schema; schema = manifest_schema(); assert SDK_VERSION == "1.0.0"; assert schema["$$id"] == "urn:infranexum:schema:connector-manifest:v1"'


api-contract-test: source-integrity-check
	@$(call PY_COVERAGE,validation.api_contracts,$(TEST_ROOT)/api_contracts,$(REPORT_ROOT)/api-contracts-coverage.txt)

api-contract-check:
	@mkdir -p $(REPORT_ROOT)
	PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=$(REPOSITORY_ROOT) $(PYTHON) -m validation.api_contracts.cli --root . --json-report $(REPORT_ROOT)/api-contracts.json --product-spec $(REPORT_ROOT)/openapi-product.yaml

archive-compatibility-test:
	@$(call PY_COVERAGE,validation.archive_compatibility,$(TEST_ROOT)/archive_compatibility,$(REPORT_ROOT)/archive-compatibility-coverage.txt)

archive-compatibility-check:
	@mkdir -p $(REPORT_ROOT); \
	archive="$$(mktemp --suffix=.zip)"; \
	trap 'rm -f "$$archive"' EXIT; \
	version="$$(cat VERSION)"; \
	git archive --format=zip --prefix="infranexum-$$version/" HEAD -o "$$archive"; \
	PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=$(REPOSITORY_ROOT) $(PYTHON) -m validation.archive_compatibility.cli --archive "$$archive" --repository-root . --json-report $(REPORT_ROOT)/archive-compatibility.json

source-integrity-test:
	@$(call PY_COVERAGE,validation.source_integrity,$(TEST_ROOT)/source_integrity,$(REPORT_ROOT)/source-integrity-coverage.txt)

source-integrity-check:
	@mkdir -p $(REPORT_ROOT)
	PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=$(REPOSITORY_ROOT) $(PYTHON) -m validation.source_integrity.cli --root . --json-report $(REPORT_ROOT)/source-integrity.json $(if $(SOURCE_INTEGRITY_REQUIRE_GIT),--require-git-tracking,) $(if $(SOURCE_INTEGRITY_REQUIRE_STAGED),--require-staged-snapshot,) $(if $(SOURCE_INTEGRITY_REQUIRE_CHECKSUMS),--require-git-checksums,)

source-integrity-precommit:
	@coverage_file="$$(mktemp)"; report_file="$$(mktemp)"; \
	trap 'rm -f "$$coverage_file" "$$report_file"' EXIT; \
	COVERAGE_FILE="$$coverage_file" PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=$(REPOSITORY_ROOT) $(PYTHON) -m coverage run --branch --source=validation.source_integrity -m unittest discover -s $(TEST_ROOT)/source_integrity -p 'test_*.py'; \
	COVERAGE_FILE="$$coverage_file" PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=$(REPOSITORY_ROOT) $(PYTHON) -m coverage report --fail-under=98 > "$$report_file"; \
	cat "$$report_file"
	PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=$(REPOSITORY_ROOT) $(PYTHON) -m validation.source_integrity.cli --root . --require-git-tracking --require-staged-snapshot --require-git-checksums
	git diff --cached --check

source-integrity-hook-install:
	git config core.hooksPath .githooks
	test "$$(git config --get core.hooksPath)" = ".githooks"

source-integrity-update:
	PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=$(REPOSITORY_ROOT) $(PYTHON) -m validation.source_integrity.cli --root . --update-inventory

source-checksum-update:
	PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=$(REPOSITORY_ROOT) $(PYTHON) -m validation.source_integrity.cli --root . --update-git-checksums

architecture-test: source-integrity-check
	@$(call PY_COVERAGE,validation.architecture,$(TEST_ROOT)/architecture,$(REPORT_ROOT)/architecture-coverage.txt)

architecture-check:
	@mkdir -p $(REPORT_ROOT)
	PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=$(REPOSITORY_ROOT) $(PYTHON) -m validation.architecture.cli --root . --policy $(VALIDATION_ROOT)/architecture/policy.json --json-report $(REPORT_ROOT)/architecture.json

toolchain-test: source-integrity-check
	@$(call PY_COVERAGE,validation.toolchains,$(TEST_ROOT)/toolchains,$(REPORT_ROOT)/toolchain-coverage.txt)

toolchain-check:
	@mkdir -p $(REPORT_ROOT)
	PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=$(REPOSITORY_ROOT) $(PYTHON) -m validation.toolchains.cli --root . --json-report $(REPORT_ROOT)/toolchains.json

migration-test: source-integrity-check
	@$(call PY_COVERAGE,validation.migrations,$(TEST_ROOT)/migrations,$(REPORT_ROOT)/migration-coverage.txt)

migration-check:
	@mkdir -p $(REPORT_ROOT)
	PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=$(REPOSITORY_ROOT) $(PYTHON) -m validation.migrations.cli --root $(MIGRATION_ROOT) --json-report $(REPORT_ROOT)/migrations.json

eventing-test: source-integrity-check
	@$(call PY_COVERAGE,validation.eventing,$(TEST_ROOT)/eventing,$(REPORT_ROOT)/eventing-coverage.txt)

eventing-check:
	@mkdir -p $(REPORT_ROOT)
	PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=$(REPOSITORY_ROOT) $(PYTHON) -m validation.eventing.cli --root . --json-report $(REPORT_ROOT)/eventing.json

persistence-test: source-integrity-check persistence-check
	@$(call PY_COVERAGE,validation.persistence,$(TEST_ROOT)/persistence,$(REPORT_ROOT)/persistence-coverage.txt)

persistence-check:
	@mkdir -p $(REPORT_ROOT)
	PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=$(REPOSITORY_ROOT) $(PYTHON) -m validation.persistence.cli --root . --json-report $(REPORT_ROOT)/persistence.json

capabilities-test: source-integrity-check
	@$(call PY_COVERAGE,validation.capabilities,$(TEST_ROOT)/capabilities,$(REPORT_ROOT)/capabilities-coverage.txt)

capabilities-check:
	@mkdir -p $(REPORT_ROOT)
	PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=$(REPOSITORY_ROOT) $(PYTHON) -m validation.capabilities.cli --root . --json-report $(REPORT_ROOT)/capabilities.json

entitlements-test: source-integrity-check
	@$(call PY_COVERAGE,validation.entitlements,$(TEST_ROOT)/entitlements,$(REPORT_ROOT)/entitlements-coverage.txt)

entitlements-check:
	@mkdir -p $(REPORT_ROOT)
	PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=$(REPOSITORY_ROOT) $(PYTHON) -m validation.entitlements.cli --root . --json-report $(REPORT_ROOT)/entitlements.json

audit-test: source-integrity-check
	@$(call PY_COVERAGE,validation.audit,$(TEST_ROOT)/audit,$(REPORT_ROOT)/audit-coverage.txt)

audit-check:
	@mkdir -p $(REPORT_ROOT)
	PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=$(REPOSITORY_ROOT) $(PYTHON) -m validation.audit.cli --root . --json-report $(REPORT_ROOT)/audit.json

java-contract-smoke:
	@build_dir="$$(mktemp -d)"; \
	trap 'rm -rf "$$build_dir"' EXIT; \
	$(JAVAC) -Xlint:all -Werror -d "$$build_dir" \
		$(COMPONENT_ROOT)/core/contracts/main/io/infranexum/core/contracts/*.java \
		$(TEST_ROOT)/java-contract-smoke/io/infranexum/core/contracts/ContractSmoke.java; \
	$(JAVA) -ea -cp "$$build_dir" io.infranexum.core.contracts.ContractSmoke

java-eventing-smoke:
	@build_dir="$$(mktemp -d)"; \
	trap 'rm -rf "$$build_dir"' EXIT; \
	$(JAVAC) -Xlint:all -Werror -d "$$build_dir" \
		$(COMPONENT_ROOT)/core/contracts/main/io/infranexum/core/contracts/*.java \
		$(COMPONENT_ROOT)/core/events/main/io/infranexum/core/events/*.java \
		$(TEST_ROOT)/java-eventing-smoke/io/infranexum/core/events/EventingSmoke.java; \
	$(JAVA) -ea -cp "$$build_dir" io.infranexum.core.events.EventingSmoke

java-workers-smoke:
	@build_dir="$$(mktemp -d)"; \
	trap 'rm -rf "$$build_dir"' EXIT; \
	$(JAVAC) -Xlint:all -Werror -d "$$build_dir" \
		$(COMPONENT_ROOT)/core/contracts/main/io/infranexum/core/contracts/*.java \
		$(COMPONENT_ROOT)/core/events/main/io/infranexum/core/events/*.java \
		$(COMPONENT_ROOT)/core/workers/main/io/infranexum/core/workers/*.java \
		$(TEST_ROOT)/java-workers-smoke/io/infranexum/core/workers/WorkersSmoke.java; \
	$(JAVA) -ea -cp "$$build_dir" io.infranexum.core.workers.WorkersSmoke

java-rsot-smoke:
	@build_dir="$$(mktemp -d)"; \
	trap 'rm -rf "$$build_dir"' EXIT; \
	$(JAVAC) -Xlint:all -Werror -d "$$build_dir" \
		$(COMPONENT_ROOT)/core/contracts/main/io/infranexum/core/contracts/*.java \
		$(COMPONENT_ROOT)/domains/rsot/main/io/infranexum/rsot/domain/*.java \
		$(COMPONENT_ROOT)/domains/rsot/main/io/infranexum/rsot/ports/*.java \
		$(COMPONENT_ROOT)/domains/rsot/main/io/infranexum/rsot/application/*.java \
		$(TEST_ROOT)/java-rsot-smoke/io/infranexum/rsot/RsotFoundationSmoke.java; \
	$(JAVA) -ea -cp "$$build_dir" io.infranexum.rsot.RsotFoundationSmoke

java-schema-registry-smoke:
	@build_dir="$$(mktemp -d)"; \
	trap 'rm -rf "$$build_dir"' EXIT; \
	$(JAVAC) -Xlint:all -Werror -d "$$build_dir" \
		$(COMPONENT_ROOT)/core/contracts/main/io/infranexum/core/contracts/*.java \
		$(COMPONENT_ROOT)/core/events/main/io/infranexum/core/events/*.java \
		$(COMPONENT_ROOT)/core/audit/main/io/infranexum/core/audit/*.java \
		$(COMPONENT_ROOT)/core/compatibility/main/io/infranexum/core/compatibility/*.java \
		$(TEST_ROOT)/java-schema-registry-smoke/io/infranexum/core/compatibility/SchemaRegistrySmoke.java; \
	$(JAVA) -ea -cp "$$build_dir" io.infranexum.core.compatibility.SchemaRegistrySmoke

java-itam-partner-smoke:
	@build_dir="$$(mktemp -d)"; \
	trap 'rm -rf "$$build_dir"' EXIT; \
	$(JAVAC) -Xlint:all -Werror -d "$$build_dir" \
		$(COMPONENT_ROOT)/core/contracts/main/io/infranexum/core/contracts/*.java \
		$(COMPONENT_ROOT)/core/events/main/io/infranexum/core/events/*.java \
		$(COMPONENT_ROOT)/domains/itam/main/io/infranexum/itam/partner/domain/*.java \
		$(COMPONENT_ROOT)/domains/itam/main/io/infranexum/itam/partner/ports/*.java \
		$(COMPONENT_ROOT)/domains/itam/main/io/infranexum/itam/partner/application/*.java \
		$(TEST_ROOT)/java-itam-partner-smoke/io/infranexum/itam/partner/ItamPartnerSmoke.java; \
	$(JAVA) -ea -cp "$$build_dir" io.infranexum.itam.partner.ItamPartnerSmoke

java-itam-asset-smoke:
	@build_dir="$$(mktemp -d)"; \
	trap 'rm -rf "$$build_dir"' EXIT; \
	$(JAVAC) -Xlint:all -Werror -d "$$build_dir" \
		$(COMPONENT_ROOT)/core/contracts/main/io/infranexum/core/contracts/*.java \
		$(COMPONENT_ROOT)/core/events/main/io/infranexum/core/events/*.java \
		$(COMPONENT_ROOT)/domains/itam/main/io/infranexum/itam/asset/domain/*.java \
		$(COMPONENT_ROOT)/domains/itam/main/io/infranexum/itam/asset/ports/*.java \
		$(COMPONENT_ROOT)/domains/itam/main/io/infranexum/itam/asset/application/*.java \
		$(TEST_ROOT)/java-itam-asset-smoke/io/infranexum/itam/asset/ItamAssetSmoke.java; \
	$(JAVA) -ea -cp "$$build_dir" io.infranexum.itam.asset.ItamAssetSmoke

java-itam-compliance-smoke:
	@build_dir="$$(mktemp -d)"; \
	trap 'rm -rf "$$build_dir"' EXIT; \
	$(JAVAC) -Xlint:all -Werror -d "$$build_dir" \
		$(COMPONENT_ROOT)/core/contracts/main/io/infranexum/core/contracts/*.java \
		$(COMPONENT_ROOT)/core/events/main/io/infranexum/core/events/*.java \
		$(COMPONENT_ROOT)/domains/itam/main/io/infranexum/itam/asset/domain/*.java \
		$(COMPONENT_ROOT)/domains/itam/main/io/infranexum/itam/asset/ports/*.java \
		$(COMPONENT_ROOT)/domains/itam/main/io/infranexum/itam/asset/application/*.java \
		$(COMPONENT_ROOT)/domains/itam/main/io/infranexum/itam/compliance/domain/*.java \
		$(COMPONENT_ROOT)/domains/itam/main/io/infranexum/itam/compliance/ports/*.java \
		$(COMPONENT_ROOT)/domains/itam/main/io/infranexum/itam/compliance/application/*.java \
		$(TEST_ROOT)/java-itam-compliance-smoke/io/infranexum/itam/compliance/ItamComplianceSmoke.java; \
	$(JAVA) -ea -cp "$$build_dir" io.infranexum.itam.compliance.ItamComplianceSmoke

java-dcim-facility-smoke:
	@build_dir="$$(mktemp -d)"; \
	trap 'rm -rf "$$build_dir"' EXIT; \
	$(JAVAC) -Xlint:all -Werror -d "$$build_dir" \
		$(COMPONENT_ROOT)/core/contracts/main/io/infranexum/core/contracts/*.java \
		$(COMPONENT_ROOT)/core/events/main/io/infranexum/core/events/*.java \
		$(COMPONENT_ROOT)/domains/dcim/main/io/infranexum/dcim/facility/domain/*.java \
		$(COMPONENT_ROOT)/domains/dcim/main/io/infranexum/dcim/facility/ports/*.java \
		$(COMPONENT_ROOT)/domains/dcim/main/io/infranexum/dcim/facility/application/*.java \
		$(TEST_ROOT)/java-dcim-facility-smoke/io/infranexum/dcim/facility/DcimFacilitySmoke.java; \
	$(JAVA) -ea -cp "$$build_dir" io.infranexum.dcim.facility.DcimFacilitySmoke

java-dcim-physical-smoke:
	@set -eu; out="$$(mktemp -d)"; trap 'rm -rf "$$out"' EXIT; \
	find $(PRODUCT_ROOT)/components/core/contracts/main $(PRODUCT_ROOT)/components/core/events/main $(PRODUCT_ROOT)/components/domains/dcim/main/io/infranexum/dcim/physical -name '*.java' -print > "$$out/sources"; \
	printf '%s\n' $(TEST_ROOT)/java-dcim-physical-smoke/io/infranexum/dcim/physical/DcimPhysicalSmoke.java >> "$$out/sources"; \
	javac -Xlint:all -Werror -d "$$out/classes" @"$$out/sources"; \
	java -cp "$$out/classes" io.infranexum.dcim.physical.DcimPhysicalSmoke

java-ddi-ipam-smoke:
	@set -eu; out="$$(mktemp -d)"; trap 'rm -rf "$$out"' EXIT; \
	find $(PRODUCT_ROOT)/components/core/contracts/main $(PRODUCT_ROOT)/components/core/events/main $(PRODUCT_ROOT)/components/domains/ddi/main/io/infranexum/ddi/ipam -name '*.java' -print > "$$out/sources"; \
	printf '%s\n' $(TEST_ROOT)/java-ddi-ipam-smoke/io/infranexum/ddi/ipam/DdiIpamSmoke.java >> "$$out/sources"; \
	javac -Xlint:all -Werror -d "$$out/classes" @"$$out/sources"; \
	java -cp "$$out/classes" io.infranexum.ddi.ipam.DdiIpamSmoke

java-integrations-smoke:
	@set -eu; out="$$(mktemp -d)"; trap 'rm -rf "$$out"' EXIT; \
	find $(PRODUCT_ROOT)/components/core/contracts/main $(PRODUCT_ROOT)/components/core/events/main $(PRODUCT_ROOT)/components/domains/integrations/main -name '*.java' -print > "$$out/sources"; \
	printf '%s\n' $(PRODUCT_ROOT)/applications/server/main/io/infranexum/server/integrations/ImmutableConnectorSyncHandlerRegistry.java $(TEST_ROOT)/java/integrations/io/infranexum/integrations/InMemoryConnectorInboxRepository.java $(TEST_ROOT)/java/integrations/io/infranexum/integrations/InMemoryOutboundNotificationRepository.java $(TEST_ROOT)/java/integrations/io/infranexum/integrations/InMemoryConnectorSyncRepository.java $(TEST_ROOT)/java-integrations-smoke/io/infranexum/integrations/ConnectorRuntimeSmoke.java $(TEST_ROOT)/java-integrations-smoke/io/infranexum/integrations/OutboundNotificationSmoke.java $(TEST_ROOT)/java-integrations-smoke/io/infranexum/integrations/ConnectorGovernanceSmoke.java $(TEST_ROOT)/java-integrations-smoke/io/infranexum/integrations/ConnectorSyncRuntimeSmoke.java $(TEST_ROOT)/java-integrations-smoke/io/infranexum/server/integrations/ConnectorSyncHandlerRegistrySmoke.java >> "$$out/sources"; \
	$(JAVAC) -Xlint:all -Werror -d "$$out/classes" @"$$out/sources"; \
	$(JAVA) -ea -cp "$$out/classes" io.infranexum.integrations.ConnectorRuntimeSmoke; \
	$(JAVA) -ea -cp "$$out/classes" io.infranexum.integrations.OutboundNotificationSmoke; \
	$(JAVA) -ea -cp "$$out/classes" io.infranexum.integrations.ConnectorGovernanceSmoke; \
	$(JAVA) -ea -cp "$$out/classes" io.infranexum.integrations.ConnectorSyncRuntimeSmoke; \
	$(JAVA) -ea -cp "$$out/classes" io.infranexum.server.integrations.ConnectorSyncHandlerRegistrySmoke

java-policy-smoke:
	@build_dir="$$(mktemp -d)"; \
	trap 'rm -rf "$$build_dir"' EXIT; \
	$(JAVAC) -Xlint:all -Werror -d "$$build_dir" \
		$(COMPONENT_ROOT)/core/contracts/main/io/infranexum/core/contracts/*.java \
		$(COMPONENT_ROOT)/core/events/main/io/infranexum/core/events/*.java \
		$(COMPONENT_ROOT)/core/audit/main/io/infranexum/core/audit/*.java \
		$(COMPONENT_ROOT)/domains/identity-access/main/io/infranexum/identity/access/domain/*.java \
		$(COMPONENT_ROOT)/domains/identity-access/main/io/infranexum/identity/access/ports/*.java \
		$(COMPONENT_ROOT)/domains/identity-access/main/io/infranexum/identity/access/application/*.java \
		$(TEST_ROOT)/java/identity-access/io/infranexum/identity/access/IdentityAccessTestRepository.java \
		$(TEST_ROOT)/java/identity-access/io/infranexum/identity/access/AccessPolicyTestRepository.java \
		$(TEST_ROOT)/java-policy-smoke/io/infranexum/identity/access/PolicyAuthorizationSmoke.java; \
	$(JAVA) -ea -cp "$$build_dir" io.infranexum.identity.access.PolicyAuthorizationSmoke

java-observability-smoke:
	@build_dir="$$(mktemp -d)"; \
	trap 'rm -rf "$$build_dir"' EXIT; \
	$(JAVAC) -Xlint:all -Werror -d "$$build_dir" \
		$(APPLICATION_ROOT)/server/main/io/infranexum/server/observability/SensitiveDataRedactor.java \
		$(TEST_ROOT)/java-observability-smoke/io/infranexum/server/observability/ObservabilityRedactionSmoke.java; \
	$(JAVA) -ea -cp "$$build_dir" io.infranexum.server.observability.ObservabilityRedactionSmoke

java-audit-smoke:
	@build_dir="$$(mktemp -d)"; \
	trap 'rm -rf "$$build_dir"' EXIT; \
	$(JAVAC) -Xlint:all -Werror -d "$$build_dir" \
		$(COMPONENT_ROOT)/core/contracts/main/io/infranexum/core/contracts/*.java \
		$(COMPONENT_ROOT)/core/audit/main/io/infranexum/core/audit/*.java \
		$(TEST_ROOT)/java-audit-smoke/io/infranexum/core/audit/AuditSmoke.java; \
	$(JAVA) -ea -cp "$$build_dir" io.infranexum.core.audit.AuditSmoke

java-jdbc-smoke:
	@build_dir="$$(mktemp -d)"; \
	trap 'rm -rf "$$build_dir"' EXIT; \
	$(JAVAC) -Xlint:all -Werror -d "$$build_dir" \
		$(COMPONENT_ROOT)/core/contracts/main/io/infranexum/core/contracts/*.java \
		$(COMPONENT_ROOT)/core/events/main/io/infranexum/core/events/*.java \
		$(COMPONENT_ROOT)/core/workers/main/io/infranexum/core/workers/*.java \
		$(COMPONENT_ROOT)/core/capabilities/main/io/infranexum/core/capabilities/*.java \
		$(COMPONENT_ROOT)/core/entitlements/main/io/infranexum/core/entitlements/*.java \
		$(COMPONENT_ROOT)/core/audit/main/io/infranexum/core/audit/*.java \
		$(COMPONENT_ROOT)/core/compatibility/main/io/infranexum/core/compatibility/*.java \
		$(JDBC_DOMAIN_SOURCES) \
		$(COMPONENT_ROOT)/adapters/jdbc/main/io/infranexum/adapters/persistence/jdbc/*.java \
		$(TEST_ROOT)/java/jdbc/io/infranexum/adapters/persistence/jdbc/JdbcAdapterSmoke.java \
		$(TEST_ROOT)/java/jdbc/io/infranexum/adapters/persistence/jdbc/JdbcAuditJournalSmoke.java; \
	$(JAVA) -ea -cp "$$build_dir" io.infranexum.adapters.persistence.jdbc.JdbcAdapterSmoke; \
	$(JAVA) -ea -cp "$$build_dir" io.infranexum.adapters.persistence.jdbc.JdbcAuditJournalSmoke

java-jdbc-workers-smoke:
	@build_dir="$$(mktemp -d)"; \
	trap 'rm -rf "$$build_dir"' EXIT; \
	$(JAVAC) -Xlint:all -Werror -d "$$build_dir" \
		$(COMPONENT_ROOT)/core/contracts/main/io/infranexum/core/contracts/*.java \
		$(COMPONENT_ROOT)/core/events/main/io/infranexum/core/events/*.java \
		$(COMPONENT_ROOT)/core/workers/main/io/infranexum/core/workers/*.java \
		$(COMPONENT_ROOT)/core/capabilities/main/io/infranexum/core/capabilities/*.java \
		$(COMPONENT_ROOT)/core/entitlements/main/io/infranexum/core/entitlements/*.java \
		$(COMPONENT_ROOT)/core/audit/main/io/infranexum/core/audit/*.java \
		$(COMPONENT_ROOT)/core/compatibility/main/io/infranexum/core/compatibility/*.java \
		$(JDBC_DOMAIN_SOURCES) \
		$(COMPONENT_ROOT)/adapters/jdbc/main/io/infranexum/adapters/persistence/jdbc/*.java \
		$(TEST_ROOT)/java/jdbc/io/infranexum/adapters/persistence/jdbc/JdbcTaskStoreSmoke.java; \
	$(JAVA) -ea -cp "$$build_dir" io.infranexum.adapters.persistence.jdbc.JdbcTaskStoreSmoke

java-capabilities-smoke:
	@build_dir="$$(mktemp -d)"; \
	trap 'rm -rf "$$build_dir"' EXIT; \
	$(JAVAC) -Xlint:all -Werror -d "$$build_dir" \
		$(COMPONENT_ROOT)/core/capabilities/main/io/infranexum/core/capabilities/*.java \
		$(TEST_ROOT)/java-capabilities-smoke/io/infranexum/core/capabilities/CapabilitiesSmoke.java; \
	$(JAVA) -ea -cp "$$build_dir" io.infranexum.core.capabilities.CapabilitiesSmoke \
		$(COMPONENT_ROOT)/core/capabilities/resources/io/infranexum/core/capabilities/capability-catalog.csv \
		$(COMPONENT_ROOT)/core/capabilities/resources/io/infranexum/core/capabilities/quota-catalog.csv

java-api-capability-smoke:
	@build_dir="$$(mktemp -d)"; \
	trap 'rm -rf "$$build_dir"' EXIT; \
	PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=$(REPOSITORY_ROOT) $(PYTHON) \
		$(TEST_ROOT)/java-api-capability-smoke/generate_cases.py \
		$(APPLICATION_ROOT)/server/resources/openapi "$$build_dir/cases.tsv"; \
	$(JAVAC) -Xlint:all -Werror -d "$$build_dir" \
		$(APPLICATION_ROOT)/server/main/io/infranexum/server/platform/ApiCapabilityRequirement.java \
		$(TEST_ROOT)/java-api-capability-smoke/io/infranexum/server/platform/ApiCapabilityRequirementSmoke.java; \
	$(JAVA) -ea -cp "$$build_dir" io.infranexum.server.platform.ApiCapabilityRequirementSmoke "$$build_dir/cases.tsv"

java-entitlements-smoke:
	@build_dir="$$(mktemp -d)"; \
	trap 'rm -rf "$$build_dir"' EXIT; \
	$(JAVAC) -Xlint:all -Werror -d "$$build_dir" \
		$(COMPONENT_ROOT)/core/contracts/main/io/infranexum/core/contracts/*.java \
		$(COMPONENT_ROOT)/core/capabilities/main/io/infranexum/core/capabilities/*.java \
		$(COMPONENT_ROOT)/core/entitlements/main/io/infranexum/core/entitlements/*.java \
		$(TEST_ROOT)/java-entitlements-smoke/io/infranexum/core/entitlements/EntitlementsSmoke.java; \
	$(JAVA) -ea -cp "$$build_dir" io.infranexum.core.entitlements.EntitlementsSmoke \
		$(COMPONENT_ROOT)/core/capabilities/resources/io/infranexum/core/capabilities/capability-catalog.csv \
		$(COMPONENT_ROOT)/core/capabilities/resources/io/infranexum/core/capabilities/quota-catalog.csv

java-entitlement-runtime-smoke:
	@build_dir="$$(mktemp -d)"; \
	trap 'rm -rf "$$build_dir"' EXIT; \
	$(JAVAC) -Xlint:all -Werror -d "$$build_dir" \
		$(COMPONENT_ROOT)/core/contracts/main/io/infranexum/core/contracts/*.java \
		$(COMPONENT_ROOT)/core/events/main/io/infranexum/core/events/*.java \
		$(COMPONENT_ROOT)/core/workers/main/io/infranexum/core/workers/*.java \
		$(COMPONENT_ROOT)/core/capabilities/main/io/infranexum/core/capabilities/*.java \
		$(COMPONENT_ROOT)/core/entitlements/main/io/infranexum/core/entitlements/*.java \
		$(COMPONENT_ROOT)/core/audit/main/io/infranexum/core/audit/*.java \
		$(COMPONENT_ROOT)/core/compatibility/main/io/infranexum/core/compatibility/*.java \
		$(JDBC_DOMAIN_SOURCES) \
		$(COMPONENT_ROOT)/adapters/jdbc/main/io/infranexum/adapters/persistence/jdbc/*.java \
		$(TEST_ROOT)/java-entitlement-runtime-smoke/io/infranexum/core/entitlements/EntitlementRuntimeSmoke.java; \
	$(JAVA) -ea -cp "$$build_dir" io.infranexum.core.entitlements.EntitlementRuntimeSmoke \
		$(COMPONENT_ROOT)/core/capabilities/resources/io/infranexum/core/capabilities/capability-catalog.csv \
		$(COMPONENT_ROOT)/core/capabilities/resources/io/infranexum/core/capabilities/quota-catalog.csv

java-activation-operations-smoke:
	@build_dir="$$(mktemp -d)"; \
	trap 'rm -rf "$$build_dir"' EXIT; \
	$(JAVAC) -Xlint:all -Werror -d "$$build_dir" \
		$(COMPONENT_ROOT)/core/contracts/main/io/infranexum/core/contracts/*.java \
		$(COMPONENT_ROOT)/core/capabilities/main/io/infranexum/core/capabilities/*.java \
		$(COMPONENT_ROOT)/core/entitlements/main/io/infranexum/core/entitlements/*.java \
		$(COMPONENT_ROOT)/adapters/jdbc/main/io/infranexum/adapters/persistence/jdbc/JdbcPersistenceException.java \
		$(COMPONENT_ROOT)/adapters/jdbc/main/io/infranexum/adapters/persistence/jdbc/FileIntegrityProofStore.java \
		$(TEST_ROOT)/java-activation-operations-smoke/io/infranexum/core/entitlements/ActivationOperationsSmoke.java; \
	$(JAVA) -ea -cp "$$build_dir" io.infranexum.core.entitlements.ActivationOperationsSmoke

agent-vet:
	@workspace="$$(mktemp -d)"; \
	trap 'rm -rf "$$workspace"' EXIT; \
	$(PYTHON) $(TOOLS_ROOT)/materialize_go_tests.py --source $(AGENT_ROOT) --tests $(TEST_ROOT)/go/agent --output "$$workspace/agent"; \
	cd "$$workspace/agent"; \
	GOTOOLCHAIN=$${GOTOOLCHAIN:-auto} $(GO) vet ./...

agent-test:
	@mkdir -p $(REPORT_ROOT); \
	workspace="$$(mktemp -d)"; coverage_file="$$(mktemp)"; summary_file="$$(mktemp)"; \
	trap 'rm -rf "$$workspace"; rm -f "$$coverage_file" "$$summary_file"' EXIT; \
	$(PYTHON) $(TOOLS_ROOT)/materialize_go_tests.py --source $(AGENT_ROOT) --tests $(TEST_ROOT)/go/agent --output "$$workspace/agent"; \
	cd "$$workspace/agent"; \
	GOTOOLCHAIN=$${GOTOOLCHAIN:-auto} $(GO) test -race -coverprofile="$$coverage_file" ./...; \
	GOTOOLCHAIN=$${GOTOOLCHAIN:-auto} $(GO) tool cover -func="$$coverage_file" > "$$summary_file"; \
	cat "$$summary_file"; \
	cp "$$summary_file" "$(REPORT_ROOT_ABS)/agent-coverage.txt"; \
	cd "$(CURDIR)"; \
	$(PYTHON) $(TOOLS_ROOT)/check_go_coverage.py "$$summary_file" 98

agent-build:
	@mkdir -p bin; \
	version="$$(cat VERSION)"; \
	cd $(AGENT_ROOT); \
	GOTOOLCHAIN=$${GOTOOLCHAIN:-auto} CGO_ENABLED=0 $(GO) build -trimpath -ldflags="-s -w -X main.version=$$version" -o "$(CURDIR)/bin/infranexum-agent" ./cmd/infranexum-agent

web-test:
	@mkdir -p $(REPORT_ROOT); \
	cd $(WEB_ROOT); \
	node --test --experimental-test-coverage --test-coverage-lines=98 --test-coverage-branches=98 --test-coverage-functions=98 --test-coverage-include='runtime/config.mjs' --test-coverage-include='runtime/logger.mjs' --test-coverage-include='runtime/static-assets.mjs' --test-coverage-include='runtime/web-application.mjs' ../../../tests/web/*.test.mjs \
		| tee "$(REPORT_ROOT_ABS)/web-coverage.txt"

web-smoke:
	@mkdir -p $(REPORT_ROOT); \
	cd $(WEB_ROOT); \
	node ../../../tests/web/smoke.mjs | tee "$(REPORT_ROOT_ABS)/web-smoke.json"

web-verify: web-test web-smoke

compose-contract-test:
	PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=$(REPOSITORY_ROOT) $(PYTHON) -m unittest discover -s $(TEST_ROOT)/deployment -p 'test_*.py'

compose-config:
	$(DOCKER_COMPOSE_SH) config

compose-build:
	$(DOCKER_COMPOSE_SH) build

compose-up:
	$(DOCKER_COMPOSE_SH) up

compose-down:
	$(DOCKER_COMPOSE_SH) down

compose-logs:
	$(DOCKER_COMPOSE_SH) logs $(SERVICES)

compose-smoke:
	$(DOCKER_COMPOSE_SH) smoke

compose-backup:
	$(DOCKER_COMPOSE_SH) backup

compose-restore:
	$(DOCKER_COMPOSE_SH) restore

compose-rollback:
	$(DOCKER_COMPOSE_SH) rollback

compose-reset:
	$(DOCKER_COMPOSE_SH) reset

JAVA_MODULES := \
	src/components/core/contracts \
	src/components/core/events \
	src/components/core/workers \
	src/components/core/capabilities \
	src/components/core/entitlements \
	src/components/core/audit \
	src/components/adapters/jdbc \
	src/applications/server

# Install production artifacts without tests first, then verify each module in isolation.
# This prevents an upstream test/coverage failure from hiding downstream module failures.

postgresql-test-schema:
	@set -eu; \
	: "$${PGHOST:?PGHOST is required}"; \
	: "$${PGUSER:?PGUSER is required}"; \
	: "$${PGDATABASE:?PGDATABASE is required}"; \
	for migration in $$(find src/distribution/migrations -mindepth 2 -maxdepth 2 -name postgresql.sql -print | sort); do \
		echo "Applying $$migration"; \
		psql --set ON_ERROR_STOP=1 --file "$$migration"; \
	done

java-module-verify:
	./mvnw --batch-mode --no-transfer-progress -Dmaven.test.skip=true -Djacoco.skip=true install
	@failures=""; \
	for module in $(JAVA_MODULES); do \
		echo "=== Independent Maven verify: $$module ==="; \
		if ! ./mvnw --batch-mode --no-transfer-progress -pl "$$module" clean verify; then \
			failures="$$failures $$module"; \
		fi; \
	done; \
	if [ -n "$$failures" ]; then \
		echo "Independent Maven module failures:$$failures" >&2; \
		exit 1; \
	fi

java-test:
	./mvnw --batch-mode --no-transfer-progress --fail-at-end verify

verify-foundation: sdk-test sdk-check api-contract-test api-contract-check compose-contract-test source-integrity-test source-integrity-check archive-compatibility-test archive-compatibility-check architecture-test architecture-check toolchain-test toolchain-check migration-test migration-check eventing-test eventing-check persistence-test persistence-check capabilities-test capabilities-check entitlements-test entitlements-check audit-test audit-check java-contract-smoke java-eventing-smoke java-audit-smoke java-jdbc-smoke java-jdbc-workers-smoke java-capabilities-smoke java-api-capability-smoke java-entitlements-smoke java-entitlement-runtime-smoke java-activation-operations-smoke java-workers-smoke java-observability-smoke java-rsot-smoke java-schema-registry-smoke java-itam-partner-smoke java-itam-asset-smoke java-itam-compliance-smoke java-dcim-facility-smoke java-dcim-physical-smoke java-ddi-ipam-smoke java-integrations-smoke java-policy-smoke agent-vet agent-test agent-build web-verify

verify: verify-foundation java-test

clean-generated:
	rm -rf .coverage bin $(REPORT_ROOT)/*.tmp $(AGENT_ROOT)/coverage.out $(AGENT_ROOT)/coverage-summary.txt
	find . -type d -name __pycache__ -prune -exec rm -rf {} +
	find . -type f \( -name '*.pyc' -o -name '*.pyo' \) -delete
