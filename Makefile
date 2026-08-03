.PHONY: architecture-test architecture-check toolchain-test toolchain-check migration-test migration-check java-contract-smoke agent-vet agent-test agent-build java-test verify-foundation verify clean-generated

PYTHON ?= python3
GO ?= go
JAVAC ?= javac
JAVA ?= java

define PY_COVERAGE
	mkdir -p validation/reports; \
	coverage_file="$$(mktemp)"; \
	trap 'rm -f "$$coverage_file"' EXIT; \
	COVERAGE_FILE="$$coverage_file" PYTHONDONTWRITEBYTECODE=1 $(PYTHON) -m coverage run --branch --source=$(1) -m unittest discover -s $(2) -p 'test_*.py'; \
	COVERAGE_FILE="$$coverage_file" PYTHONDONTWRITEBYTECODE=1 $(PYTHON) -m coverage report --fail-under=98 > $(3); \
	cat $(3)
endef

architecture-test:
	@set -eu; $(call PY_COVERAGE,validation.architecture,tests/architecture,validation/reports/architecture-coverage.txt)

architecture-check:
	@mkdir -p validation/reports
	PYTHONDONTWRITEBYTECODE=1 $(PYTHON) -m validation.architecture.cli --root . --policy validation/architecture/policy.json --json-report validation/reports/architecture.json

toolchain-test:
	@set -eu; $(call PY_COVERAGE,validation.toolchains,tests/toolchains,validation/reports/toolchain-coverage.txt)

toolchain-check:
	@mkdir -p validation/reports
	PYTHONDONTWRITEBYTECODE=1 $(PYTHON) -m validation.toolchains.cli --root . --json-report validation/reports/toolchains.json

migration-test:
	@set -eu; $(call PY_COVERAGE,validation.migrations,tests/migrations,validation/reports/migration-coverage.txt)

migration-check:
	@mkdir -p validation/reports
	PYTHONDONTWRITEBYTECODE=1 $(PYTHON) -m validation.migrations.cli --root distribution/migrations --json-report validation/reports/migrations.json

java-contract-smoke:
	@set -eu; \
	build_dir="$$(mktemp -d)"; \
	trap 'rm -rf "$$build_dir"' EXIT; \
	$(JAVAC) -Xlint:all -Werror -d "$$build_dir" \
		components/core/contracts/src/main/java/io/infranexum/core/contracts/*.java \
		tests/java-contract-smoke/io/infranexum/core/contracts/ContractSmoke.java; \
	$(JAVA) -ea -cp "$$build_dir" io.infranexum.core.contracts.ContractSmoke

agent-vet:
	cd applications/agent && GOTOOLCHAIN=$${GOTOOLCHAIN:-auto} $(GO) vet ./...

agent-test:
	@set -eu; \
	mkdir -p validation/reports; \
	coverage_file="$$(mktemp)"; \
	summary_file="$$(mktemp)"; \
	trap 'rm -f "$$coverage_file" "$$summary_file"' EXIT; \
	cd applications/agent; \
	GOTOOLCHAIN=$${GOTOOLCHAIN:-auto} $(GO) test -race -coverprofile="$$coverage_file" ./...; \
	GOTOOLCHAIN=$${GOTOOLCHAIN:-auto} $(GO) tool cover -func="$$coverage_file" > "$$summary_file"; \
	cat "$$summary_file"; \
	cd ../..; \
	cp "$$summary_file" validation/reports/agent-coverage.txt; \
	$(PYTHON) tools/check_go_coverage.py "$$summary_file" 98

agent-build:
	@set -eu; \
	mkdir -p bin; \
	cd applications/agent; \
	version="$$(cat ../../VERSION)"; \
	GOTOOLCHAIN=$${GOTOOLCHAIN:-auto} CGO_ENABLED=0 $(GO) build -trimpath -ldflags="-s -w -X main.version=$$version" -o ../../bin/infranexum-agent ./cmd/infranexum-agent

java-test:
	./mvnw --batch-mode --no-transfer-progress verify

verify-foundation: architecture-test architecture-check toolchain-test toolchain-check migration-test migration-check java-contract-smoke agent-vet agent-test agent-build

verify: verify-foundation java-test

clean-generated:
	rm -rf .coverage bin validation/reports/*.tmp applications/agent/coverage.out applications/agent/coverage-summary.txt
	find . -type d -name __pycache__ -prune -exec rm -rf {} +
	find . -type f \\( -name '*.pyc' -o -name '*.pyo' \\) -delete
