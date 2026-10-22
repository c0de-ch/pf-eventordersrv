# Order Service

Event-driven order service: `POST /orders` stores an order, publishes an `order.created` JMS event, and an asynchronous consumer processes it (`CREATED → PROCESSING → PROCESSED`).

## Run

| How | Command |
|-----|---------|
| Full stack | `make up`, then http://localhost:8080 |
| Dev loop | `./mvnw spring-boot:run` (Postgres and Artemis start from `compose.yaml`) |
| Tests | `./mvnw verify` (Testcontainers, needs Docker) |

Requires JDK 25 and Docker. `make help` lists all targets. Host ports: `POSTGRES_PORT` (5432), `ARTEMIS_PORT` (61616), `ARTEMIS_CONSOLE_PORT` (8161).

## Stack

Java 25 · Spring Boot 4.1 · Spring Web MVC · Spring Data JPA · Flyway · PostgreSQL 17 · JMS via ActiveMQ Artemis · Actuator + Micrometer · Testcontainers · Maven wrapper
