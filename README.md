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
| `ORDERS_CLOCK_OFFSET` | `PT0S` | Shifts the application clock (ISO-8601 duration, e.g. `P30D`, `-PT2H`) for test scenarios. Business timestamps follow it; log and trace timestamps stay real. |
