SHELL := /bin/bash
.SHELLFLAGS := -eu -o pipefail -c

.PHONY: architecture-test architecture-check toolchain-test toolchain-check migration-test migration-check eventing-test eventing-check persistence-test persistence-check capabilities-test capabilities-check entitlements-test entitlements-check audit-test audit-check java-contract-smoke java-eventing-smoke java-audit-smoke java-jdbc-smoke java-capabilities-smoke java-entitlements-smoke java-entitlement-runtime-smoke java-activation-operations-smoke agent-vet agent-test agent-build web-test web-smoke web-verify java-test verify-foundation verify clean-generated

PYTHON ?= python3
GO ?= go
JAVAC ?= javac
JAVA ?= java

SOURCE_ROOT := src
APPLICATION_ROOT := $(SOURCE_ROOT)/applications
COMPONENT_ROOT := $(SOURCE_ROOT)/components
TEST_ROOT := $(SOURCE_ROOT)/tests
VALIDATION_ROOT := $(SOURCE_ROOT)/validation
TOOLS_ROOT := $(SOURCE_ROOT)/tools
MIGRATION_ROOT := $(SOURCE_ROOT)/distribution/migrations
REPORT_ROOT := artifacts/validation
REPORT_ROOT_ABS := $(abspath $(REPORT_ROOT))
AGENT_ROOT := $(APPLICATION_ROOT)/agent
WEB_ROOT := $(APPLICATION_ROOT)/web

# Python validation packages live below src/. PYTHONPATH keeps their import
# names stable while enforcing the repository-wide single source root.
define PY_COVERAGE
	mkdir -p $(REPORT_ROOT); \
	coverage_file="$$(mktemp)"; \
	trap 'rm -f "$$coverage_file"' EXIT; \
	COVERAGE_FILE="$$coverage_file" PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=$(SOURCE_ROOT) $(PYTHON) -m coverage run --branch --source=$(1) -m unittest discover -s $(2) -p 'test_*.py'; \
	COVERAGE_FILE="$$coverage_file" PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=$(SOURCE_ROOT) $(PYTHON) -m coverage report --fail-under=98 > $(3); \
	cat $(3)
endef

architecture-test:
	@$(call PY_COVERAGE,validation.architecture,$(TEST_ROOT)/architecture,$(REPORT_ROOT)/architecture-coverage.txt)

architecture-check:
	@mkdir -p $(REPORT_ROOT)
	PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=$(SOURCE_ROOT) $(PYTHON) -m validation.architecture.cli --root . --policy $(VALIDATION_ROOT)/architecture/policy.json --json-report $(REPORT_ROOT)/architecture.json

toolchain-test:
	@$(call PY_COVERAGE,validation.toolchains,$(TEST_ROOT)/toolchains,$(REPORT_ROOT)/toolchain-coverage.txt)

toolchain-check:
	@mkdir -p $(REPORT_ROOT)
	PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=$(SOURCE_ROOT) $(PYTHON) -m validation.toolchains.cli --root . --json-report $(REPORT_ROOT)/toolchains.json

migration-test:
	@$(call PY_COVERAGE,validation.migrations,$(TEST_ROOT)/migrations,$(REPORT_ROOT)/migration-coverage.txt)

migration-check:
	@mkdir -p $(REPORT_ROOT)
	PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=$(SOURCE_ROOT) $(PYTHON) -m validation.migrations.cli --root $(MIGRATION_ROOT) --json-report $(REPORT_ROOT)/migrations.json

eventing-test:
	@$(call PY_COVERAGE,validation.eventing,$(TEST_ROOT)/eventing,$(REPORT_ROOT)/eventing-coverage.txt)

eventing-check:
	@mkdir -p $(REPORT_ROOT)
	PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=$(SOURCE_ROOT) $(PYTHON) -m validation.eventing.cli --root . --json-report $(REPORT_ROOT)/eventing.json

