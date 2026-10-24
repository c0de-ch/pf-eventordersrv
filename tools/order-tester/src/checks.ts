import type { HttpResponse, Json } from './http.ts';
import { STATUSES, type OrderExpectation, type PageExpectation, type ProblemExpectation } from './model.ts';

export type JsonObject = { [key: string]: Json };

export function asObject(value: Json | undefined): JsonObject | undefined {
  return typeof value === 'object' && value !== null && !Array.isArray(value) ? value : undefined;
}

export function asArray(value: Json | undefined): Json[] | undefined {
  return Array.isArray(value) ? value : undefined;
}

// The API prints amounts with two decimals: 10 -> 10.00, 99.9 -> 99.90
export function canonicalAmount(input: string | number): string {
  const text = String(input);
  const dot = text.indexOf('.');
  const whole = dot < 0 ? text : text.slice(0, dot);
  const fraction = dot < 0 ? '' : text.slice(dot + 1);
  return `${whole}.${fraction.padEnd(2, '0')}`;
}

// Read from the raw text: JSON.parse would round a 17-digit amount
export function rawAmount(text: string): string | undefined {
  return /"amount"\s*:\s*(-?\d+(?:\.\d+)?)/.exec(text)?.[1];
}

// Instants with up to nine fraction digits, as Jackson prints java.time.Instant
export function instant(value: Json | undefined): number | undefined {
  if (typeof value !== 'string') {
    return undefined;
  }
  const match = /^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d{1,9}))?Z$/.exec(value);
  if (!match || match[1] === undefined) {
    return undefined;
  }
  const base = Date.parse(`${match[1]}Z`);
  const fraction = match[2] === undefined ? 0 : Number(`0.${match[2]}`) * 1000;
  return Number.isNaN(base) ? undefined : base + fraction;
}

export function statusOf(response: HttpResponse): string | undefined {
  const status = asObject(response.json)?.['status'];
  return typeof status === 'string' ? status : undefined;
}

export function idOf(response: HttpResponse): number | undefined {
  const id = asObject(response.json)?.['id'];
  return typeof id === 'number' && Number.isInteger(id) && id > 0 ? id : undefined;
}

export function problemChecks(response: HttpResponse, expectation: ProblemExpectation, path: string): string[] {
  const problems: string[] = [];
  if (response.status !== expectation.status) {
    problems.push(`status ${response.status}, expected ${expectation.status}`);
  }
  const type = response.headers.get('content-type') ?? '';
  if (!type.startsWith('application/problem+json')) {
    problems.push(`content type "${type}", expected application/problem+json`);
  }
  const body = asObject(response.json);
  if (body === undefined) {
    problems.push('body is not a JSON object');
    return problems;
  }
  if (body['status'] !== expectation.status) {
    problems.push(`problem status ${JSON.stringify(body['status'] ?? null)}, expected ${expectation.status}`);
  }
  if (typeof body['title'] !== 'string' || body['title'] === '') {
    problems.push('title missing');
  }
  if (expectation.title !== undefined && body['title'] !== expectation.title) {
    problems.push(`title ${JSON.stringify(body['title'] ?? null)}, expected "${expectation.title}"`);
  }
  if (expectation.detail !== undefined && body['detail'] !== expectation.detail) {
    problems.push(`detail ${JSON.stringify(body['detail'] ?? null)}, expected "${expectation.detail}"`);
  }
  if (typeof body['instance'] === 'string' && body['instance'] !== path) {
    problems.push(`instance ${JSON.stringify(body['instance'])}, expected "${path}"`);
  }
  for (const key of ['stackTrace', 'trace', 'exception', 'message']) {
    if (key in body) {
      problems.push(`"${key}" in the problem detail leaks internals`);
    }
  }
  if (expectation.fields !== undefined) {
    const errors = asArray(body['errors']);
    if (errors === undefined) {
      problems.push('errors[] missing');
      return problems;
    }
    const fields = errors.map((error) => asObject(error)?.['field']).filter((field): field is string => typeof field === 'string');
    if (JSON.stringify(fields) !== JSON.stringify(expectation.fields)) {
      problems.push(`errors on [${fields.join(', ')}], expected [${expectation.fields.join(', ')}]`);
    }
    const withoutMessage = errors.some((error) => {
      const message = asObject(error)?.['message'];
      return typeof message !== 'string' || message === '';
    });
    if (withoutMessage) {
      problems.push('a field error has no message');
    }
  }
  return problems;
}

export function orderChecks(response: HttpResponse, expectation: OrderExpectation): string[] {
  const body = asObject(response.json);
  if (body === undefined) {
    return ['body is not a JSON object'];
  }
  const problems: string[] = [];
  if (idOf(response) === undefined) {
    problems.push(`id ${JSON.stringify(body['id'] ?? null)} is not a positive integer`);
  }
  if (body['customerId'] !== expectation.customerId) {
    problems.push(`customerId ${JSON.stringify(body['customerId'] ?? null)}, expected ${JSON.stringify(expectation.customerId)}`);
  }
  if (body['currency'] !== expectation.currency) {
    problems.push(`currency ${JSON.stringify(body['currency'] ?? null)}, expected "${expectation.currency}"`);
  }
  const amount = rawAmount(response.text);
  if (amount !== expectation.amount) {
    problems.push(`amount ${amount ?? 'missing'}, expected ${expectation.amount}`);
  }
  const status = body['status'];
  if (typeof status !== 'string' || !(STATUSES as readonly string[]).includes(status)) {
    problems.push(`status ${JSON.stringify(status ?? null)} is not a known status`);
  }
  return problems;
}

