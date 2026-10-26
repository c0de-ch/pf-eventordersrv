# Operations

How the service is built, deployed, observed and what is missing for production. Design and error handling: [design](design.md). Diagrams: [architecture](architecture/README.md).

## Container

Multi-stage `Dockerfile`: Maven build on Temurin 25 JDK (Alpine, dependency cache mount), then the layered jar extracted onto a Temurin 25 JRE image in the order dependencies, loader, snapshots, application, so a code change rebuilds only the last layer. The process runs as UID 10001 and reads its memory limit from the container (`MaxRAMPercentage=75`). `.dockerignore` is a whitelist: only `pom.xml`, `mvnw`, `.mvn/` and `src/` reach the build. Host ports: `POSTGRES_PORT` (5432), `ARTEMIS_PORT` (61616), `ARTEMIS_CONSOLE_PORT` (8161), `GRAFANA_PORT` (3000), `APP_PORT` (8080), `UI_PORT` (8081); `DLQ_HOLD_MS` and `DLQ_SCAN_MS` set the dead-letter hold (default 30 s) and the broker's expiry scan, see [configuration](configuration.md). `make reset-db` empties the order tables (identities restart at 1) and purges the three queues of the running stack; `make kind-reset-db` does the same on kind.

The Artemis console is on http://localhost:8161 (artemis/artemis). `scripts/smoke-test.sh <base url>` checks readiness, the processed and the failed path, problem details, metrics and the OpenAPI document; with `UI_URL` set it also checks the UI and its API proxy.

## Kubernetes

| Where | How |
|-------|-----|
| Local kind cluster | `make kind-up kind-deploy`: creates cluster `orders` (one control plane that maps the NodePorts to the host, two workers that run the pods), loads the images, installs `charts/dev-infra` (PostgreSQL, Artemis, Grafana stack with the dashboard), `charts/order-service` and `charts/order-ui`, runs the Helm tests and the smoke test. UI on http://localhost:30081, API on http://localhost:30080, Grafana on http://localhost:30300, Artemis console on http://localhost:30161 (artemis/artemis). `make kind-down` removes it. On Linux the three kubelets need `fs.inotify.max_user_instances` of at least 512 (kind's known issue "too many open files"); the workflow sets it, `make kind-up` warns. |
| Any cluster | `helm upgrade --install order-service charts/order-service -n orders -f my-values.yaml` with the database, broker and collector of the platform; see `charts/order-service/values-prod.example.yaml`. |

The `order-service` chart runs the image as a non-root user on a read-only filesystem with startup, liveness and readiness probes on the actuator endpoints, memory limits without CPU throttling, a config checksum that rolls pods on config changes, and optional HPA, PodDisruptionBudget, ServiceMonitor and Ingress. Credentials come from an existing Secret (`secrets.existingSecret`, keys `DB_PASSWORD` and `ARTEMIS_PASSWORD`). `worker.separate=true` splits the release into API pods (relay on, consumers off) and worker pods (consumers on) from the same image. `topologySpreadConstraints` spreads the pods of each deployment over nodes or zones; on kind the service runs two replicas spread over the workers (best effort, `ScheduleAnyway`) with a PodDisruptionBudget of one. `make lint` runs `helm lint` for all value files and checks the chart copies of `broker.xml` and the dashboards. The `dev-infra` chart is for development and tests only; its values `artemis.deadLetterHoldMs`, `artemis.expiryScanMs` and `artemis.consoleNodePort` set the dead-letter hold, the expiry scan and the console port.

## CI/CD and GitOps

| Workflow | Trigger | What it does |
|----------|---------|--------------|
| `ci` | push to `main`, pull requests | `./mvnw verify` with Testcontainers and test reports as artifact; UI lint, tests and build; `helm lint` and kubeconform for every value set; on `main` the images `order-service` and `order-ui` go to `ghcr.io/c0de-ch/pf-eventordersrv/` (`sha-<commit>`, `main`) |
| `kind-e2e` | push to `main`, pull requests | kind cluster from `deploy/kind/cluster.yaml`, all three charts, `helm test`, smoke test through the NodePorts including the UI proxy |
| `release` | tag `v*` | images `:<version>`, the three charts as OCI artifacts under `ghcr.io/c0de-ch/charts`, GitHub release with the chart packages |

