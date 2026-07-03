.PHONY: help build run docker docker-run compose smoke vendor vendor-verify clean

SERVICE_POM := service/pom.xml
BASE_URL ?= http://localhost:8080

help: ## Show this help
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-16s\033[0m %s\n", $$1, $$2}'

build: ## Build the service (unit + integration tests)
	mvn -B -f $(SERVICE_POM) verify

run: ## Run the service locally
	mvn -B -f $(SERVICE_POM) spring-boot:run

docker: ## Build the container image
	docker build -t epi-validator:local -f service/Dockerfile .

docker-run: docker ## Run the container on :8080
	docker run --rm -p 8080:8080 -m 2g epi-validator:local

compose: ## Start via docker compose and wait for readiness
	docker compose up --wait

smoke: ## Validate the two example bundles against BASE_URL
	python3 examples/validate_bundle.py examples/good-bundle.json --epi-type 1 --base-url $(BASE_URL)
	! python3 examples/validate_bundle.py examples/broken-bundle.json --epi-type 1 --base-url $(BASE_URL)

vendor: ## Re-download IG packages per tools/packages.lock.json (updates pins; deliberate PR only)
	tools/vendor-packages.sh

vendor-verify: ## Verify committed IG packages match the SHA-256 lockfile (offline)
	tools/vendor-packages.sh --verify

clean: ## Remove build outputs
	mvn -B -f $(SERVICE_POM) clean
