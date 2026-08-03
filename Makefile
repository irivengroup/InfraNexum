.PHONY: architecture-test architecture-check agent-vet agent-test agent-build java-test verify-foundation verify clean-generated

PYTHON ?= python3
GO ?= go

architecture-test:
	@set -eu; \
	mkdir -p validation/reports; \
	coverage_file="$$(mktemp)"; \
	trap 'rm -f "$$coverage_file"' EXIT; \
	COVERAGE_FILE="$$coverage_file" PYTHONDONTWRITEBYTECODE=1 $(PYTHON) -m coverage run --branch -m unittest discover -s tests/architecture -p 'test_*.py'; \
	COVERAGE_FILE="$$coverage_file" PYTHONDONTWRITEBYTECODE=1 $(PYTHON) -m coverage report --fail-under=98 > validation/reports/architecture-coverage.txt; \
	cat validation/reports/architecture-coverage.txt

architecture-check:
	@mkdir -p validation/reports
	PYTHONDONTWRITEBYTECODE=1 $(PYTHON) -m validation.architecture.cli --root . --policy validation/architecture/policy.json --json-report validation/reports/architecture.json

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

verify-foundation: architecture-test architecture-check agent-vet agent-test agent-build

verify: verify-foundation java-test

clean-generated:
	rm -rf .coverage bin applications/agent/coverage.out applications/agent/coverage-summary.txt