Dependabot keeps Maven dependencies, actions and base images current. The stages map one to one onto a GitLab pipeline (build, test, image, lint, e2e, release). `deploy/argocd/application.yaml` sketches the GitOps hand-over: Argo CD syncs `charts/order-service` with environment values, and a release bumps the image tag. `deploy/otel/collector.yaml` is the sample collector pipeline, the contract with the platform: OTLP in, `k8sattributes` and batching, `spanmetrics` and `servicegraph` connectors, Tempo, Mimir and Loki out, with scrape and file-log alternatives commented in place.

## Observability

| Signal | How |
|--------|-----|
| Traces | OpenTelemetry via Micrometer Tracing, W3C propagation, OTLP export. One trace spans `POST /orders`, the outbox relay and the consumer: the relay restores the `traceparent` stored with the event; the processing span carries `order.id`. Requests to `/actuator/**` are not observed: no spans and no request timers for probes and scrapes. |
| Metrics | Micrometer: `/actuator/prometheus` for scraping and OTLP push. Custom: `orders_accepted_total`, `orders_processed_total`, `orders_failed_total`, `orders_dead_lettered_total`, `order_processing_seconds`, `order_lifecycle_seconds{outcome}`, `outbox_pending`, `outbox_publish_failures_total`. |
| Logs | Profiles `docker`/`k8s`: ECS JSON on stdout with `trace.id`, `span.id`, MDC `order.id`/`event.id`, a fixed `event.action` vocabulary (`order.accepted`, `order.event.published`, `order.event.publish_failed`, `order.event.received`, `order.processing.started`, `order.processing.resumed`, `order.processing.skipped`, `order.processed`, `order.failed`, `order.failure.ignored`, `order.event.dead_lettered`) and `app.time`, the business clock. Also exported over OTLP. Default profile: readable text with the same key-value pairs. Failure lines carry `error.type` and `error.message`; an unexpected exception keeps its stack trace, logged once. |
| Health | `/actuator/health/liveness`, `/actuator/health/readiness` (database only: a broker outage is buffered by the outbox) |
| Dashboard | Grafana dashboard `Order Service` (folder `Orders`), provisioned from `deploy/grafana/dashboards/order-service.json` in compose and kind: order rates, processing and lifecycle percentiles, outbox backlog, HTTP and JMS throughput, and the traces (list and waterfall) and log lines of one order through the `Order id` variable. Click a trace in the list to see it in the dashboard, or open it in Explore from the trace id. A second dashboard, `Order Service Runtime` (`deploy/grafana/dashboards/order-service-runtime.json`, http://localhost:3000/d/order-service-runtime), shows the runtime underneath: HTTP rate, status and latency per route, JMS publish and process, outbox, HikariCP pool and Spring Data repository calls, heap, GC, threads and CPU, and the warnings and errors from Loki. |

The app is configured with the standard OpenTelemetry variables; without an endpoint nothing is exported. Locally `docker compose` runs `grafana/otel-lgtm` (Grafana on http://localhost:3000, dashboard at http://localhost:3000/d/order-service, OTLP on 4317/4318) and `./mvnw spring-boot:run` connects to it automatically. The trace of a message is in the message itself: the JMS property `traceparent` carries the trace id, and every log line of the order carries `trace.id`.

## User interface

`ui/` is a small Angular 22 application (standalone components, signals, reactive forms, Vitest, ESLint): an order list with status filter and paging, a create form that shows the API's problem details per field, and an order page with the lifecycle timeline that refreshes until the order is final. It calls `/api/*`; the dev server proxies that to `localhost:8080` (`npm start` in `ui/`, Node 24), the image serves it with unprivileged nginx that proxies to the service (`API_UPSTREAM`). `make ui-check` runs lint, tests and build.

## Towards production

| Area | Next steps |
|------|------------|
| Security | OAuth2 resource server with ownership checks on `/orders`, TLS everywhere, credentials from a secret manager, NetworkPolicies, springdoc endpoints and health details off outside development, image signing and scanning |
| Data and messaging | HA PostgreSQL with backups, HA broker (Artemis cluster or IBM MQ), purge job for published outbox rows, DLQ redrive tool over the `dead_letter` table and runbook, `Idempotency-Key` on `POST /orders`, message groups per customer if ordering matters, published event schema |
| Operations | Alerts on `outbox_pending`, DLQ depth, `orders_failed_total` and `order_processing_seconds` p99; SLOs and dashboards per environment; trace sampling below 1.0; worker split (`worker.separate=true`), HPA and PDB sized from load tests |
| Delivery | Argo CD per environment with image-tag promotion, expand/contract migration policy, release notes from Conventional Commits, smaller runtime image (jlink, CDS) |