persistence-test:
	@$(call PY_COVERAGE,validation.persistence,$(TEST_ROOT)/persistence,$(REPORT_ROOT)/persistence-coverage.txt)

persistence-check:
	@mkdir -p $(REPORT_ROOT)
	PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=$(SOURCE_ROOT) $(PYTHON) -m validation.persistence.cli --root . --json-report $(REPORT_ROOT)/persistence.json

capabilities-test:
	@$(call PY_COVERAGE,validation.capabilities,$(TEST_ROOT)/capabilities,$(REPORT_ROOT)/capabilities-coverage.txt)

capabilities-check:
	@mkdir -p $(REPORT_ROOT)
	PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=$(SOURCE_ROOT) $(PYTHON) -m validation.capabilities.cli --root . --json-report $(REPORT_ROOT)/capabilities.json

entitlements-test:
	@$(call PY_COVERAGE,validation.entitlements,$(TEST_ROOT)/entitlements,$(REPORT_ROOT)/entitlements-coverage.txt)

entitlements-check:
	@mkdir -p $(REPORT_ROOT)
	PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=$(SOURCE_ROOT) $(PYTHON) -m validation.entitlements.cli --root . --json-report $(REPORT_ROOT)/entitlements.json

audit-test:
	@$(call PY_COVERAGE,validation.audit,$(TEST_ROOT)/audit,$(REPORT_ROOT)/audit-coverage.txt)

audit-check:
	@mkdir -p $(REPORT_ROOT)
	PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=$(SOURCE_ROOT) $(PYTHON) -m validation.audit.cli --root . --json-report $(REPORT_ROOT)/audit.json

