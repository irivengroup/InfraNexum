SHELL := /bin/bash
.SHELLFLAGS := -eu -o pipefail -c

.PHONY: postgresql-test-schema archive-compatibility-test archive-compatibility-check source-integrity-test source-integrity-check source-integrity-precommit source-integrity-hook-install source-integrity-update source-checksum-update architecture-test architecture-check toolchain-test toolchain-check migration-test migration-check eventing-test eventing-check persistence-test persistence-check capabilities-test capabilities-check entitlements-test entitlements-check audit-test audit-check java-contract-smoke java-eventing-smoke java-audit-smoke java-jdbc-smoke java-jdbc-workers-smoke java-capabilities-smoke java-entitlements-smoke java-entitlement-runtime-smoke java-activation-operations-smoke java-workers-smoke agent-vet agent-test agent-build web-test web-smoke web-verify java-module-verify java-test verify-foundation verify clean-generated

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

verify-foundation: source-integrity-test source-integrity-check archive-compatibility-test archive-compatibility-check architecture-test architecture-check toolchain-test toolchain-check migration-test migration-check eventing-test eventing-check persistence-test persistence-check capabilities-test capabilities-check entitlements-test entitlements-check audit-test audit-check java-contract-smoke java-eventing-smoke java-audit-smoke java-jdbc-smoke java-jdbc-workers-smoke java-capabilities-smoke java-entitlements-smoke java-entitlement-runtime-smoke java-activation-operations-smoke java-workers-smoke agent-vet agent-test agent-build web-verify

verify: verify-foundation java-test

clean-generated:
	rm -rf .coverage bin $(REPORT_ROOT)/*.tmp $(AGENT_ROOT)/coverage.out $(AGENT_ROOT)/coverage-summary.txt
	find . -type d -name __pycache__ -prune -exec rm -rf {} +
	find . -type f \( -name '*.pyc' -o -name '*.pyo' \) -delete
