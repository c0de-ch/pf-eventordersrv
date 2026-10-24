import { setTimeout as delay } from 'node:timers/promises';
import { asObject, historyChecks, idOf, lifecycleSeconds, orderChecks, pageChecks, problemChecks, statusOf, timestampChecks } from './checks.ts';
import { describeRequest, pathOf, type ApiClient, type HttpResponse } from './http.ts';
import { describeExpectation, TERMINAL_STATUSES, type CreatedOrder, type OrderExpectation, type Result, type Scenario, type Verdict } from './model.ts';
import { phaseOne, phaseTwo, type CatalogueOptions } from './scenarios.ts';

export interface RunOptions {
  concurrency: number;
  pollMs: number;
  processedTimeoutMs: number;
  failedTimeoutMs: number;
}

export interface RunOutcome {
  results: Result[];
  created: CreatedOrder[];
}

export async function runCatalogue(
  client: ApiClient,
  catalogue: CatalogueOptions,
  options: RunOptions,
  onResult: (result: Result) => void = () => undefined,
): Promise<RunOutcome> {
  const created: CreatedOrder[] = [];
  const first = await runAll(client, phaseOne(catalogue), options, created, onResult);
  // The listing checks need to know what the first phase created
  const second = await runAll(client, phaseTwo(catalogue, created), options, created, onResult);
  return { results: [...first, ...second], created };
}

export async function runAll(
  client: ApiClient,
  scenarios: Scenario[],
  options: RunOptions,
  created: CreatedOrder[],
  onResult: (result: Result) => void = () => undefined,
): Promise<Result[]> {
  const results: Result[] = [];
  let next = 0;
  const worker = async (): Promise<void> => {
    for (;;) {
      const index = next++;
      const scenario = scenarios[index];
      if (scenario === undefined) {
        return;
      }
      const result = await runScenario(client, scenario, options, created);
      results[index] = result;
      onResult(result);
    }
  };
  const workers = Math.max(1, Math.min(options.concurrency, scenarios.length));
  await Promise.all(Array.from({ length: workers }, worker));
  return results;
}

export async function runScenario(client: ApiClient, scenario: Scenario, options: RunOptions, created: CreatedOrder[]): Promise<Result> {
  const started = Date.now();
  const base = {
    id: scenario.id,
    group: scenario.group,
    title: scenario.title,
    request: describeRequest(scenario.request),
    expected: describeExpectation(scenario.expect),
  };
  try {
    const evaluation = await evaluate(client, scenario, options, created);
    return { ...base, ...evaluation, durationMs: Date.now() - started };
  } catch (error) {
    const message = error instanceof Error ? `${error.name}: ${error.message}` : String(error);
    return { ...base, actual: `request failed: ${message}`, verdict: 'fail', problems: [message], durationMs: Date.now() - started };
  }
}

interface Evaluation {
  actual: string;
  verdict: Verdict;
  problems: string[];
}

function verdictOf(problems: string[]): Verdict {
  return problems.length === 0 ? 'pass' : 'fail';
}

function snippet(text: string): string {
  return text.replace(/\s+/g, ' ').trim().slice(0, 120);
}

function titleOf(response: HttpResponse): string | undefined {
  const title = asObject(response.json)?.['title'];
  return typeof title === 'string' ? title : undefined;
}

function describePage(response: HttpResponse): string {
  const page = asObject(asObject(response.json)?.['page']);
  if (response.status !== 200 || page === undefined) {
    return `${response.status} ${snippet(response.text)}`;
  }
  return `200, ${String(page['totalElements'])} elements, page ${String(page['number'])} of ${String(page['totalPages'])}, size ${String(page['size'])}`;
}

