.DEFAULT_GOAL := help
MVN ?= ./mvnw -q -B
COMPOSE ?= docker compose --profile app
IMAGE ?= order-service
UI_IMAGE ?= order-ui
IMAGE_TAG ?= $(shell git rev-parse --short HEAD 2>/dev/null || echo local)
KIND_CLUSTER ?= orders
KUBE_CONTEXT ?= kind-$(KIND_CLUSTER)
NAMESPACE ?= orders
HELM = helm --kube-context $(KUBE_CONTEXT)
KUBECTL = kubectl --context $(KUBE_CONTEXT)

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

image: ## Build the application image (order-service:local and :<git sha>)
	docker build -t $(IMAGE):local -t $(IMAGE):$(IMAGE_TAG) .

ui-check: ## Lint, test and build the Angular UI (needs Node 24)
	cd ui && npm ci && npx ng lint && npx ng test --watch=false && npx ng build

image-ui: ## Build the UI image (order-ui:local and :<git sha>)
	docker build -t $(UI_IMAGE):local -t $(UI_IMAGE):$(IMAGE_TAG) ui

up: ## Build the image and start the full stack with Docker Compose
	$(COMPOSE) up -d --build --wait

smoke: ## End-to-end smoke test against the running stack (BASE_URL / UI_URL override the targets)
	UI_URL=$(or $(UI_URL),http://localhost:8081) scripts/smoke-test.sh $(BASE_URL)

down: ## Stop the stack
	$(COMPOSE) down

logs: ## Follow application logs
	$(COMPOSE) logs -f app

clean: ## Remove build output
	$(MVN) clean

lint: ## Lint the Helm charts and check the broker policy copy
	helm lint charts/order-service charts/order-ui charts/dev-infra
	helm lint charts/order-service -f charts/order-service/values-kind.yaml
	helm lint charts/order-service -f charts/order-service/values-prod.example.yaml
	helm lint charts/order-ui -f charts/order-ui/values-kind.yaml
	cmp deploy/artemis/broker.xml charts/dev-infra/files/broker.xml

kind-up: ## Create the kind cluster "orders" (your current kubectl context stays active)
	@kind get clusters 2>/dev/null | grep -qx $(KIND_CLUSTER) && echo "kind cluster $(KIND_CLUSTER) exists" || { \
	  prev=$$(kubectl config current-context 2>/dev/null || true); \
	  kind create cluster --name $(KIND_CLUSTER) --config deploy/kind/cluster.yaml; \
	  [ -n "$$prev" ] && kubectl config use-context "$$prev" >/dev/null || true; }

kind-deploy: image image-ui ## Load the images, install all charts, run helm tests and the smoke test
	kind load docker-image $(IMAGE):$(IMAGE_TAG) $(UI_IMAGE):$(IMAGE_TAG) --name $(KIND_CLUSTER)
	$(HELM) upgrade --install dev-infra charts/dev-infra -n $(NAMESPACE) --create-namespace --wait --timeout 5m
	$(HELM) upgrade --install order-service charts/order-service -n $(NAMESPACE) \
	  -f charts/order-service/values-kind.yaml --set image.tag=$(IMAGE_TAG) --wait --timeout 5m
	$(HELM) upgrade --install order-ui charts/order-ui -n $(NAMESPACE) \
	  -f charts/order-ui/values-kind.yaml --set image.tag=$(IMAGE_TAG) --wait --timeout 3m
	$(HELM) test order-service -n $(NAMESPACE)
	$(HELM) test order-ui -n $(NAMESPACE)
	UI_URL=http://localhost:30081 scripts/smoke-test.sh http://localhost:30080

kind-logs: ## Follow the application logs in kind
	$(KUBECTL) -n $(NAMESPACE) logs deploy/order-service -f

kind-down: ## Delete the kind cluster "orders"
	kind delete cluster --name $(KIND_CLUSTER)

.PHONY: help build test verify run image ui-check image-ui up smoke down logs clean lint kind-up kind-deploy kind-logs kind-down