export function historyChecks(body: JsonObject, expectedChain: readonly string[], reasonContains?: string): string[] {
  const history = asArray(body['history']);
  if (history === undefined) {
    return ['history missing'];
  }
  const problems: string[] = [];
  const entries = history.map(asObject);
  const chain = entries.map((entry) => entry?.['to']);
  if (JSON.stringify(chain) !== JSON.stringify(expectedChain)) {
    problems.push(`history ${chain.map(String).join(' > ')}, expected ${expectedChain.join(' > ')}`);
  }
  let previousTo: Json = null;
  let previousAt = Number.NEGATIVE_INFINITY;
  entries.forEach((entry, index) => {
    if (entry === undefined) {
      problems.push(`history[${index}] is not an object`);
      return;
    }
    const from = entry['from'] ?? null;
    if (from !== previousTo) {
      problems.push(`history[${index}].from ${JSON.stringify(from)}, expected ${JSON.stringify(previousTo)}`);
    }
    previousTo = entry['to'] ?? null;
    const at = instant(entry['at']);
    if (at === undefined) {
      problems.push(`history[${index}].at ${JSON.stringify(entry['at'] ?? null)} is not an instant`);
    } else {
      if (at < previousAt) {
        problems.push(`history[${index}].at is earlier than the previous transition`);
      }
      previousAt = at;
    }
    if (entry['to'] !== 'FAILED' && (entry['reason'] ?? null) !== null) {
      problems.push(`history[${index}] carries a reason without a failure`);
    }
  });
  if (reasonContains !== undefined) {
    const reason = entries.at(-1)?.['reason'];
    if (typeof reason !== 'string' || !reason.includes(reasonContains)) {
      problems.push(`final reason ${JSON.stringify(reason ?? null)}, expected to contain "${reasonContains}"`);
    }
  }
  return problems;
}

// Audit columns: the order row and its history rows are written by the same transactions
export function timestampChecks(body: JsonObject): string[] {
  const problems: string[] = [];
  const created = instant(body['createdAt']);
  const updated = instant(body['updatedAt']);
  if (created === undefined) {
    problems.push(`createdAt ${JSON.stringify(body['createdAt'] ?? null)} is not an instant`);
  }
  if (updated === undefined) {
    problems.push(`updatedAt ${JSON.stringify(body['updatedAt'] ?? null)} is not an instant`);
  }
  if (created !== undefined && updated !== undefined && updated < created) {
    problems.push('updatedAt is earlier than createdAt');
  }
  const history = asArray(body['history']) ?? [];
  const firstAt = instant(asObject(history[0])?.['at']);
  const lastAt = instant(asObject(history.at(-1))?.['at']);
  if (created !== undefined && firstAt !== undefined && firstAt < created - 50) {
    problems.push('history[0].at is earlier than createdAt');
  }
  if (updated !== undefined && lastAt !== undefined && Math.abs(updated - lastAt) > 2000) {
    problems.push(`updatedAt is ${Math.round(Math.abs(updated - lastAt))} ms away from the last transition`);
  }
  return problems;
}

// Seconds between the first and the last transition, measured by the service's own clock
export function lifecycleSeconds(body: JsonObject): number | undefined {
  const history = asArray(body['history']) ?? [];
  const first = instant(asObject(history[0])?.['at']);
  const last = instant(asObject(history.at(-1))?.['at']);
  return first === undefined || last === undefined ? undefined : (last - first) / 1000;
}

export function pageChecks(response: HttpResponse, expectation: PageExpectation): string[] {
  if (response.status !== 200) {
    return [`status ${response.status}, expected 200`];
  }
  const body = asObject(response.json);
  const content = asArray(body?.['content']);
  const page = asObject(body?.['page']);
  if (body === undefined || content === undefined || page === undefined) {
    return ['body is not a page with content[] and page{}'];
  }
  const problems: string[] = [];
  const numbers = { size: page['size'], number: page['number'], totalElements: page['totalElements'], totalPages: page['totalPages'] };
  for (const [key, value] of Object.entries(numbers)) {
    if (typeof value !== 'number') {
      problems.push(`page.${key} ${JSON.stringify(value ?? null)} is not a number`);
    }
  }
  const expectNumber = (key: keyof typeof numbers, expected: number | undefined): void => {
    if (expected !== undefined && numbers[key] !== expected) {
      problems.push(`page.${key} ${String(numbers[key])}, expected ${expected}`);
    }
  };
  expectNumber('size', expectation.size);
  expectNumber('number', expectation.number);
  expectNumber('totalElements', expectation.totalElements);
  expectNumber('totalPages', expectation.totalPages);
  if (expectation.contentLength !== undefined && content.length !== expectation.contentLength) {
    problems.push(`${content.length} entries on the page, expected ${expectation.contentLength}`);
  }
  if (typeof numbers.size === 'number' && content.length > numbers.size) {
    problems.push(`${content.length} entries exceed page.size ${numbers.size}`);
  }
  if (expectation.everyStatus !== undefined) {
    const other = content.map((entry) => asObject(entry)?.['status']).filter((status) => status !== expectation.everyStatus);
    if (other.length > 0) {
      problems.push(`${other.length} entries with status ${other.map(String).join(', ')}, expected all ${expectation.everyStatus}`);
    }
  }
  if (expectation.descendingIds) {
    const ids = content.map((entry) => asObject(entry)?.['id']).filter((id): id is number => typeof id === 'number');
    if (ids.some((id, index) => index > 0 && id > (ids[index - 1] ?? Number.POSITIVE_INFINITY))) {
      problems.push(`ids ${ids.join(', ')} are not newest first`);
    }
  }
  if (content.some((entry) => asObject(entry)?.['history'] !== undefined)) {
    problems.push('list entries carry the history, expected summaries');
  }
  return problems;
}
