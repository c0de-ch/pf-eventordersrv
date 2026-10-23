# Order Service

[![ci](https://github.com/c0de-ch/pf-eventordersrv/actions/workflows/ci.yml/badge.svg)](https://github.com/c0de-ch/pf-eventordersrv/actions/workflows/ci.yml) [![kind-e2e](https://github.com/c0de-ch/pf-eventordersrv/actions/workflows/kind-e2e.yml/badge.svg)](https://github.com/c0de-ch/pf-eventordersrv/actions/workflows/kind-e2e.yml)

Event-driven order service: `POST /orders` stores an order, publishes an `order.accepted` JMS event, and an asynchronous consumer processes it (`ACCEPTED → PROCESSING → PROCESSED`, or `FAILED` after exhausted retries). The service itself is `src/`; `ui/`, `charts/`, `deploy/` and `.github/` cover the optional items and can be skipped on a first read.

Java 25 · Spring Boot 4.1 · Spring Web MVC · Spring Data JPA · Flyway · PostgreSQL 17 · JMS via ActiveMQ Artemis · Actuator + Micrometer + OpenTelemetry · Testcontainers · Maven wrapper · Angular 22 · nginx · Docker Compose · Helm · kind · GitHub Actions

## Run

| How | Command |
|-----|---------|
| Full stack in containers | `make up` builds the images and starts Postgres, Artemis, the Grafana stack, the app (http://localhost:8080) and the UI (http://localhost:8081); `make smoke` runs the end-to-end check; `make down` stops everything |
| Dev loop | `./mvnw spring-boot:run` (Postgres, Artemis and the Grafana stack start from `compose.yaml`) |
| Tests | `./mvnw verify` (Testcontainers, needs Docker) |
| Kubernetes | `make kind-up kind-deploy` for a local kind cluster; `helm upgrade --install` for any other cluster (see Kubernetes) |

`make up` only needs Docker. The Maven path requires JDK 25; `java -version` must show 25 for `java -jar` (the Maven build enforces it, a plain `java` call does not). `make help` lists all targets.

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

## Design

```
POST /orders ─▶ OrderController ─▶ OrderService.create()
                                    │  one transaction: orders (ACCEPTED) + order_status_history + outbox_event (OrderAccepted, traceparent)
                                    ▼
                  OutboxRelay (every 1 s, FOR UPDATE SKIP LOCKED) ─▶ JMS queue order.accepted
                                                                            │
                  OrderAcceptedListener (transacted session) ◀──────────────┘
                    tx1 ACCEPTED → PROCESSING · simulated work · tx2 PROCESSING → PROCESSED
                    exception → rollback → redelivery (3 attempts) → DLQ.order.accepted ─▶ DeadLetterListener → FAILED
```

| Decision | Why | Not chosen |
|----------|-----|------------|
| Transactional outbox | Order and event commit together: no event without an order, no order without an event; a broker outage does not block the API | Publish after commit (event lost on a crash in between); XA across JDBC and JMS (heavy, poorly supported) |
| Status on `orders`, transitions in `order_status_history` | The current state is one cheap read, the history is a full audit trail; `Order` validates every transition and appends the row | Hibernate Envers (generic `_aud` tables); event sourcing (more than the task needs) |
| `bigint identity` ids | Readable (`/orders/1042`), sortable, native to JPA and Postgres | UUIDs (no enumeration, but unreadable); formatted references (need a sequence anyway) |
| Two short transactions in the consumer | `PROCESSING` is visible while the work runs, no database transaction spans the work, a crash leaves a state the redelivery resumes | One transaction around the whole processing |
| Broker redelivery plus in-process `@Retryable` | Redelivery survives a pod restart and ends in a dead-letter queue; the in-process retry absorbs transient errors without redelivery noise | Client-side retry only |
| Thin event | `orderId`, `customerId`, `amount`, `currency`, `occurredAt`; consumers fetch the rest through `GET /orders/{id}`, so the contract stays stable | Full order snapshot in the event |
| One deployable, roles by toggle | The task simulates the processing in-process; `ORDERS_WORKER_ENABLED` and `ORDERS_OUTBOX_RELAY_ENABLED` split API and worker later from the same image | Separate services from day one |
| Package by feature, guarded by ArchUnit | `order` (domain, service), `order.api`, `order.processing`, `outbox`, `messaging`, `common`; dependencies point inward and the build fails when they do not | Layer packages (`controller`, `service`, `repository`) |

## Assumptions

- An order is a `customerId` (opaque string up to 64 characters, not checked against a customer system), an `amount` (positive, at most 2 decimals) and an optional ISO 4217 `currency`, default `CHF`; one currency per order, no conversion.
- The initial status is `ACCEPTED`; the challenge's `CREATED` is an example, and one word is used for status, event, queue, log action and metric (see Messaging).
- The processing is simulated in-process; its outcome is chosen by the customer-id prefix (`FAIL-`, `FLAKY-`) so every path can be demonstrated.
- The API is internal, behind a gateway, and has no authentication; sequential ids are acceptable under that assumption, ownership checks arrive with authentication.
- Delivery is at least once, without ordering guarantees between orders; consumers are idempotent.
- Postgres only, no H2 profile: the tests run the real database through Testcontainers.
- All timestamps are UTC; business time comes from one injectable `Clock` (`ORDERS_CLOCK_OFFSET` shifts it for date scenarios), log and trace timestamps stay real.
- Published outbox rows are kept as an audit trail; purging them is a production step.
- Requests are not deduplicated: a client that retries `POST /orders` after a lost response creates a second order (see Towards production).

## Messaging

One word for one thing: status `ACCEPTED`, event `OrderAccepted`, queue `order.accepted`, log action `order.accepted`, metric `orders_accepted_total`. Creating an order writes the order and an `OrderAccepted` event into the same database transaction (transactional outbox). A relay publishes pending events to the JMS queue `order.accepted` every second, oldest first, with `FOR UPDATE SKIP LOCKED` so several instances can run. Delivery is at least once; consumers must be idempotent.

| Message | Content |
|---------|---------|
| Body (JSON text) | `{"orderId": 1, "customerId": "12345", "amount": 99.90, "currency": "CHF", "occurredAt": "2026-10-22T10:00:00Z"}` |
| Properties | `_type=OrderAccepted`, `eventId=<outbox id>`, `JMSCorrelationID=<orderId>` |

## Processing and error handling

A consumer reads `order.accepted` in a transacted JMS session, moves the order to `PROCESSING`, simulates work and sets `PROCESSED`. Customer ids starting with `FAIL-` fail permanently; ids starting with `FLAKY-` fail once and succeed on the in-process retry. The broker policy lives in `deploy/artemis/broker.xml` (mounted by compose and used by the tests); after changing it, recreate the broker with `docker compose up -d --force-recreate -V artemis`.

| Situation | Behaviour |
|-----------|-----------|
| Invalid request body | `400` problem detail with `errors[{field, message}]`, nothing persisted |
| Unknown order id (REST) | `404` problem detail |
| Illegal transition, concurrent update | `409` problem detail |
| Database down | Readiness `DOWN`; `POST /orders` returns `500` problem detail, nothing persisted |
| Broker down | API keeps accepting orders; events wait in the outbox (`outbox_pending`, `outbox_publish_failures_total`) and are delivered when the broker returns |
| Processing throws | Session rollback; the broker redelivers after 1 s and 2 s (3 attempts), then moves the message to `DLQ.order.accepted` |
| Message in `DLQ.order.accepted` | Dead-letter consumer sets the order to `FAILED` with a reason; nothing is retried automatically |
| Transient error (`FLAKY-`) | Retried twice in-process with 200 ms delay, no redelivery |
| Duplicate delivery | Order already final: acknowledged and skipped |
| Unknown order id (message) | Logged and acknowledged (poison message) |
| Crash while processing | Order stays `PROCESSING`; the redelivered message resumes it |
| Shutdown with a message in flight | Graceful shutdown (20 s); an unfinished JMS transaction rolls back and the message is redelivered |

## Observability

| Signal | How |
|--------|-----|
| Traces | OpenTelemetry via Micrometer Tracing, W3C propagation, OTLP export. One trace spans `POST /orders`, the outbox relay and the consumer: the relay restores the `traceparent` stored with the event. |
| Metrics | Micrometer: `/actuator/prometheus` for scraping and OTLP push. Custom: `orders_accepted_total`, `orders_processed_total`, `orders_failed_total`, `orders_dead_lettered_total`, `order_processing_seconds`, `order_lifecycle_seconds{outcome}`, `outbox_pending`, `outbox_publish_failures_total`. |
| Logs | Profiles `docker`/`k8s`: ECS JSON on stdout with `trace.id`, `span.id`, MDC `order.id`/`event.id`, a fixed `event.action` vocabulary (`order.accepted`, `order.event.published`, `order.event.received`, `order.processing.started`, `order.processed`, `order.failed`, `order.event.dead_lettered`) and `app.time`, the business clock. Also exported over OTLP. Default profile: readable text with the same key-value pairs. |
| Health | `/actuator/health/liveness`, `/actuator/health/readiness` (database only: a broker outage is buffered by the outbox) |

The app is configured with the standard OpenTelemetry variables; without an endpoint nothing is exported. Locally `docker compose` runs `grafana/otel-lgtm` (Grafana on http://localhost:3000, OTLP on 4317/4318) and `./mvnw spring-boot:run` connects to it automatically.

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
| Integration | Happy path to `PROCESSED`, `FAIL-` to `FAILED` through the DLQ, `FLAKY-` retry, trace id stored with the event, metric names, health groups, OpenAPI | `OrderFlowIT`, `OrderApiIT`, `OrderServiceApplicationIT` (failsafe, Testcontainers) |
| Architecture | Package dependencies, no `Instant.now()`, entities audited, controllers in `api` | `ArchitectureTest` (ArchUnit) |
| UI | API client, form validation with field errors, timeline rendering | Vitest, ESLint (`make ui-check`) |
| End to end | `scripts/smoke-test.sh` against compose or kind: health, processed and failed path, problem details, metrics, OpenAPI, UI proxy | `make smoke`, `helm test`, `kind-e2e` workflow |

## User interface

`ui/` is a small Angular 22 application (standalone components, signals, reactive forms, Vitest, ESLint): an order list with status filter and paging, a create form that shows the API's problem details per field, and an order page with the lifecycle timeline that refreshes until the order is final. It calls `/api/*`; the dev server proxies that to `localhost:8080` (`npm start` in `ui/`, Node 24), the image serves it with unprivileged nginx that proxies to the service (`API_UPSTREAM`).

## Container

Multi-stage `Dockerfile`: Maven build on Temurin 25 JDK (Alpine, dependency cache mount), then the layered jar extracted onto a Temurin 25 JRE image in the order dependencies, loader, snapshots, application, so a code change rebuilds only the last layer. The process runs as UID 10001 and reads its memory limit from the container (`MaxRAMPercentage=75`). `.dockerignore` is a whitelist: only `pom.xml`, `mvnw`, `.mvn/` and `src/` reach the build. Host ports: `POSTGRES_PORT` (5432), `ARTEMIS_PORT` (61616), `ARTEMIS_CONSOLE_PORT` (8161).

## Kubernetes

| Where | How |
|-------|-----|
| Local kind cluster | `make kind-up kind-deploy`: creates cluster `orders`, loads the images, installs `charts/dev-infra` (PostgreSQL, Artemis, Grafana stack), `charts/order-service` and `charts/order-ui`, runs the Helm tests and the smoke test. UI on http://localhost:30081, API on http://localhost:30080, Grafana on http://localhost:30300. `make kind-down` removes it. |
| Any cluster | `helm upgrade --install order-service charts/order-service -n orders -f my-values.yaml` with the database, broker and collector of the platform; see `charts/order-service/values-prod.example.yaml`. |

The `order-service` chart runs the image as a non-root user on a read-only filesystem with startup, liveness and readiness probes on the actuator endpoints, memory limits without CPU throttling, a config checksum that rolls pods on config changes, and optional HPA, PodDisruptionBudget, ServiceMonitor and Ingress. Credentials come from an existing Secret (`secrets.existingSecret`, keys `DB_PASSWORD` and `ARTEMIS_PASSWORD`). `worker.separate=true` splits the release into API pods (relay on, consumers off) and worker pods (consumers on) from the same image. `make lint` runs `helm lint` for all value files. The `dev-infra` chart is for development and tests only.

## CI/CD and GitOps

| Workflow | Trigger | What it does |
|----------|---------|--------------|
| `ci` | push to `main`, pull requests | `./mvnw verify` with Testcontainers and test reports as artifact; UI lint, tests and build; `helm lint` and kubeconform for every value set; on `main` the images `order-service` and `order-ui` go to `ghcr.io/c0de-ch/pf-eventordersrv/` (`sha-<commit>`, `main`) |
| `kind-e2e` | push to `main`, pull requests | kind cluster from `deploy/kind/cluster.yaml`, all three charts, `helm test`, smoke test through the NodePorts including the UI proxy |
| `release` | tag `v*` | images `:<version>`, the three charts as OCI artifacts under `ghcr.io/c0de-ch/charts`, GitHub release with the chart packages |

Dependabot keeps Maven dependencies, actions and base images current. The stages map one to one onto a GitLab pipeline (build, test, image, lint, e2e, release). `deploy/argocd/application.yaml` sketches the GitOps hand-over: Argo CD syncs `charts/order-service` with environment values, and a release bumps the image tag. `deploy/otel/collector.yaml` is the sample collector pipeline, the contract with the platform: OTLP in, `k8sattributes` and batching, `spanmetrics` and `servicegraph` connectors, Tempo, Mimir and Loki out, with scrape and file-log alternatives commented in place.

## When Kafka instead of JMS

The task asks for JMS, and Kafka is not a JMS provider. The publisher sits behind the `OutboxPublisher` port, so a Kafka adapter is a drop-in; the rest of the picture changes as follows.

| Aspect | JMS (here) | Kafka |
|--------|------------|-------|
| Consumption | Competing consumers on a queue, per-message acknowledgement | Consumer groups with offsets per partition; `customerId` as record key keeps one customer's orders in order |
| Retry and dead letters | Broker redelivery policy and DLQ in `broker.xml` | Client side: Spring Kafka `DefaultErrorHandler` with backoff and a `DeadLetterPublishingRecoverer` to `order.accepted.DLT` |
| History | Message is gone once consumed | Retention allows replay and many independent consumers |
| Outbox relay | Polling relay with `SKIP LOCKED` | Same relay, or Debezium capturing `outbox_event` into the topic |
| Fits when | Request-style work, transactional sessions, enterprise brokers (IBM MQ, Artemis) | Event streams, fan-out, replay, high throughput |

## Towards production

| Area | Next steps |
|------|------------|
| Security | OAuth2 resource server with ownership checks on `/orders`, TLS everywhere, credentials from a secret manager, NetworkPolicies, springdoc endpoints and health details off outside development, image signing and scanning |
| Data and messaging | HA PostgreSQL with backups, HA broker (Artemis cluster or IBM MQ), purge job for published outbox rows, DLQ redrive tool and runbook, `Idempotency-Key` on `POST /orders`, message groups per customer if ordering matters, published event schema |
| Operations | Alerts on `outbox_pending`, DLQ depth, `orders_failed_total` and `order_processing_seconds` p99; SLOs and dashboards; trace sampling below 1.0; worker split (`worker.separate=true`), HPA and PDB sized from load tests |
| Delivery | Argo CD per environment with image-tag promotion, expand/contract migration policy, release notes from Conventional Commits, smaller runtime image (jlink, CDS) |

## Configuration

| Variable | Default | Purpose |
|----------|---------|---------|
| `DB_URL` | `jdbc:postgresql://localhost:5432/orders` | JDBC URL |
| `DB_USER`, `DB_PASSWORD` | `orders`, `orders` | Database credentials |
| `ARTEMIS_URL` | `tcp://localhost:61616` | Broker URL |
| `ARTEMIS_USER`, `ARTEMIS_PASSWORD` | `artemis`, `artemis` | Broker credentials |
| `SERVER_PORT` | `8080` | HTTP port (`APP_PORT` maps it on the compose host, `UI_PORT` the UI, default 8081) |
| `ORDERS_MESSAGING_ORDER_ACCEPTED_QUEUE` | `order.accepted` | Queue for `OrderAccepted` events |
| `ORDERS_MESSAGING_ORDER_ACCEPTED_DLQ` | `DLQ.order.accepted` | Dead-letter queue of `order.accepted` |
| `ORDERS_WORKER_ENABLED` | `true` | Run the consumers in this instance |
| `ORDERS_PROCESSING_DELAY` | `PT0.5S` | Simulated processing time |
| `ORDERS_PROCESSING_FAILING_CUSTOMER_PREFIX` | `FAIL-` | Customer ids whose orders fail permanently |
| `ORDERS_PROCESSING_FLAKY_CUSTOMER_PREFIX` | `FLAKY-` | Customer ids whose orders fail once |
| `ORDERS_OUTBOX_RELAY_ENABLED` | `true` | Run the outbox relay in this instance |
| `ORDERS_OUTBOX_RELAY_POLL_INTERVAL` | `PT1S` | Delay between relay runs |
| `ORDERS_OUTBOX_RELAY_BATCH_SIZE` | `50` | Events per relay transaction |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | unset | OTLP collector, e.g. `http://otel-lgtm:4318`; unset disables trace and log export |
| `MANAGEMENT_OTLP_METRICS_EXPORT_ENABLED` | `false` | Push metrics over OTLP as well (set it together with the endpoint); the Prometheus endpoint is always on |
| `OTEL_SERVICE_NAME`, `OTEL_RESOURCE_ATTRIBUTES` | `order-service`, `service.namespace=orders` | Resource attributes seen by the platform |
| `OTEL_TRACES_SAMPLER_ARG` | `1.0` | Sampling ratio; lower it in production |
| `DEPLOYMENT_ENVIRONMENT` | `local` | `deployment.environment.name` resource attribute |
| `SPRING_PROFILES_ACTIVE` | unset | `docker` or `k8s` switches logs to ECS JSON |
| `ORDERS_CLOCK_OFFSET` | `PT0S` | Shifts the application clock (ISO-8601 duration, e.g. `P30D`, `-PT2H`) for test scenarios. Business timestamps follow it; log and trace timestamps stay real. |
