import assert from 'node:assert/strict';
import { test } from 'node:test';
import { ApiClient } from '../src/http.ts';
import type { Result } from '../src/model.ts';
import { runAll, runCatalogue, type RunOptions } from '../src/runner.ts';
import { phaseOne, phaseTwo, type CatalogueOptions } from '../src/scenarios.ts';
import { FakeApi, type Defect } from './fake-api.ts';

const fast: RunOptions = { concurrency: 8, pollMs: 15, processedTimeoutMs: 2500, failedTimeoutMs: 4000 };
const catalogue: CatalogueOptions = { run: 'trun', count: 4, seed: 7, failMinSeconds: 0.3 };

async function run(defects: Defect[] = [], options: RunOptions = fast): Promise<Result[]> {
  const api = await FakeApi.start({ defects, failMs: 400, processingMs: 50 });
  try {
    return (await runCatalogue(new ApiClient(api.baseUrl, 2000), catalogue, options)).results;
  } finally {
    await api.stop();
  }
}

function failed(results: Result[]): string[] {
  return results.filter((result) => result.verdict === 'fail').map((result) => `${result.id}: ${result.problems.join('; ')}`);
}

function failedIds(results: Result[]): string[] {
  return results.filter((result) => result.verdict === 'fail').map((result) => result.id);
}

test('a conforming service passes every checked scenario', async () => {
  const results = await run();
  assert.deepEqual(failed(results), []);
  assert.equal(results.length, phaseOne(catalogue).length + phaseTwo(catalogue, []).length);
  assert.equal(results.filter((result) => result.verdict === 'observed').length, 2);
  const fail = results.find((result) => result.id === 'processing.fail');
  assert.match(fail?.actual ?? '', /^201, FAILED after/);
});

test('a service that never dead-letters is caught', async () => {
  const ids = failedIds(await run(['no-dead-letter']));
  assert.ok(ids.includes('processing.fail'), ids.join(', '));
  assert.ok(ids.includes('processing.fail-before-flaky'), ids.join(', '));
  // The listing expectations follow what was created, so the filter check itself still holds
  assert.ok(!ids.includes('list.status-failed'), ids.join(', '));
  assert.ok(!ids.includes('create.minimal'), ids.join(', '));
});

test('a missing PROCESSING transition is caught on every order', async () => {
  const results = await run(['skip-processing-row']);
  const orders = results.filter((result) => result.expected.startsWith('201, then'));
  assert.ok(orders.length > 10);
  for (const order of orders) {
    assert.equal(order.verdict, 'fail', order.id);
    assert.ok(order.problems.some((problem) => problem.startsWith('history ACCEPTED > ')), order.problems.join('; '));
  }
});

test('switched-off validation is caught, including through the persisted rows', async () => {
  const ids = failedIds(await run(['accept-invalid']));
  assert.ok(ids.includes('validation.amount-zero'), ids.join(', '));
  assert.ok(ids.includes('validation.currency-lowercase'), ids.join(', '));
  assert.ok(ids.includes('list.invalid-none'), ids.join(', '));
});

test('orders that never become final are reported with their last status', async () => {
  const results = await run(['never-final'], { ...fast, processedTimeoutMs: 300, failedTimeoutMs: 300 });
  const minimal = results.find((result) => result.id === 'create.minimal');
  assert.equal(minimal?.verdict, 'fail');
  assert.ok(minimal?.problems.some((problem) => problem.startsWith('final status PROCESSING')), minimal?.problems.join('; '));
});

test('a 500 on an unknown id and a lost currency default are caught', async () => {
  const ids = failedIds(await run(['unknown-is-500', 'no-currency-default']));
  assert.ok(ids.includes('read.unknown'), ids.join(', '));
  assert.ok(ids.includes('create.minimal'), ids.join(', '));
  assert.ok(!ids.includes('create.currency'), ids.join(', '));
});

test('deduplicated requests are caught through the listing', async () => {
  const ids = failedIds(await run(['dedupe']));
  assert.ok(ids.includes('list.duplicates'), ids.join(', '));
});

test('an unreachable target yields failed results instead of an exception', async () => {
  const api = await FakeApi.start();
  const url = api.baseUrl;
  await api.stop();
  const scenario = phaseOne(catalogue).find((candidate) => candidate.id === 'read.unknown');
  assert.ok(scenario);
  const results = await runAll(new ApiClient(url, 500), [scenario], fast, []);
  assert.equal(results[0]?.verdict, 'fail');
  assert.match(results[0]?.actual ?? '', /^request failed: /);
});