java-contract-smoke:
	@build_dir="$$(mktemp -d)"; \
	trap 'rm -rf "$$build_dir"' EXIT; \
	$(JAVAC) -Xlint:all -Werror -d "$$build_dir" \
		$(COMPONENT_ROOT)/core/contracts/src/main/java/io/infranexum/core/contracts/*.java \
		$(TEST_ROOT)/java-contract-smoke/io/infranexum/core/contracts/ContractSmoke.java; \
	$(JAVA) -ea -cp "$$build_dir" io.infranexum.core.contracts.ContractSmoke

java-eventing-smoke:
	@build_dir="$$(mktemp -d)"; \
	trap 'rm -rf "$$build_dir"' EXIT; \
	$(JAVAC) -Xlint:all -Werror -d "$$build_dir" \
		$(COMPONENT_ROOT)/core/contracts/src/main/java/io/infranexum/core/contracts/*.java \
		$(COMPONENT_ROOT)/core/events/src/main/java/io/infranexum/core/events/*.java \
		$(TEST_ROOT)/java-eventing-smoke/io/infranexum/core/events/EventingSmoke.java; \
	$(JAVA) -ea -cp "$$build_dir" io.infranexum.core.events.EventingSmoke

java-audit-smoke:
	@build_dir="$$(mktemp -d)"; \
	trap 'rm -rf "$$build_dir"' EXIT; \
	$(JAVAC) -Xlint:all -Werror -d "$$build_dir" \
		$(COMPONENT_ROOT)/core/contracts/src/main/java/io/infranexum/core/contracts/*.java \
		$(COMPONENT_ROOT)/core/audit/src/main/java/io/infranexum/core/audit/*.java \
		$(TEST_ROOT)/java-audit-smoke/io/infranexum/core/audit/AuditSmoke.java; \
	$(JAVA) -ea -cp "$$build_dir" io.infranexum.core.audit.AuditSmoke

java-jdbc-smoke:
	@build_dir="$$(mktemp -d)"; \
	trap 'rm -rf "$$build_dir"' EXIT; \
	$(JAVAC) -Xlint:all -Werror -d "$$build_dir" \
		$(COMPONENT_ROOT)/core/contracts/src/main/java/io/infranexum/core/contracts/*.java \
		$(COMPONENT_ROOT)/core/events/src/main/java/io/infranexum/core/events/*.java \
		$(COMPONENT_ROOT)/core/capabilities/src/main/java/io/infranexum/core/capabilities/*.java \
		$(COMPONENT_ROOT)/core/entitlements/src/main/java/io/infranexum/core/entitlements/*.java \
		$(COMPONENT_ROOT)/core/audit/src/main/java/io/infranexum/core/audit/*.java \
		$(COMPONENT_ROOT)/adapters/persistence-jdbc/src/main/java/io/infranexum/adapters/persistence/jdbc/*.java \
		$(COMPONENT_ROOT)/adapters/persistence-jdbc/src/test/java/io/infranexum/adapters/persistence/jdbc/JdbcAdapterSmoke.java \
		$(COMPONENT_ROOT)/adapters/persistence-jdbc/src/test/java/io/infranexum/adapters/persistence/jdbc/JdbcAuditJournalSmoke.java; \
	$(JAVA) -ea -cp "$$build_dir" io.infranexum.adapters.persistence.jdbc.JdbcAdapterSmoke; \
	$(JAVA) -ea -cp "$$build_dir" io.infranexum.adapters.persistence.jdbc.JdbcAuditJournalSmoke

java-capabilities-smoke:
	@build_dir="$$(mktemp -d)"; \
	trap 'rm -rf "$$build_dir"' EXIT; \
	$(JAVAC) -Xlint:all -Werror -d "$$build_dir" \
		$(COMPONENT_ROOT)/core/capabilities/src/main/java/io/infranexum/core/capabilities/*.java \
		$(TEST_ROOT)/java-capabilities-smoke/io/infranexum/core/capabilities/CapabilitiesSmoke.java; \
	$(JAVA) -ea -cp "$$build_dir" io.infranexum.core.capabilities.CapabilitiesSmoke \
		$(COMPONENT_ROOT)/core/capabilities/src/main/resources/io/infranexum/core/capabilities/capability-catalog.csv \
		$(COMPONENT_ROOT)/core/capabilities/src/main/resources/io/infranexum/core/capabilities/quota-catalog.csv

java-entitlements-smoke:
	@build_dir="$$(mktemp -d)"; \
	trap 'rm -rf "$$build_dir"' EXIT; \
	$(JAVAC) -Xlint:all -Werror -d "$$build_dir" \
		$(COMPONENT_ROOT)/core/contracts/src/main/java/io/infranexum/core/contracts/*.java \
		$(COMPONENT_ROOT)/core/capabilities/src/main/java/io/infranexum/core/capabilities/*.java \
		$(COMPONENT_ROOT)/core/entitlements/src/main/java/io/infranexum/core/entitlements/*.java \
		$(TEST_ROOT)/java-entitlements-smoke/io/infranexum/core/entitlements/EntitlementsSmoke.java; \
	$(JAVA) -ea -cp "$$build_dir" io.infranexum.core.entitlements.EntitlementsSmoke \
		$(COMPONENT_ROOT)/core/capabilities/src/main/resources/io/infranexum/core/capabilities/capability-catalog.csv \
		$(COMPONENT_ROOT)/core/capabilities/src/main/resources/io/infranexum/core/capabilities/quota-catalog.csv

java-entitlement-runtime-smoke:
	@build_dir="$$(mktemp -d)"; \
	trap 'rm -rf "$$build_dir"' EXIT; \
	$(JAVAC) -Xlint:all -Werror -d "$$build_dir" \
		$(COMPONENT_ROOT)/core/contracts/src/main/java/io/infranexum/core/contracts/*.java \
		$(COMPONENT_ROOT)/core/events/src/main/java/io/infranexum/core/events/*.java \
		$(COMPONENT_ROOT)/core/capabilities/src/main/java/io/infranexum/core/capabilities/*.java \
		$(COMPONENT_ROOT)/core/entitlements/src/main/java/io/infranexum/core/entitlements/*.java \
		$(COMPONENT_ROOT)/core/audit/src/main/java/io/infranexum/core/audit/*.java \
		$(COMPONENT_ROOT)/adapters/persistence-jdbc/src/main/java/io/infranexum/adapters/persistence/jdbc/*.java \
		$(TEST_ROOT)/java-entitlement-runtime-smoke/io/infranexum/core/entitlements/EntitlementRuntimeSmoke.java; \
	$(JAVA) -ea -cp "$$build_dir" io.infranexum.core.entitlements.EntitlementRuntimeSmoke \
		$(COMPONENT_ROOT)/core/capabilities/src/main/resources/io/infranexum/core/capabilities/capability-catalog.csv \
		$(COMPONENT_ROOT)/core/capabilities/src/main/resources/io/infranexum/core/capabilities/quota-catalog.csv

java-activation-operations-smoke:
	@build_dir="$$(mktemp -d)"; \
	trap 'rm -rf "$$build_dir"' EXIT; \
	$(JAVAC) -Xlint:all -Werror -d "$$build_dir" \
		$(COMPONENT_ROOT)/core/contracts/src/main/java/io/infranexum/core/contracts/*.java \
		$(COMPONENT_ROOT)/core/capabilities/src/main/java/io/infranexum/core/capabilities/*.java \
		$(COMPONENT_ROOT)/core/entitlements/src/main/java/io/infranexum/core/entitlements/*.java \
		$(COMPONENT_ROOT)/adapters/persistence-jdbc/src/main/java/io/infranexum/adapters/persistence/jdbc/JdbcPersistenceException.java \
		$(COMPONENT_ROOT)/adapters/persistence-jdbc/src/main/java/io/infranexum/adapters/persistence/jdbc/FileIntegrityProofStore.java \
		$(TEST_ROOT)/java-activation-operations-smoke/io/infranexum/core/entitlements/ActivationOperationsSmoke.java; \
	$(JAVA) -ea -cp "$$build_dir" io.infranexum.core.entitlements.ActivationOperationsSmoke

agent-vet:
	cd $(AGENT_ROOT) && GOTOOLCHAIN=$${GOTOOLCHAIN:-auto} $(GO) vet ./...

agent-test:
	@mkdir -p $(REPORT_ROOT); \
	coverage_file="$$(mktemp)"; \
	summary_file="$$(mktemp)"; \
	trap 'rm -f "$$coverage_file" "$$summary_file"' EXIT; \
	cd $(AGENT_ROOT); \
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
	node --test --experimental-test-coverage --test-coverage-lines=98 --test-coverage-branches=98 --test-coverage-functions=98 --test-coverage-include='runtime/config.mjs' --test-coverage-include='runtime/logger.mjs' --test-coverage-include='runtime/static-assets.mjs' --test-coverage-include='runtime/web-application.mjs' runtime/tests/*.test.mjs \
		| tee "$(REPORT_ROOT_ABS)/web-coverage.txt"

web-smoke:
	@mkdir -p $(REPORT_ROOT); \
	cd $(WEB_ROOT); \
	node runtime/tests/smoke.mjs | tee "$(REPORT_ROOT_ABS)/web-smoke.json"

web-verify: web-test web-smoke

java-test:
	./mvnw --batch-mode --no-transfer-progress verify

verify-foundation: architecture-test architecture-check toolchain-test toolchain-check migration-test migration-check eventing-test eventing-check persistence-test persistence-check capabilities-test capabilities-check entitlements-test entitlements-check audit-test audit-check java-contract-smoke java-eventing-smoke java-audit-smoke java-jdbc-smoke java-capabilities-smoke java-entitlements-smoke java-entitlement-runtime-smoke java-activation-operations-smoke agent-vet agent-test agent-build web-verify

verify: verify-foundation java-test

clean-generated:
	rm -rf .coverage bin $(REPORT_ROOT)/*.tmp $(AGENT_ROOT)/coverage.out $(AGENT_ROOT)/coverage-summary.txt
	find . -type d -name __pycache__ -prune -exec rm -rf {} +
	find . -type f \( -name '*.pyc' -o -name '*.pyo' \) -delete
