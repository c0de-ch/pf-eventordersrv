import assert from 'node:assert/strict';
import { test } from 'node:test';
import type { Result } from '../src/model.ts';
import { renderJson, renderMarkdown, summarize, type RunReport } from '../src/report.ts';

const results: Result[] = [
  { id: 'create.minimal', group: 'create', title: 'minimal body', request: 'POST /orders {"a":1}', expected: '201, then PROCESSED', actual: '201, PROCESSED after 1.2 s', verdict: 'pass', problems: [], durationMs: 1200 },
  { id: 'processing.fail', group: 'processing', title: 'FAIL- customer', request: 'POST /orders {"customerId":"FAIL-x|y"}', expected: '201, then FAILED', actual: '201, PROCESSED after 0.9 s', verdict: 'fail', problems: ['final status PROCESSED after 0.9 s, expected FAILED'], durationMs: 900 },
  { id: 'probe.amount-as-string', group: 'probe', title: 'is a numeric string coerced?', request: 'POST /orders {"amount":"10.00"}', expected: 'observed', actual: '201, order 5 created', verdict: 'observed', problems: [], durationMs: 50 },
];

const report: RunReport = {
  target: 'http://localhost:8080',
  run: 't1',
  startedAt: '2026-10-24T18:00:00.000Z',
  durationMs: 2150,
  settings: { seed: 1, count: 10 },
  summary: summarize(results),
  results,
};

test('summarize counts verdicts in total and per group', () => {
  const summary = summarize(results);
  assert.equal(summary.total, 3);
  assert.equal(summary.pass, 1);
  assert.equal(summary.fail, 1);
  assert.equal(summary.observed, 1);
  assert.deepEqual(summary.byGroup.processing, { pass: 0, fail: 1, observed: 0 });
  assert.deepEqual(summary.byGroup.list, { pass: 0, fail: 0, observed: 0 });
});

test('markdown states the verdict, lists failures first and escapes table pipes', () => {
  const markdown = renderMarkdown(report);
  assert.ok(markdown.includes('Verdict: **FAIL**, 1 of 2 checked scenarios behaved as specified, 1 observed.'));
  assert.ok(markdown.indexOf('## Failures') < markdown.indexOf('## Scenarios'));
  assert.ok(markdown.includes('### processing.fail: FAIL- customer'));
  assert.ok(markdown.includes('FAIL-x\\|y'));
  assert.ok(markdown.includes('## Observations'));
  assert.ok(!markdown.includes('| OBSERVED |'));
});

test('markdown of a clean run has no failure section', () => {
  const clean = { ...report, results: results.filter((result) => result.verdict !== 'fail') };
  const markdown = renderMarkdown({ ...clean, summary: summarize(clean.results) });
  assert.ok(markdown.includes('Verdict: **PASS**'));
  assert.ok(!markdown.includes('## Failures'));
});

test('json report round-trips', () => {
  const parsed = JSON.parse(renderJson(report)) as RunReport;
  assert.equal(parsed.summary.fail, 1);
  assert.equal(parsed.results.length, 3);
  assert.equal(parsed.run, 't1');
});
