# Design

The flow, the decisions behind it, the assumptions, the messaging contract, the error handling and the tests. Diagrams: [architecture](architecture/README.md).

![Order flow](architecture/02-order-flow.svg)

## Decisions

| Decision | Why | Not chosen |
|----------|-----|------------|
| Transactional outbox | Order and event commit together: no event without an order, no order without an event; a broker outage does not block the API | Publish after commit (event lost on a crash in between); XA across JDBC and JMS (heavy, poorly supported) |
| Dead-letter hold and table | A dead letter waits 30 s in `DLQ.order.accepted.hold`, visible in the broker console, then the broker moves it to `DLQ.order.accepted`, where the consumer stores it in `dead_letter` (body, properties, origin, trace id) and fails the order; queryable and the base for a redrive | Consuming at once (nothing to inspect in the broker); holding by delayed redelivery (a scheduled message is invisible to browsing); leaving messages in the queue (invisible to the API); logs only (retention, not queryable per order) |
| Status on `orders`, transitions in `order_status_history` | The current state is one cheap read, the history is a full audit trail; `Order` validates every transition and appends the row | Hibernate Envers (generic `_aud` tables); event sourcing (more than the task needs) |
| `bigint identity` ids | Readable (`/orders/1042`), sortable, native to JPA and Postgres | UUIDs (no enumeration, but unreadable); formatted references (need a sequence anyway) |
| Two short transactions in the consumer | `PROCESSING` is visible while the work runs, no database transaction spans the work, a crash leaves a state the redelivery resumes | One transaction around the whole processing |
| Broker redelivery plus in-process `@Retryable` | Redelivery survives a pod restart and ends in a dead-letter queue; the in-process retry absorbs transient errors without redelivery noise | Client-side retry only |
| Thin event | `orderId`, `customerId`, `amount`, `currency`, `occurredAt`; consumers fetch the rest through `GET /orders/{id}`, so the contract stays stable | Full order snapshot in the event |
| One deployable, roles by toggle | The task simulates the processing in-process; `ORDERS_WORKER_ENABLED` and `ORDERS_OUTBOX_RELAY_ENABLED` split API and worker later from the same image | Separate services from day one |
| Package by feature, guarded by ArchUnit | `order` (domain, service), `order.api`, `order.processing`, `outbox`, `messaging`, `common`; dependencies point inward and the build fails when they do not | Layer packages (`controller`, `service`, `repository`) |
| `ACCEPTED` as initial status | One word for one thing: status `ACCEPTED`, event `OrderAccepted`, queue `order.accepted`, log action `order.accepted`, metric `orders_accepted_total`; the challenge's `CREATED` is an example | `CREATED`, which also collides with the Prometheus `_created` suffix |

## Assumptions

- An order is a `customerId` (opaque string up to 64 characters, not checked against a customer system), an `amount` (positive, at most 2 decimals) and an optional ISO 4217 `currency`, default `CHF`; one currency per order, no conversion.
- The processing is simulated in-process; its outcome is chosen by the customer-id prefix (`FAIL-`, `FLAKY-`) so every path can be demonstrated.
- The API is internal, behind a gateway, and has no authentication; sequential ids are acceptable under that assumption, ownership checks arrive with authentication.
- Delivery is at least once, without ordering guarantees between orders; consumers are idempotent.
- Postgres only, no H2 profile: the tests run the real database through Testcontainers.
- All timestamps are UTC; business time comes from one injectable `Clock` (`ORDERS_CLOCK_OFFSET` shifts it for date scenarios), log and trace timestamps stay real.
- Published outbox rows are kept as an audit trail; purging them is a production step.
- Requests are not deduplicated: a client that retries `POST /orders` after a lost response creates a second order (see [operations](operations.md), towards production).

## Messaging

Creating an order writes the order and an `OrderAccepted` event into the same database transaction (transactional outbox). A relay publishes pending events to the JMS queue `order.accepted` every second, oldest first, with `FOR UPDATE SKIP LOCKED` so several instances can run. Delivery is at least once; consumers must be idempotent.

| Message | Content |
|---------|---------|
| Body (JSON text) | `{"orderId": 1, "customerId": "12345", "amount": 99.90, "currency": "CHF", "occurredAt": "2026-10-22T10:00:00Z"}` |
| Properties | `_type=OrderAccepted`, `eventId=<outbox id>`, `JMSCorrelationID=<orderId>`, `traceparent=<W3C trace context>` |

## Processing and error handling

A consumer reads `order.accepted` in a transacted JMS session, moves the order to `PROCESSING`, simulates work and sets `PROCESSED`. Customer ids starting with `FAIL-` fail permanently; ids starting with `FLAKY-` fail once and succeed on the in-process retry. The broker policy lives in `deploy/artemis/broker.xml` (mounted by compose and used by the tests); after changing it, recreate the broker with `docker compose up -d --force-recreate -V artemis`. Dead letters wait 30 s in `DLQ.order.accepted.hold` before the broker moves them to `DLQ.order.accepted`; the hold and the expiry scan are broker JVM properties (`orders.dlq.hold`, `orders.dlq.scan`, see [configuration](configuration.md)).

