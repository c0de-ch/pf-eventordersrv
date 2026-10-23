.DEFAULT_GOAL := help
MVN ?= ./mvnw -q -B
COMPOSE ?= docker compose --profile app

help: ## Show targets
	@grep -E '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  %-12s %s\n", $$1, $$2}'

build: ## Package the application without tests
	$(MVN) -DskipTests package

test: ## Unit tests
	$(MVN) test

verify: ## Unit and integration tests (needs Docker)
	$(MVN) verify

run: ## Run locally; Postgres and Artemis start from compose.yaml
	./mvnw spring-boot:run

image: ## Build the application image (order-service:local)
	docker build -t order-service:local .

up: ## Build the image and start the full stack with Docker Compose
	$(COMPOSE) up -d --build --wait

smoke: ## End-to-end smoke test against the running stack (BASE_URL overrides the target)
	scripts/smoke-test.sh $(BASE_URL)

down: ## Stop the stack
	$(COMPOSE) down

logs: ## Follow application logs
	$(COMPOSE) logs -f app

clean: ## Remove build output
	$(MVN) clean

.PHONY: help build test verify run image up smoke down logs clean
