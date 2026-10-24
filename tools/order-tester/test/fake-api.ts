import { createServer, type IncomingMessage, type Server, type ServerResponse } from 'node:http';
import type { AddressInfo } from 'node:net';
import { setTimeout as delay } from 'node:timers/promises';

// Defects the tester must detect; a fake without defects follows the published contract
export type Defect =
  | 'no-dead-letter'
  | 'skip-processing-row'
  | 'accept-invalid'
  | 'never-final'
  | 'unknown-is-500'
  | 'dedupe'
  | 'no-currency-default';

export interface FakeOptions {
  processingMs?: number;
  failMs?: number;
  defects?: Defect[];
}

interface Transition {
  from: string | null;
  to: string;
  reason: string | null;
  at: number;
}

interface StoredOrder {
  id: number;
  customerId: string;
  amount: string;
  currency: string;
  status: string;
  createdAt: number;
  updatedAt: number;
  history: Transition[];
}

type Reply = (status: number, payload: string, headers?: Record<string, string>) => void;

const STATUSES = new Set(['ACCEPTED', 'PROCESSING', 'PROCESSED', 'FAILED']);
const LONG_MAX = 9223372036854775807n;
const JSON_TYPE = { 'Content-Type': 'application/json' };
const PROBLEM_TYPE = { 'Content-Type': 'application/problem+json' };
const METRICS = ['orders_accepted_total', 'orders_processed_total', 'orders_failed_total', 'orders_dead_lettered_total', 'order_processing_seconds_count', 'outbox_pending']
  .map((name) => `# TYPE ${name} untyped\n${name} 1.0\n`).join('');

export class FakeApi {
  readonly orders = new Map<number, StoredOrder>();
  private readonly server: Server;
  private readonly options: Required<FakeOptions>;
  private nextId = 1;
  private port = 0;

  private constructor(options: FakeOptions) {
    this.options = { processingMs: options.processingMs ?? 60, failMs: options.failMs ?? 400, defects: options.defects ?? [] };
    this.server = createServer((request, response) => {
      void this.handle(request, response);
    });
  }

  static async start(options: FakeOptions = {}): Promise<FakeApi> {
    const api = new FakeApi(options);
    await new Promise<void>((resolve) => api.server.listen(0, '127.0.0.1', resolve));
    api.port = (api.server.address() as AddressInfo).port;
    return api;
  }

  get baseUrl(): string {
    return `http://127.0.0.1:${this.port}`;
  }

  async stop(): Promise<void> {
    this.server.closeAllConnections();
    await new Promise<void>((resolve, reject) => this.server.close((error) => (error ? reject(error) : resolve())));
  }

  private has(defect: Defect): boolean {
    return this.options.defects.includes(defect);
  }

  private async handle(request: IncomingMessage, response: ServerResponse): Promise<void> {
    const url = new URL(request.url ?? '/', this.baseUrl);
    const method = request.method ?? 'GET';
    const body = await readBody(request);
    const reply: Reply = (status, payload, headers = {}) => {
      response.writeHead(status, headers);
      response.end(payload);
    };
    if (url.pathname === '/actuator/health/readiness' || url.pathname === '/actuator/health/liveness') {
      return reply(200, '{"status":"UP"}', JSON_TYPE);
    }
    if (url.pathname.startsWith('/actuator/health/')) {
      return reply(404, '');
    }
    if (url.pathname === '/actuator/prometheus') {
      return reply(200, METRICS, { 'Content-Type': 'text/plain; version=0.0.4; charset=utf-8' });
    }
    if (url.pathname === '/v3/api-docs') {
      return reply(200, '{"openapi":"3.1.0","paths":{"/orders":{"post":{}},"/orders/{id}":{"get":{}}}}', JSON_TYPE);
    }
    if (url.pathname === '/orders') {
      if (method === 'POST') {
        return this.create(request, body, reply);
      }
      if (method === 'GET') {
        return this.list(url, reply);
      }
      return problem(reply, 405, 'Method Not Allowed', url.pathname);
    }
    const item = /^\/orders\/([^/]+)$/.exec(url.pathname);
    if (item !== null) {
      if (method !== 'GET') {
        return problem(reply, 405, 'Method Not Allowed', url.pathname);
      }
      return this.read(item[1] ?? '', url.pathname, reply);
    }
    return problem(reply, 404, 'Not Found', url.pathname);
  }

