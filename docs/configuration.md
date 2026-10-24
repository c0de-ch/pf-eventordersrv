# Configuration

Defaults live in `src/main/resources/application.yml`; every value can be overridden by an environment variable. Charts set them from values, compose from `compose.yaml`.

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
| `HOSTNAME` | `local` | `service.instance.id` resource attribute and ECS `service.node.name`; Docker and Kubernetes set it (container id, pod name) |
| `SPRING_PROFILES_ACTIVE` | unset | `docker` or `k8s` switches logs to ECS JSON |
| `ORDERS_CLOCK_OFFSET` | `PT0S` | Shifts the application clock (ISO-8601 duration, e.g. `P30D`, `-PT2H`) for test scenarios. Business timestamps follow it; log and trace timestamps stay real. |

Fixed in `application.yml`: readiness group `readinessState,db`, graceful shutdown 20 s, JMS listener concurrency 2 to 5 with transacted sessions, page size limit 100, percentile histograms for `order.processing` and `order.lifecycle`, OTLP metrics step 15 s.
