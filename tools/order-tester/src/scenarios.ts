import { JSON_HEADERS, type Json, type RequestSpec } from './http.ts';
import { canonicalAmount } from './checks.ts';
import type { CreatedOrder, Group, OrderExpectation, Outcome, PageExpectation, ProblemExpectation, Scenario } from './model.ts';

export interface CatalogueOptions {
  run: string;
  count: number;
  seed: number;
  failMinSeconds: number;
}

// 1 s + 2 s redelivery back-off plus the 30 s dead-letter hold in the shipped broker policy: a FAILED order cannot be final sooner
export const DEFAULT_FAIL_MIN_SECONDS = 33;
export const CURRENCIES = ['CHF', 'EUR', 'USD', 'GBP'] as const;
const FAILED_REASON = 'dead-lettered';

export function runToken(seed: number): string {
  return `t${(seed >>> 0).toString(36)}`;
}

export function mulberry32(seed: number): () => number {
  let state = seed >>> 0;
  return () => {
    state = (state + 0x6d2b79f5) >>> 0;
    let t = state;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

export interface GeneratedOrder {
  customerId: string;
  amount: string;
  currency: (typeof CURRENCIES)[number];
}

// Deterministic for a seed, so the same data can be replayed against another environment
export function generateOrders(options: CatalogueOptions): GeneratedOrder[] {
  const random = mulberry32(options.seed);
  const pick = (bound: number): number => Math.floor(random() * bound);
  const orders: GeneratedOrder[] = [];
  for (let i = 0; i < options.count; i++) {
    const whole = 1 + pick(9999);
    const decimals = pick(3);
    const amount = decimals === 0 ? `${whole}` : decimals === 1 ? `${whole}.${1 + pick(9)}` : `${whole}.${String(1 + pick(99)).padStart(2, '0')}`;
    orders.push({ customerId: `${options.run}-burst`, amount, currency: CURRENCIES[pick(CURRENCIES.length)] ?? 'CHF' });
  }
  return orders;
}

function post(body: string | Uint8Array, headers: Record<string, string> = JSON_HEADERS): RequestSpec {
  return { method: 'POST', path: '/orders', headers, body };
}

function get(path: string): RequestSpec {
  return { method: 'GET', path };
}

function json(fields: { [key: string]: Json }): string {
  return JSON.stringify(fields);
}

// Amount as raw text so that a 17-digit value reaches the wire unrounded
function orderJson(customerId: string, amountText: string, currency?: string | null): string {
  const currencyPart = currency === undefined ? '' : `,"currency":${JSON.stringify(currency)}`;
  return `{"customerId":${JSON.stringify(customerId)},"amount":${amountText}${currencyPart}}`;
}

function creates(
  id: string,
  group: Group,
  title: string,
  customerId: string,
  amountText: string,
  currency: string | null | undefined,
  outcome: Outcome,
  extra: Partial<Pick<OrderExpectation, 'reasonContains' | 'minSeconds'>> = {},
  body: string = orderJson(customerId, amountText, currency),
): Scenario {
  const expect: OrderExpectation = { kind: 'order', customerId, amount: canonicalAmount(amountText), currency: currency ?? 'CHF', outcome, ...extra };
  return { id, group, title, request: post(body), expect };
}

function rejects(
  id: string,
  group: Group,
  title: string,
  request: RequestSpec,
  status: number,
  fields?: string[],
  more: Partial<Pick<ProblemExpectation, 'title' | 'detail'>> = {},
): Scenario {
  const expect: ProblemExpectation = { kind: 'problem', status, ...(fields === undefined ? {} : { fields }), ...more };
  return { id, group, title, request, expect };
}

function lists(id: string, title: string, path: string, expect: Omit<PageExpectation, 'kind'>): Scenario {
  return { id, group: 'list', title, request: get(path), expect: { kind: 'page', ...expect } };
}

function probes(id: string, question: string, request: RequestSpec): Scenario {
  return { id, group: 'probe', title: question, request, expect: { kind: 'probe', question } };
}

function fails(options: CatalogueOptions): Pick<OrderExpectation, 'reasonContains' | 'minSeconds'> {
  return { reasonContains: FAILED_REASON, minSeconds: options.failMinSeconds };
}

const METRIC_NAMES = ['orders_accepted_total', 'orders_processed_total', 'orders_failed_total', 'orders_dead_lettered_total', 'order_processing_seconds', 'outbox_pending'];

// Everything that does not depend on data created in this run
export function phaseOne(options: CatalogueOptions): Scenario[] {
  const run = options.run;
  const invalid = `${run}-invalid`;
  const encoder = new TextEncoder();
  return [
    creates('create.minimal', 'create', 'minimal body, currency defaults to CHF', `${run}-min`, '42.5', undefined, 'PROCESSED'),
    creates('create.currency', 'create', 'explicit currency is kept', `${run}-eur`, '99.99', 'EUR', 'PROCESSED'),
    creates('create.amount-min', 'create', 'smallest amount 0.01', `${run}-amin`, '0.01', undefined, 'PROCESSED'),
    creates('create.amount-integer', 'create', 'integer amount is stored with two decimals', `${run}-aint`, '10', undefined, 'PROCESSED'),
    creates('create.amount-one-decimal', 'create', 'one decimal is padded to two', `${run}-adec`, '99.9', undefined, 'PROCESSED'),
    creates('create.amount-max', 'create', '17 integer digits, the largest amount the schema allows, survives unrounded', `${run}-amax`, '99999999999999999.99', undefined, 'PROCESSED'),
    creates('create.customer-64', 'create', 'customer id of exactly 64 characters', `${run}-`.padEnd(64, 'x'), '1', undefined, 'PROCESSED'),
    creates('create.customer-unicode', 'create', 'customer id with umlauts, CJK and an emoji is stored and listed verbatim', `${run}-Zürich-客户-🙂`, '1', undefined, 'PROCESSED'),
    creates('create.unknown-field', 'create', 'unknown JSON field is ignored', `${run}-extra`, '1', undefined, 'PROCESSED', {}, json({ customerId: `${run}-extra`, amount: 1, note: 'ignored' })),
    creates('create.currency-null', 'create', 'explicit null currency defaults to CHF', `${run}-null`, '1', null, 'PROCESSED'),
    creates('create.duplicate-1', 'create', 'identical request twice creates two orders: no deduplication', `${run}-dup`, '5', undefined, 'PROCESSED'),
    creates('create.duplicate-2', 'create', 'identical request twice creates two orders: no deduplication', `${run}-dup`, '5', undefined, 'PROCESSED'),
    ...generateOrders(options).map((order, index) =>
      creates(`create.generated-${index + 1}`, 'create', `generated order ${index + 1} of ${options.count}`, order.customerId, order.amount, order.currency, 'PROCESSED')),

    creates('processing.fail', 'processing', 'FAIL- customer: three deliveries, dead-letter queue, FAILED with a reason', `FAIL-${run}`, '10', undefined, 'FAILED', fails(options)),
    creates('processing.flaky', 'processing', 'FLAKY- customer: transient error retried in process, PROCESSED', `FLAKY-${run}`, '10', undefined, 'PROCESSED'),
    creates('processing.fail-lowercase', 'processing', 'prefix match is case-sensitive: fail- is a regular customer', `fail-${run}`, '10', undefined, 'PROCESSED'),
    creates('processing.fail-prefix-only', 'processing', 'customer id equal to the prefix fails', 'FAIL-', '10', undefined, 'FAILED', fails(options)),
    creates('processing.fail-before-flaky', 'processing', 'FAIL-FLAKY-: the permanent rule wins', `FAIL-FLAKY-${run}`, '10', undefined, 'FAILED', fails(options)),
    creates('processing.flaky-before-fail', 'processing', 'FLAKY-FAIL-: transient once, then PROCESSED', `FLAKY-FAIL-${run}`, '10', undefined, 'PROCESSED'),

    rejects('validation.customer-empty', 'validation', 'empty customer id', post(json({ customerId: '', amount: 10 })), 400, ['customerId']),
    rejects('validation.customer-blank', 'validation', 'blank customer id', post(json({ customerId: '   ', amount: 10 })), 400, ['customerId']),
    rejects('validation.customer-missing', 'validation', 'missing customer id', post(json({ amount: 10 })), 400, ['customerId']),
    rejects('validation.customer-65', 'validation', 'customer id of 65 characters', post(json({ customerId: `${run}-`.padEnd(65, 'x'), amount: 10 })), 400, ['customerId']),
    rejects('validation.amount-missing', 'validation', 'missing amount', post(json({ customerId: invalid })), 400, ['amount']),
    rejects('validation.amount-null', 'validation', 'null amount', post(json({ customerId: invalid, amount: null })), 400, ['amount']),
    rejects('validation.amount-zero', 'validation', 'zero amount', post(json({ customerId: invalid, amount: 0 })), 400, ['amount']),
    rejects('validation.amount-negative', 'validation', 'negative amount', post(json({ customerId: invalid, amount: -1 })), 400, ['amount']),
    rejects('validation.amount-three-decimals', 'validation', 'three decimals', post(orderJson(invalid, '1.234')), 400, ['amount']),
    rejects('validation.amount-18-digits', 'validation', '18 integer digits', post(orderJson(invalid, '123456789012345678')), 400, ['amount']),
    rejects('validation.amount-text', 'validation', 'amount is a word: unreadable body, no field list', post(json({ customerId: invalid, amount: 'abc' })), 400),
    rejects('validation.amount-boolean', 'validation', 'amount is a boolean: unreadable body', post(json({ customerId: invalid, amount: true })), 400),
    rejects('validation.currency-lowercase', 'validation', 'lower-case currency', post(json({ customerId: invalid, amount: 10, currency: 'chf' })), 400, ['currency']),
    rejects('validation.currency-short', 'validation', 'two-letter currency', post(json({ customerId: invalid, amount: 10, currency: 'CH' })), 400, ['currency']),
    rejects('validation.currency-long', 'validation', 'four-letter currency', post(json({ customerId: invalid, amount: 10, currency: 'CHFX' })), 400, ['currency']),
    rejects('validation.currency-empty', 'validation', 'empty currency', post(json({ customerId: invalid, amount: 10, currency: '' })), 400, ['currency']),
    rejects('validation.all-invalid', 'validation', 'three violations are reported together, sorted by field', post(json({ customerId: '', amount: -1, currency: 'chf' })), 400, ['amount', 'currency', 'customerId']),
    rejects('validation.malformed-json', 'validation', 'malformed JSON', post('{not json'), 400),
    rejects('validation.empty-body', 'validation', 'empty body', post(''), 400),
    rejects('validation.json-array', 'validation', 'JSON array instead of an object', post('[]'), 400),
    rejects('validation.json-null', 'validation', 'JSON null instead of an object', post('null'), 400),
    rejects('validation.text-plain', 'validation', 'valid JSON sent as text/plain', post(json({ customerId: invalid, amount: 10 }), { 'Content-Type': 'text/plain' }), 415),
    rejects('validation.no-content-type', 'validation', 'valid JSON without a Content-Type', post(encoder.encode(json({ customerId: invalid, amount: 10 })), {}), 415),

    rejects('read.unknown', 'read', 'unknown id', get('/orders/999999999'), 404, undefined, { title: 'Order not found', detail: 'order 999999999 not found' }),
    rejects('read.non-numeric', 'read', 'non-numeric id', get('/orders/abc'), 400),
    rejects('read.negative', 'read', 'negative id is a valid number that does not exist', get('/orders/-1'), 404),
    rejects('read.overflow', 'read', 'id beyond 64 bits', get('/orders/99999999999999999999'), 400),
    rejects('read.put', 'read', 'orders are not editable', { method: 'PUT', path: '/orders/1', headers: JSON_HEADERS, body: '{}' }, 405),
    rejects('read.delete', 'read', 'orders are not deletable', { method: 'DELETE', path: '/orders/1' }, 405),
    rejects('read.post-on-item', 'read', 'POST on a single order', { method: 'POST', path: '/orders/1', headers: JSON_HEADERS, body: json({ customerId: invalid, amount: 10 }) }, 405),

    { id: 'operations.readiness', group: 'operations', title: 'readiness probe', request: get('/actuator/health/readiness'), expect: { kind: 'health' } },
    { id: 'operations.liveness', group: 'operations', title: 'liveness probe', request: get('/actuator/health/liveness'), expect: { kind: 'health' } },
    { id: 'operations.metrics', group: 'operations', title: 'Prometheus endpoint exposes the business metrics', request: get('/actuator/prometheus'), expect: { kind: 'text', status: 200, contains: METRIC_NAMES } },
    { id: 'operations.openapi', group: 'operations', title: 'OpenAPI document describes both resources', request: get('/v3/api-docs'), expect: { kind: 'text', status: 200, contains: ['"/orders"', '"/orders/{id}"'] } },
    { id: 'operations.health-unknown', group: 'operations', title: 'unknown health group', request: get('/actuator/health/nope'), expect: { kind: 'text', status: 404, contains: [] } },

    probes('probe.amount-as-string', 'is a numeric string coerced to an amount or rejected?', post(json({ customerId: `${run}-probe`, amount: '10.00' }))),
    probes('probe.customer-padded', 'is surrounding whitespace in the customer id kept, trimmed or rejected?', post(json({ customerId: `  ${run}-pad  `, amount: 1 }))),
  ];
}

// Listing checks derive their expected totals from what phase one actually created
export function phaseTwo(options: CatalogueOptions, created: CreatedOrder[]): Scenario[] {
  const run = options.run;
  const burst = `${run}-burst`;
  const duplicates = `${run}-dup`;
  const failing = `FAIL-${run}`;
  const count = (customerId: string, status?: string): number =>
    created.filter((order) => order.customerId === customerId && (status === undefined || order.status === status)).length;
  const query = (customerId: string, more = ''): string => `/orders?customerId=${encodeURIComponent(customerId)}${more}`;
  const burstCount = count(burst);
  return [
    lists('list.burst-total', 'generated orders are all listed and processed', query(burst, '&size=100'), { totalElements: burstCount, contentLength: burstCount, everyStatus: 'PROCESSED', descendingIds: true }),
    lists('list.burst-paging', 'page size 3 pages the generated orders', query(burst, '&size=3'), { size: 3, number: 0, totalElements: burstCount, totalPages: Math.ceil(burstCount / 3), contentLength: Math.min(3, burstCount) }),
    lists('list.burst-last-page', 'the last page holds the remainder', query(burst, `&size=3&page=${Math.max(0, Math.ceil(burstCount / 3) - 1)}`), { contentLength: burstCount === 0 ? 0 : burstCount - 3 * (Math.ceil(burstCount / 3) - 1) }),
    lists('list.duplicates', 'two identical requests are two orders', query(duplicates), { totalElements: count(duplicates) }),
    lists('list.invalid-none', 'rejected requests persisted nothing', query(`${run}-invalid`), { totalElements: 0, contentLength: 0 }),
    lists('list.unicode-filter', 'the unicode customer id is found by filter', query(`${run}-Zürich-客户-🙂`), { totalElements: count(`${run}-Zürich-客户-🙂`) }),
    lists('list.status-failed', 'status filter FAILED finds the failed order', query(failing, '&status=FAILED'), { totalElements: count(failing, 'FAILED'), everyStatus: 'FAILED' }),
    lists('list.status-processed-excludes', 'status filter PROCESSED excludes the failed order', query(failing, '&status=PROCESSED'), { totalElements: count(failing, 'PROCESSED') }),
    lists('list.size-capped', 'page size is capped at 100', '/orders?size=1000', { size: 100 }),
    lists('list.size-zero', 'page size 0 falls back to the default 20', '/orders?size=0', { size: 20 }),
    lists('list.page-negative', 'negative page falls back to page 0', '/orders?page=-1', { number: 0 }),
    lists('list.page-text', 'non-numeric page falls back to page 0', '/orders?page=abc', { number: 0 }),
    lists('list.newest-first', 'default order is newest first', '/orders?size=5', { size: 5, descendingIds: true }),
    rejects('list.unknown-status', 'list', 'unknown status filter', get('/orders?status=DONE'), 400),
  ];
}