  private create(request: IncomingMessage, body: string, reply: Reply): void {
    const type = request.headers['content-type'] ?? '';
    if (!type.startsWith('application/json')) {
      return problem(reply, 415, 'Unsupported Media Type', '/orders', { detail: `Content-Type '${type}' is not supported.` });
    }
    if (body.trim() === '') {
      return problem(reply, 400, 'Bad Request', '/orders', { detail: 'Required request body is missing' });
    }
    let parsed: unknown;
    try {
      parsed = JSON.parse(body);
    } catch {
      return problem(reply, 400, 'Bad Request', '/orders', { detail: 'Failed to read request' });
    }
    if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
      return problem(reply, 400, 'Bad Request', '/orders', { detail: 'Failed to read request' });
    }
    const fields = parsed as Record<string, unknown>;
    const amountText = /"amount"\s*:\s*(-?\d+(?:\.\d+)?)\s*[,}]/.exec(body)?.[1];
    if ('amount' in fields && fields['amount'] !== null && amountText === undefined) {
      return problem(reply, 400, 'Bad Request', '/orders', { detail: 'Failed to read request' });
    }
    const errors: { field: string; message: string }[] = [];
    const customerId = fields['customerId'];
    if (typeof customerId !== 'string' || customerId.trim() === '') {
      errors.push({ field: 'customerId', message: 'must not be blank' });
    } else if (customerId.length > 64) {
      errors.push({ field: 'customerId', message: 'size must be between 0 and 64' });
    }
    if (amountText === undefined) {
      errors.push({ field: 'amount', message: 'must not be null' });
    } else {
      const [whole = '', fraction = ''] = amountText.replace('-', '').split('.');
      if (Number(amountText) <= 0) {
        errors.push({ field: 'amount', message: 'must be greater than 0' });
      }
      if (whole.length > 17 || fraction.length > 2) {
        errors.push({ field: 'amount', message: 'numeric value out of bounds (<17 digits>.<2 digits> expected)' });
      }
    }
    const currency = fields['currency'];
    if (currency !== undefined && currency !== null && (typeof currency !== 'string' || !/^[A-Z]{3}$/.test(currency))) {
      errors.push({ field: 'currency', message: 'must match "[A-Z]{3}"' });
    }
    if (errors.length > 0 && !this.has('accept-invalid')) {
      errors.sort((a, b) => a.field.localeCompare(b.field));
      return problem(reply, 400, 'Bad Request', '/orders', { detail: 'Invalid request content.', errors });
    }
    const now = Date.now();
    const stored: StoredOrder = {
      id: this.nextId++,
      customerId: String(customerId ?? ''),
      amount: canonical(amountText ?? '0'),
      currency: currency === undefined || currency === null ? (this.has('no-currency-default') ? 'EUR' : 'CHF') : String(currency),
      status: 'ACCEPTED',
      createdAt: now,
      updatedAt: now,
      history: [{ from: null, to: 'ACCEPTED', reason: null, at: now }],
    };
    if (this.has('dedupe')) {
      const existing = [...this.orders.values()].find((order) => order.customerId === stored.customerId && order.amount === stored.amount);
      if (existing !== undefined) {
        return reply(201, render(existing), { ...JSON_TYPE, Location: `${this.baseUrl}/orders/${existing.id}` });
      }
    }
    this.orders.set(stored.id, stored);
    void this.process(stored);
    reply(201, render(stored), { ...JSON_TYPE, Location: `${this.baseUrl}/orders/${stored.id}` });
  }

  private async process(order: StoredOrder): Promise<void> {
    await delay(20);
    if (!this.has('skip-processing-row')) {
      transition(order, 'PROCESSING', null);
    }
    if (this.has('never-final')) {
      return;
    }
    if (order.customerId.startsWith('FAIL-') && !this.has('no-dead-letter')) {
      await delay(this.options.failMs);
      transition(order, 'FAILED', 'dead-lettered after repeated processing failures');
    } else {
      await delay(this.options.processingMs);
      transition(order, 'PROCESSED', null);
    }
  }

  private read(raw: string, pathname: string, reply: Reply): void {
    if (!/^-?\d+$/.test(raw)) {
      return problem(reply, 400, 'Bad Request', pathname, { detail: `Failed to convert 'id' with value: '${raw}'` });
    }
    if (BigInt(raw) > LONG_MAX || BigInt(raw) < -LONG_MAX - 1n) {
      return problem(reply, 400, 'Bad Request', pathname, { detail: `Failed to convert 'id' with value: '${raw}'` });
    }
    const order = this.orders.get(Number(raw));
    if (order === undefined) {
      return this.has('unknown-is-500')
        ? problem(reply, 500, 'Internal error', pathname, { detail: 'Unexpected error' })
        : problem(reply, 404, 'Order not found', pathname, { detail: `order ${raw} not found` });
    }
    reply(200, render(order), JSON_TYPE);
  }

  private list(url: URL, reply: Reply): void {
    const status = url.searchParams.get('status');
    if (status !== null && !STATUSES.has(status)) {
      return problem(reply, 400, 'Bad Request', '/orders', { detail: `Failed to convert 'status' with value: '${status}'` });
    }
    const customerId = url.searchParams.get('customerId');
    const size = clamp(url.searchParams.get('size'), 1, 100, 20);
    const number = clamp(url.searchParams.get('page'), 0, Number.MAX_SAFE_INTEGER, 0);
    const all = [...this.orders.values()]
      .filter((order) => (customerId === null || order.customerId === customerId) && (status === null || order.status === status))
      .sort((a, b) => b.id - a.id);
    const content = all.slice(number * size, number * size + size).map((order) => render(order, false)).join(',');
    const page = `{"size":${size},"number":${number},"totalElements":${all.length},"totalPages":${Math.ceil(all.length / size)}}`;
    reply(200, `{"content":[${content}],"page":${page}}`, JSON_TYPE);
  }
}

