# Order Service

Event-driven order service: `POST /orders` stores an order, publishes an `order.created` JMS event, and an asynchronous consumer processes it (`CREATED → PROCESSING → PROCESSED`).

## Run

| How | Command |
|-----|---------|
| Full stack | `make up`, then http://localhost:8080 |
| Dev loop | `./mvnw spring-boot:run` (Postgres and Artemis start from `compose.yaml`) |
| Tests | `./mvnw verify` (Testcontainers, needs Docker) |

Requires JDK 25 and Docker. `make help` lists all targets. Host ports: `POSTGRES_PORT` (5432), `ARTEMIS_PORT` (61616), `ARTEMIS_CONSOLE_PORT` (8161).

## API

| Request | Response |
|---------|----------|
| `POST /orders` with `{"customerId": "12345", "amount": 99.99, "currency": "CHF"}` (`currency` optional, default `CHF`) | `201`, `Location: /orders/{id}`, order with status history |
| `GET /orders/{id}` | `200` order with status history, `404` if unknown |
| `GET /orders?customerId=&status=&page=&size=` | `200` page of orders, newest first |

```
curl -s -X POST localhost:8080/orders -H 'Content-Type: application/json' -d '{"customerId":"12345","amount":99.99}'
curl -s localhost:8080/orders/1
```

Errors are RFC 9457 problem details (`application/problem+json`); validation failures list `errors[{field, message}]`, illegal status transitions and concurrent updates return `409`. OpenAPI at `/v3/api-docs`, Swagger UI at `/swagger-ui.html`.

## Messaging

Creating an order writes the order and an `OrderCreated` event into the same database transaction (transactional outbox). A relay publishes pending events to the JMS queue `order.created` every second, oldest first, with `FOR UPDATE SKIP LOCKED` so several instances can run. Delivery is at least once; consumers must be idempotent.

| Message | Content |
|---------|---------|
| Body (JSON text) | `{"orderId": 1, "customerId": "12345", "amount": 99.90, "currency": "CHF", "occurredAt": "2026-10-22T10:00:00Z"}` |
| Properties | `_type=OrderCreated`, `eventId=<outbox id>`, `JMSCorrelationID=<orderId>` |

## Processing

A consumer reads `order.created` in a transacted JMS session, moves the order to `PROCESSING`, simulates work and sets `PROCESSED`. Customer ids starting with `FAIL-` fail permanently; ids starting with `FLAKY-` fail once and succeed on the in-process retry. The broker policy lives in `deploy/artemis/broker.xml` (mounted by compose and used by the tests); after changing it, recreate the broker with `docker compose up -d --force-recreate -V artemis`.

| Situation | Behaviour |
|-----------|-----------|
| Processing throws | Session rollback; the broker redelivers after 1 s and 2 s (3 attempts), then moves the message to `DLQ.order.created` |
| Message in `DLQ.order.created` | Dead-letter consumer sets the order to `FAILED` with a reason |
| Transient error (`FLAKY-`) | Retried twice in-process with 200 ms delay, no redelivery |
| Duplicate delivery | Order already final: acknowledged and skipped |
| Unknown order id | Logged and acknowledged (poison message) |
| Crash while processing | Order stays `PROCESSING`; the redelivered message resumes it |
| Broker down | API keeps accepting orders; events wait in the outbox |

## Stack

Java 25 · Spring Boot 4.1 · Spring Web MVC · Spring Data JPA · Flyway · PostgreSQL 17 · JMS via ActiveMQ Artemis · Actuator + Micrometer · Testcontainers · Maven wrapper

## Configuration

| Variable | Default | Purpose |
|----------|---------|---------|
| `DB_URL` | `jdbc:postgresql://localhost:5432/orders` | JDBC URL |
| `DB_USER`, `DB_PASSWORD` | `orders`, `orders` | Database credentials |
| `ARTEMIS_URL` | `tcp://localhost:61616` | Broker URL |
| `ARTEMIS_USER`, `ARTEMIS_PASSWORD` | `artemis`, `artemis` | Broker credentials |
| `SERVER_PORT` | `8080` | HTTP port |
| `ORDERS_MESSAGING_ORDER_CREATED_QUEUE` | `order.created` | Queue for `OrderCreated` events |
| `ORDERS_MESSAGING_ORDER_CREATED_DLQ` | `DLQ.order.created` | Dead-letter queue of `order.created` |
| `ORDERS_WORKER_ENABLED` | `true` | Run the consumers in this instance |
| `ORDERS_PROCESSING_DELAY` | `PT0.5S` | Simulated processing time |
| `ORDERS_PROCESSING_FAILING_CUSTOMER_PREFIX` | `FAIL-` | Customer ids whose orders fail permanently |
| `ORDERS_PROCESSING_FLAKY_CUSTOMER_PREFIX` | `FLAKY-` | Customer ids whose orders fail once |
| `ORDERS_OUTBOX_RELAY_ENABLED` | `true` | Run the outbox relay in this instance |
| `ORDERS_OUTBOX_RELAY_POLL_INTERVAL` | `PT1S` | Delay between relay runs |
| `ORDERS_OUTBOX_RELAY_BATCH_SIZE` | `50` | Events per relay transaction |
| `ORDERS_CLOCK_OFFSET` | `PT0S` | Shifts the application clock (ISO-8601 duration, e.g. `P30D`, `-PT2H`) for test scenarios. Business timestamps follow it; log and trace timestamps stay real. |
