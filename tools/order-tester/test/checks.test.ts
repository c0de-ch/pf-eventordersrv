import assert from 'node:assert/strict';
import { test } from 'node:test';
import { canonicalAmount, historyChecks, instant, pageChecks, problemChecks, rawAmount, timestampChecks } from '../src/checks.ts';
import type { HttpResponse, Json } from '../src/http.ts';

function response(status: number, body: string, type = 'application/json'): HttpResponse {
  return { status, headers: new Headers({ 'content-type': type }), text: body, json: JSON.parse(body) as Json };
}

const t0 = '2026-10-24T18:22:10.000001Z';
const t1 = '2026-10-24T18:22:11.000001Z';
const t2 = '2026-10-24T18:22:14.500000Z';

test('canonicalAmount prints two decimals', () => {
  assert.equal(canonicalAmount('10'), '10.00');
  assert.equal(canonicalAmount('99.9'), '99.90');
  assert.equal(canonicalAmount('0.01'), '0.01');
  assert.equal(canonicalAmount('99999999999999999.99'), '99999999999999999.99');
});

test('rawAmount keeps the digits JSON.parse would round', () => {
  const text = '{"id":1,"amount":99999999999999999.99,"status":"ACCEPTED"}';
  assert.equal(rawAmount(text), '99999999999999999.99');
  assert.notEqual(String((JSON.parse(text) as { amount: number }).amount), '99999999999999999.99');
});

test('instant parses microsecond precision and rejects other formats', () => {
  assert.equal(instant('2026-10-24T18:22:10Z'), Date.parse('2026-10-24T18:22:10Z'));
  assert.equal(instant('2026-10-24T18:22:10.123456Z'), Date.parse('2026-10-24T18:22:10Z') + 123.456);
  assert.equal(instant('24.10.2026 18:22'), undefined);
  assert.equal(instant(5), undefined);
});

test('historyChecks accepts a consistent failed chain', () => {
  const body = { history: [
    { from: null, to: 'ACCEPTED', reason: null, at: t0 },
    { from: 'ACCEPTED', to: 'PROCESSING', reason: null, at: t1 },
    { from: 'PROCESSING', to: 'FAILED', reason: 'dead-lettered after repeated processing failures', at: t2 },
  ] };
  assert.deepEqual(historyChecks(body, ['ACCEPTED', 'PROCESSING', 'FAILED'], 'dead-lettered'), []);
});

test('historyChecks reports a broken chain, a backwards timestamp and a missing reason', () => {
  const body = { history: [
    { from: null, to: 'ACCEPTED', reason: null, at: t1 },
    { from: 'ACCEPTED', to: 'FAILED', reason: null, at: t0 },
  ] };
  const problems = historyChecks(body, ['ACCEPTED', 'PROCESSING', 'FAILED'], 'dead-lettered');
  assert.ok(problems.some((p) => p.includes('expected ACCEPTED > PROCESSING > FAILED')), problems.join('; '));
  assert.ok(problems.some((p) => p.includes('earlier than the previous transition')), problems.join('; '));
  assert.ok(problems.some((p) => p.includes('final reason null')), problems.join('; '));
});

test('historyChecks rejects a reason on a successful transition and a wrong from', () => {
  const body = { history: [
    { from: null, to: 'ACCEPTED', reason: 'why', at: t0 },
    { from: 'PROCESSING', to: 'PROCESSED', reason: null, at: t1 },
  ] };
  const problems = historyChecks(body, ['ACCEPTED', 'PROCESSED']);
  assert.ok(problems.some((p) => p.includes('reason without a failure')), problems.join('; '));
  assert.ok(problems.some((p) => p.includes('history[1].from "PROCESSING", expected "ACCEPTED"')), problems.join('; '));
});

test('problemChecks accepts an RFC 9457 body with sorted field errors', () => {
  const body = JSON.stringify({ type: 'about:blank', title: 'Bad Request', status: 400, detail: 'Invalid request content.', instance: '/orders',
    errors: [{ field: 'amount', message: 'must be greater than 0' }, { field: 'customerId', message: 'must not be blank' }] });
  const res = response(400, body, 'application/problem+json');
  assert.deepEqual(problemChecks(res, { kind: 'problem', status: 400, fields: ['amount', 'customerId'] }, '/orders'), []);
});

test('problemChecks reports status, media type, field order, instance and leaked internals', () => {
  const body = JSON.stringify({ title: 'Bad Request', status: 400, instance: '/other', stackTrace: 'x',
    errors: [{ field: 'customerId', message: 'must not be blank' }, { field: 'amount', message: '' }] });
  const problems = problemChecks(response(200, body), { kind: 'problem', status: 400, fields: ['amount', 'customerId'] }, '/orders');
  assert.ok(problems.some((p) => p === 'status 200, expected 400'));
  assert.ok(problems.some((p) => p.includes('expected application/problem+json')));
  assert.ok(problems.some((p) => p.includes('errors on [customerId, amount]')));
  assert.ok(problems.some((p) => p.includes('instance "/other"')));
  assert.ok(problems.some((p) => p.includes('leaks internals')));
  assert.ok(problems.some((p) => p.includes('no message')));
});

test('pageChecks verifies totals, the cap and the status filter', () => {
  const page = (size: number, statuses: string[]): HttpResponse => response(200, JSON.stringify({
    content: statuses.map((status, index) => ({ id: 100 - index, status })),
    page: { size, number: 0, totalElements: statuses.length, totalPages: 1 },
  }));
  assert.deepEqual(pageChecks(page(100, ['PROCESSED', 'PROCESSED']), { kind: 'page', size: 100, totalElements: 2, everyStatus: 'PROCESSED', descendingIds: true }), []);
  const problems = pageChecks(page(20, ['PROCESSED', 'FAILED']), { kind: 'page', size: 100, totalElements: 3, everyStatus: 'PROCESSED' });
  assert.ok(problems.some((p) => p === 'page.size 20, expected 100'));
  assert.ok(problems.some((p) => p === 'page.totalElements 2, expected 3'));
  assert.ok(problems.some((p) => p.includes('1 entries with status FAILED')));
});

test('timestampChecks reports an update before the creation', () => {
  const problems = timestampChecks({ createdAt: t1, updatedAt: t0, history: [{ from: null, to: 'ACCEPTED', reason: null, at: t1 }] });
  assert.deepEqual(problems, ['updatedAt is earlier than createdAt']);
  assert.deepEqual(timestampChecks({ createdAt: t0, updatedAt: t1, history: [{ from: null, to: 'ACCEPTED', reason: null, at: t0 }, { from: 'ACCEPTED', to: 'PROCESSED', reason: null, at: t1 }] }), []);
});