function clamp(raw: string | null, min: number, max: number, fallback: number): number {
  const parsed = Number.parseInt(raw ?? '', 10);
  if (Number.isNaN(parsed) || parsed < min) {
    return fallback;
  }
  return Math.min(parsed, max);
}

function transition(order: StoredOrder, to: string, reason: string | null): void {
  const at = Date.now();
  order.history.push({ from: order.status, to, reason, at });
  order.status = to;
  order.updatedAt = at;
}

function canonical(amountText: string): string {
  const [whole = '', fraction = ''] = amountText.split('.');
  return `${whole}.${fraction.padEnd(2, '0')}`;
}

// Six fraction digits like the service; the amount is spliced in as text to keep 17-digit values exact
function render(order: StoredOrder, withHistory = true): string {
  const view: Record<string, unknown> = {
    id: order.id,
    customerId: order.customerId,
    amount: '__AMOUNT__',
    currency: order.currency,
    status: order.status,
    createdAt: iso(order.createdAt),
    updatedAt: iso(order.updatedAt),
  };
  if (withHistory) {
    view['history'] = order.history.map((entry) => ({ from: entry.from, to: entry.to, reason: entry.reason, at: iso(entry.at) }));
  }
  return JSON.stringify(view).replace('"__AMOUNT__"', order.amount);
}

function iso(millis: number): string {
  return new Date(millis).toISOString().replace('Z', '000Z');
}

function problem(reply: Reply, status: number, title: string, instance: string, extra: Record<string, unknown> = {}): void {
  reply(status, JSON.stringify({ type: 'about:blank', title, status, instance, ...extra }), PROBLEM_TYPE);
}

async function readBody(request: IncomingMessage): Promise<string> {
  const chunks: Buffer[] = [];
  for await (const chunk of request) {
    chunks.push(Buffer.from(chunk as Buffer));
  }
  return Buffer.concat(chunks).toString('utf8');
}
