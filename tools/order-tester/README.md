# Order tester

Black-box check of a running order service: generates test data, drives every documented error and edge case through the HTTP API, follows each order to its final status and writes a report. It knows only the API contract ([design](../../docs/design.md), [README](../../README.md)) and shares no code with the service.

## Run

| What | Command |
|------|---------|
| Against compose | `make blackbox` or `node tools/order-tester/src/main.ts` (default target `http://localhost:8080`) |
| Against kind or any other deployment | `make blackbox BASE_URL=http://localhost:30080` |
| Replay the same data against another environment | `node tools/order-tester/src/main.ts --seed 20261024 --base-url ...` |
| Options | `node tools/order-tester/src/main.ts --help` |

Needs Node 24 or newer and nothing else: the TypeScript sources run directly, there are no runtime dependencies. Exit code 0 when every checked scenario behaved as specified, 1 on deviations, 2 when the target is not ready. The report is written to `tools/order-tester/report/` as `report.md` (verdict, totals per group, failures with request, expectation and observed behaviour, every scenario) and `report.json`.

| Option | Default | Purpose |
|--------|---------|---------|
| `--base-url` | `http://localhost:8080` | Service under test |
| `--count` | `10` | Generated regular orders (random amount with 0 to 2 decimals, random currency) |
| `--seed` | current time | Seed for the generated data and the run token; the same seed replays the same data |
| `--timeout` | `120` | Seconds to wait for a `FAILED` order; a `PROCESSED` order gets half |
| `--fail-min-seconds` | `33` | Time a `FAILED` order must have taken by the service's own clock: redelivery back-off 1 s + 2 s plus the 30 s dead-letter hold in `deploy/artemis/broker.xml` |
| `--concurrency` | `8` | Scenarios in flight |
| `--poll` | `500` | Poll interval in ms while an order is not final |
| `--out` | `tools/order-tester/report` | Report directory |

## What it checks

Every customer id of a run starts with the run token (`t<seed in base 36>`), so the data of one run can be found and listed afterwards.

| Group | Scenarios |
|-------|-----------|
| create | Minimal body and default currency, explicit and explicit-null currency, amounts 0.01, integer, one decimal and the 17-digit maximum (compared as text, unrounded), 64-character and unicode customer ids, unknown JSON field, the same request twice (two orders), the generated orders |
| processing | `FAIL-` ends `FAILED` with the dead-letter reason after the redelivery back-off and the 30 s dead-letter hold; `FLAKY-` ends `PROCESSED`; prefix rules are case-sensitive, the prefix alone fails, `FAIL-FLAKY-` fails, `FLAKY-FAIL-` succeeds |
| validation | Empty, blank, missing and 65-character customer id; missing, null, zero, negative, three-decimal, 18-digit, textual and boolean amount; lower-case, two-, four-letter and empty currency; three violations reported together and sorted; malformed JSON, empty body, JSON array, JSON null; `text/plain` and no `Content-Type` (415). Each answer must be an `application/problem+json` body without internals; field errors carry field and message |
| read | Unknown id (404 with title and detail), non-numeric id (400), negative id (404), id beyond 64 bits (400), `PUT`, `DELETE` and `POST` on an order (405) |
| list | Totals and paging of the generated orders, duplicates counted twice, nothing persisted for rejected requests, unicode filter, status filter in both directions, page size cap 100, fallbacks for size 0, negative and non-numeric page, newest first, unknown status filter (400) |
| operations | Readiness and liveness `UP`, Prometheus endpoint with the business metrics, OpenAPI document with both resources, unknown health group (404) |
| probe | Behaviour the contract leaves open (numeric string as amount, whitespace around a customer id): recorded in the report, never a failure |

Every created order is followed until it is `PROCESSED` or `FAILED` and read back: `Location`, immutable fields, the amount printed with two decimals, the history chain `null → ACCEPTED → PROCESSING → final` with `from` equal to the previous `to`, non-decreasing timestamps, a reason only on `FAILED`, and `createdAt`/`updatedAt` consistent with the history. Listing totals are derived from what the run created.

## Develop

| What | Command |
|------|---------|
| Type check (strict TypeScript, erasable syntax only) | `cd tools/order-tester && npm ci && npm run check` |
| Tests | `npm test`: pure checks, catalogue, report, and the runner against `test/fake-api.ts`, a fake service that implements the contract and can be given defects (no dead-lettering, missing `PROCESSING` row, validation off, orders never final, 500 on unknown id, deduplication, lost currency default); each defect must surface as a failure |
| Both from the repository root | `make blackbox-check` |

Layout: `src/http.ts` (fetch client), `src/model.ts` (scenario and result types), `src/scenarios.ts` (catalogue and seeded generator), `src/checks.ts` (pure assertions), `src/runner.ts` (two phases: create and probe, then list), `src/report.ts`, `src/main.ts` (CLI).