| Situation | Behaviour |
|-----------|-----------|
| Invalid request body | `400` problem detail with `errors[{field, message}]`, nothing persisted |
| Unknown order id (REST) | `404` problem detail |
| Illegal transition, concurrent update | `409` problem detail |
| Database down | Readiness `DOWN`; `POST /orders` returns `500` problem detail, nothing persisted |
| Broker down | API keeps accepting orders; events wait in the outbox (`outbox_pending`, `outbox_publish_failures_total`) and are delivered when the broker returns |
| Processing throws | Session rollback; the broker redelivers after 1 s and 2 s (3 attempts), then moves the message to `DLQ.order.accepted.hold`, where it waits 30 s (`expiry-delay`) before the broker moves it to `DLQ.order.accepted` |
| Message in `DLQ.order.accepted` (after the hold) | Dead-letter consumer stores the message in `dead_letter` (body, properties, origin queue, trace id) and sets the order to `FAILED` with a reason, in one transaction; nothing is retried automatically |
| Transient error (`FLAKY-`) | Retried twice in-process with 200 ms delay, no redelivery |
| Duplicate delivery | Order already final: acknowledged and skipped |
| Unknown order id (message) | Logged and acknowledged (poison message) |
| Crash while processing | Order stays `PROCESSING`; the redelivered message resumes it |
| Shutdown with a message in flight | Graceful shutdown (20 s); an unfinished JMS transaction rolls back and the message is redelivered |

## Spring features

| Area | Used |
|------|------|
| Web | Spring Web MVC, Bean Validation, `ProblemDetail` through `ResponseEntityExceptionHandler`, `PagedModel`, springdoc OpenAPI |
| Data | Spring Data JPA with auditing (`@CreatedDate`, `@LastModifiedDate`), `@Version`, `Specification` filters, `@EntityGraph`, native `SKIP LOCKED` query, Flyway with `ddl-auto=validate` |
| Messaging | `JmsTemplate`, `@JmsListener` with transacted sessions and concurrency, `JacksonJsonMessageConverter`, Artemis starter |
| Transactions | `@Transactional` (`MANDATORY` for the outbox append), `TransactionTemplate` in the relay, `@Scheduled` relay |
| Resilience | Spring Framework 7 `@Retryable` (`@EnableResilientMethods`), graceful shutdown, virtual threads |
| Configuration | `@ConfigurationProperties` records with defaults, profiles, `@ConditionalOnProperty` role toggles, Docker Compose support in the dev loop |
| Observability | Actuator health groups and probes, Micrometer `Observation`, Micrometer Tracing on OpenTelemetry, OTLP exporters, structured logging (ECS) with a `StructuredLoggingJsonMembersCustomizer` |
| Tests | `@SpringBootTest`, `@WebMvcTest`, `@DataJpaTest`, `MockMvcTester`, `@MockitoBean`, Testcontainers `@ServiceConnection`, `@AutoConfigureMetrics`, `@AutoConfigureTracing` |

## Tests

| Level | What | Where |
|-------|------|-------|
| Unit | Status transitions and history, order validation, service with mocks, processor retry, relay batch, trace context, log customizer, clock offset | `*Test` (surefire) |
| Web slice | `201` with `Location`, `400` field errors and malformed input, `404`, `409`, paged list | `OrderControllerTest` |
| Persistence slice | Sequential ids and auditing columns, version bump on transition, filters, history order, `SKIP LOCKED` with two real transactions | `OrderRepositoryIT`, `OutboxRepositoryIT` |
| Integration | Happy path to `PROCESSED`, `FAIL-` to `FAILED` through the hold queue and the DLQ with the stored dead letter (hold shortened to 2 s in the test broker), `FLAKY-` retry, trace id stored with the event, metric names, health groups, OpenAPI | `OrderFlowIT`, `OrderApiIT`, `OrderServiceApplicationIT` (failsafe, Testcontainers) |
| Architecture | Package dependencies, no `Instant.now()`, entities audited, controllers in `api` | `ArchitectureTest` (ArchUnit) |
| UI | API client, form validation with field errors, timeline rendering | Vitest, ESLint (`make ui-check`) |
| End to end | `scripts/smoke-test.sh` against compose or kind: health, processed and failed path, problem details, metrics, OpenAPI, UI proxy | `make smoke`, `helm test`, `kind-e2e` workflow |

`./mvnw verify` runs unit and integration tests (Docker needed for Testcontainers).

## When Kafka instead of JMS

The task asks for JMS, and Kafka is not a JMS provider. The publisher sits behind the `OutboxPublisher` port, so a Kafka adapter is a drop-in; the rest of the picture changes as follows.

| Aspect | JMS (here) | Kafka |
|--------|------------|-------|
| Consumption | Competing consumers on a queue, per-message acknowledgement | Consumer groups with offsets per partition; `customerId` as record key keeps one customer's orders in order |
| Retry and dead letters | Broker redelivery policy and DLQ in `broker.xml` | Client side: Spring Kafka `DefaultErrorHandler` with backoff and a `DeadLetterPublishingRecoverer` to `order.accepted.DLT` |
| History | Message is gone once consumed | Retention allows replay and many independent consumers |
| Outbox relay | Polling relay with `SKIP LOCKED` | Same relay, or Debezium capturing `outbox_event` into the topic |
| Fits when | Request-style work, transactional sessions, enterprise brokers (IBM MQ, Artemis) | Event streams, fan-out, replay, high throughput |
