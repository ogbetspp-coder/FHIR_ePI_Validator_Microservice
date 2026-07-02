.PHONY: help build test run docker docker-run compose demo python-install python-test python-lint vendor vendor-verify clean

SERVICE_POM := service/pom.xml
BASE_URL ?= http://localhost:8080

help: ## Show this help
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-16s\033[0m %s\n", $$1, $$2}'

build: ## Build the service (unit + integration tests)
	mvn -B -f $(SERVICE_POM) verify

test: build ## Alias for build (mvn verify)

run: ## Run the service locally (dev profile)
	mvn -B -f $(SERVICE_POM) spring-boot:run

docker: ## Build the service container image
	docker build -t epi-validator:local service/

docker-run: docker ## Run the service container on :8080
	docker run --rm -p 8080:8080 -m 2g epi-validator:local

compose: ## Start the service via docker compose and wait for readiness
	docker compose up --wait

demo: ## Run the agentic pipeline demo against BASE_URL (default localhost:8080)
	python3 examples/pipeline_demo.py --base-url $(BASE_URL)

python-install: ## Install the Python client (editable, with dev deps)
	python3 -m pip install -e 'clients/python[dev]'

python-test: ## Run Python client tests
	cd clients/python && python3 -m pytest -q

python-lint: ## Lint Python code
	cd clients/python && python3 -m ruff check src tests ../../examples

vendor: ## Re-download IG packages per tools/packages.lock.json (updates pins — deliberate PR only)
	tools/vendor-packages.sh

vendor-verify: ## Verify committed IG packages match the SHA-256 lockfile (offline)
	tools/vendor-packages.sh --verify

clean: ## Remove build outputs
	mvn -B -f $(SERVICE_POM) clean
	rm -rf clients/python/dist clients/python/build
