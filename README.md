# Order Service

[![ci](https://github.com/c0de-ch/pf-eventordersrv/actions/workflows/ci.yml/badge.svg)](https://github.com/c0de-ch/pf-eventordersrv/actions/workflows/ci.yml) [![kind-e2e](https://github.com/c0de-ch/pf-eventordersrv/actions/workflows/kind-e2e.yml/badge.svg)](https://github.com/c0de-ch/pf-eventordersrv/actions/workflows/kind-e2e.yml)

Event-driven order service: `POST /orders` stores an order, publishes an `order.accepted` JMS event, and an asynchronous consumer processes it (`ACCEPTED → PROCESSING → PROCESSED`, or `FAILED` after exhausted retries). Java 25, Spring Boot 4.1, PostgreSQL, ActiveMQ Artemis, OpenTelemetry; Angular UI, Docker Compose, Helm, kind, GitHub Actions.

## Run

| How | Command |
|-----|---------|
| Full stack in containers | `make up` starts Postgres, Artemis, the Grafana stack, the app (http://localhost:8080) and the UI (http://localhost:8081); `make smoke` checks it end to end; `make down` stops it |
| Dev loop | `./mvnw spring-boot:run` (the infrastructure starts from `compose.yaml`) |
| Tests | `./mvnw verify` (Testcontainers, needs Docker) |
| Kubernetes | `make kind-up kind-deploy` for a local kind cluster; any cluster: `helm upgrade --install order-service charts/order-service -f my-values.yaml` |

`make up` only needs Docker; the Maven path needs JDK 25 or newer. `make help` lists all targets.

## API

| Request | Response |
|---------|----------|
| `POST /orders` with `{"customerId": "12345", "amount": 99.99, "currency": "CHF"}` (`currency` optional, default `CHF`) | `201`, `Location: /orders/{id}`, order with status history |
| `GET /orders/{id}` | `200` order with status history, `404` if unknown |
| `GET /orders?customerId=&status=&page=&size=` | `200` page of orders, newest first |

```
curl -s -X POST localhost:8080/orders -H 'Content-Type: application/json' -d '{"customerId":"12345","amount":99.99}'
```

Errors are RFC 9457 problem details; Swagger UI at `/swagger-ui.html`.

## Design

![Order flow](docs/architecture/02-order-flow.svg)

- Transactional outbox: order, status history and event commit together; a relay publishes to JMS, so a broker outage never blocks the API.
- Status on `orders`, every transition as a row in `order_status_history`; transitions are validated in the aggregate.
- Consumer in two short transactions; broker redelivery (3 attempts), then 30 s in a dead-letter hold queue for inspection, then a consumer that stores the message in `dead_letter` and marks the order `FAILED`; in-process `@Retryable` for transient errors.
- One deployable with role toggles for API and worker; packages by feature, guarded by ArchUnit.
- One trace from REST through the outbox to the consumer, ECS logs with a fixed `event.action` vocabulary, a provisioned Grafana dashboard.

## Assumptions

- An order is a `customerId`, a positive `amount` with at most two decimals and an optional ISO 4217 `currency` (default `CHF`); the initial status is `ACCEPTED`.
- Processing is simulated in-process; customer ids starting with `FAIL-` fail permanently, `FLAKY-` fail once.
- No authentication: the API sits behind a gateway. Delivery is at least once and consumers are idempotent; requests are not deduplicated.
- PostgreSQL only; UTC everywhere, business time from one injectable clock (`ORDERS_CLOCK_OFFSET`).

## Towards production

Authentication with ownership checks, secrets from a secret manager, HA database and broker, outbox purge and DLQ redrive runbook, `Idempotency-Key`, alerts on outbox backlog and DLQ depth, Argo CD promotion by image tag. Details in [docs/operations.md](docs/operations.md).

## Documentation

| Document | Content |
|----------|---------|
| [docs/architecture](docs/architecture/README.md) | Eight diagrams: context, order flow, status model, packages, data model, kind deployment, observability, CI/CD |
| [docs/design.md](docs/design.md) | Decisions with alternatives, assumptions, messaging contract, error handling, Spring features, tests, Kafka instead of JMS |
| [docs/operations.md](docs/operations.md) | Container, Kubernetes, CI/CD and GitOps, observability and dashboard, UI, production path |
| [docs/configuration.md](docs/configuration.md) | Environment variables and defaults |
