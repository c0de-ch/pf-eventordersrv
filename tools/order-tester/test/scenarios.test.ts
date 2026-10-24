import assert from 'node:assert/strict';
import { test } from 'node:test';
import { GROUPS } from '../src/model.ts';
import { CURRENCIES, generateOrders, phaseOne, phaseTwo, runToken, type CatalogueOptions } from '../src/scenarios.ts';

const options: CatalogueOptions = { run: 'tabc', count: 7, seed: 42, failMinSeconds: 3 };

test('scenario ids are unique and every group is covered', () => {
  const scenarios = [...phaseOne(options), ...phaseTwo(options, [])];
  const ids = scenarios.map((scenario) => scenario.id);
  assert.equal(new Set(ids).size, ids.length);
  for (const group of GROUPS) {
    assert.ok(scenarios.some((scenario) => scenario.group === group), `group ${group} is empty`);
  }
});

test('generated orders are deterministic for a seed and valid for the schema', () => {
  const orders = generateOrders(options);
  assert.equal(orders.length, 7);
  assert.deepEqual(generateOrders(options), orders);
  assert.notDeepEqual(generateOrders({ ...options, seed: 43 }), orders);
  for (const order of orders) {
    assert.match(order.amount, /^\d{1,4}(\.\d{1,2})?$/);
    assert.ok(Number(order.amount) > 0);
    assert.ok((CURRENCIES as readonly string[]).includes(order.currency));
    assert.equal(order.customerId, 'tabc-burst');
  }
});

test('the run token prefixes every customer id this run creates', () => {
  assert.equal(runToken(42), 't16');
  const created = phaseOne(options).filter((scenario) => scenario.expect.kind === 'order');
  for (const scenario of created) {
    const customerId = scenario.expect.kind === 'order' ? scenario.expect.customerId : '';
    assert.ok(customerId.includes('tabc') || customerId.startsWith('FAIL-'), `${scenario.id}: ${customerId}`);
    assert.ok(customerId.length <= 64, `${scenario.id}: ${customerId.length} characters`);
  }
});

test('every validation scenario expects a client error problem detail', () => {
  const validation = phaseOne(options).filter((scenario) => scenario.group === 'validation');
  assert.ok(validation.length >= 20);
  for (const scenario of validation) {
    assert.equal(scenario.expect.kind, 'problem', scenario.id);
    assert.ok(scenario.expect.kind === 'problem' && [400, 415].includes(scenario.expect.status), scenario.id);
  }
});

test('phase two derives its totals from what phase one created', () => {
  const created = [
    ...Array.from({ length: 7 }, (_, index) => ({ scenarioId: `create.generated-${index + 1}`, id: index + 1, customerId: 'tabc-burst', status: 'PROCESSED' })),
    { scenarioId: 'create.duplicate-1', id: 8, customerId: 'tabc-dup', status: 'PROCESSED' },
    { scenarioId: 'create.duplicate-2', id: 9, customerId: 'tabc-dup', status: 'PROCESSED' },
    { scenarioId: 'processing.fail', id: 10, customerId: 'FAIL-tabc', status: 'FAILED' },
  ];
  const byId = new Map(phaseTwo(options, created).map((scenario) => [scenario.id, scenario.expect]));
  assert.deepEqual(byId.get('list.burst-total'), { kind: 'page', totalElements: 7, contentLength: 7, everyStatus: 'PROCESSED', descendingIds: true });
  assert.deepEqual(byId.get('list.burst-paging'), { kind: 'page', size: 3, number: 0, totalElements: 7, totalPages: 3, contentLength: 3 });
  assert.deepEqual(byId.get('list.burst-last-page'), { kind: 'page', contentLength: 1 });
  assert.deepEqual(byId.get('list.duplicates'), { kind: 'page', totalElements: 2 });
  assert.deepEqual(byId.get('list.status-failed'), { kind: 'page', totalElements: 1, everyStatus: 'FAILED' });
  assert.deepEqual(byId.get('list.status-processed-excludes'), { kind: 'page', totalElements: 0 });
});