async function evaluate(client: ApiClient, scenario: Scenario, options: RunOptions, created: CreatedOrder[]): Promise<Evaluation> {
  const expectation = scenario.expect;
  switch (expectation.kind) {
    case 'order':
      return followOrder(client, scenario, expectation, options, created);
    case 'problem': {
      const response = await client.send(scenario.request);
      const problems = problemChecks(response, expectation, pathOf(scenario.request));
      return { actual: `${response.status} ${titleOf(response) ?? snippet(response.text)}`.trim(), verdict: verdictOf(problems), problems };
    }
    case 'page': {
      const response = await client.send(scenario.request);
      const problems = pageChecks(response, expectation);
      return { actual: describePage(response), verdict: verdictOf(problems), problems };
    }
    case 'text': {
      const response = await client.send(scenario.request);
      const problems: string[] = [];
      if (response.status !== expectation.status) {
        problems.push(`status ${response.status}, expected ${expectation.status}`);
      }
      for (const needle of expectation.contains) {
        if (!response.text.includes(needle)) {
          problems.push(`"${needle}" missing`);
        }
      }
      return { actual: `${response.status}, ${response.text.length} characters`, verdict: verdictOf(problems), problems };
    }
    case 'health': {
      const response = await client.send(scenario.request);
      const status = statusOf(response);
      const problems: string[] = [];
      if (response.status !== 200) {
        problems.push(`status ${response.status}, expected 200`);
      }
      if (status !== 'UP') {
        problems.push(`health status ${JSON.stringify(status ?? null)}, expected UP`);
      }
      return { actual: `${response.status} ${status ?? snippet(response.text)}`, verdict: verdictOf(problems), problems };
    }
    case 'probe': {
      const response = await client.send(scenario.request);
      const id = idOf(response);
      const detail = id === undefined
        ? ` ${snippet(response.text)}`
        : `, order ${id} created with customerId ${JSON.stringify(asObject(response.json)?.['customerId'] ?? null)}`;
      return { actual: `${response.status}${detail}`, verdict: 'observed', problems: [] };
    }
  }
}

async function followOrder(
  client: ApiClient,
  scenario: Scenario,
  expectation: OrderExpectation,
  options: RunOptions,
  created: CreatedOrder[],
): Promise<Evaluation> {
  const start = Date.now();
  const response = await client.send(scenario.request);
  if (response.status !== 201) {
    return { actual: `${response.status} ${snippet(response.text)}`, verdict: 'fail', problems: [`status ${response.status}, expected 201`] };
  }
  const problems = orderChecks(response, expectation);
  const id = idOf(response);
  const location = response.headers.get('location');
  if (location === null || id === undefined || !location.endsWith(`/orders/${id}`)) {
    problems.push(`Location ${JSON.stringify(location)}, expected to end with /orders/${String(id)}`);
  }
  // The response is built inside the creating transaction; the relay and the consumer run after the commit
  if (statusOf(response) !== 'ACCEPTED') {
    problems.push(`initial status ${JSON.stringify(statusOf(response) ?? null)}, expected ACCEPTED`);
  }
  if (id === undefined) {
    return { actual: '201 without a usable id', verdict: 'fail', problems };
  }
  const deadline = start + (expectation.outcome === 'FAILED' ? options.failedTimeoutMs : options.processedTimeoutMs);
  let current = response;
  let status = statusOf(current);
  while (status !== undefined && !TERMINAL_STATUSES.has(status) && Date.now() < deadline) {
    await delay(options.pollMs);
    current = await client.send({ method: 'GET', path: `/orders/${id}` });
    if (current.status !== 200) {
      problems.push(`GET /orders/${id} answered ${current.status}`);
      break;
    }
    status = statusOf(current);
  }
  const seconds = ((Date.now() - start) / 1000).toFixed(1);
  created.push({ scenarioId: scenario.id, id, customerId: expectation.customerId, status: status ?? 'unknown' });
  if (status !== expectation.outcome) {
    problems.push(`final status ${status ?? 'unknown'} after ${seconds} s, expected ${expectation.outcome}`);
    return { actual: `201, ${status ?? 'unknown'} after ${seconds} s`, verdict: 'fail', problems };
  }
  const body = asObject(current.json);
  if (body === undefined) {
    problems.push('read back: body is not a JSON object');
    return { actual: `201, ${status} after ${seconds} s`, verdict: 'fail', problems };
  }
  problems.push(...orderChecks(current, expectation).map((problem) => `read back: ${problem}`));
  if (idOf(current) !== id) {
    problems.push(`read back id ${String(idOf(current))}, expected ${id}`);
  }
  problems.push(...historyChecks(body, ['ACCEPTED', 'PROCESSING', expectation.outcome], expectation.reasonContains));
  problems.push(...timestampChecks(body));
  const lifecycle = lifecycleSeconds(body);
  if (expectation.minSeconds !== undefined && (lifecycle === undefined || lifecycle < expectation.minSeconds)) {
    problems.push(`final after ${lifecycle?.toFixed(1) ?? '?'} s by the service clock, expected at least ${expectation.minSeconds} s of redelivery back-off`);
  }
  return { actual: `201, ${status} after ${seconds} s`, verdict: verdictOf(problems), problems };
}
