import { mkdir, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { parseArgs } from 'node:util';
import { ApiClient } from './http.ts';
import type { Result } from './model.ts';
import { renderJson, renderMarkdown, summarize, type RunReport } from './report.ts';
import { runCatalogue, type RunOptions } from './runner.ts';
import { DEFAULT_FAIL_MIN_SECONDS, runToken, type CatalogueOptions } from './scenarios.ts';

const USAGE = `Usage: node src/main.ts [options]
  --base-url <url>        service under test (default http://localhost:8080)
  --out <dir>             report directory (default <tool>/report)
  --count <n>             generated regular orders (default 10)
  --seed <n>              seed for the generated data and the run token (default: current time)
  --timeout <seconds>     wait for a FAILED order; a PROCESSED order gets half (default 120)
  --fail-min-seconds <s>  redelivery back-off a FAILED order must have taken (default ${DEFAULT_FAIL_MIN_SECONDS})
  --concurrency <n>       scenarios in flight (default 8)
  --poll <ms>             poll interval while an order is not final (default 500)
  --quiet                 print only the summary
  --help`;

class UsageError extends Error {}

function integer(name: string, value: string, min: number): number {
  const parsed = Number(value);
  if (!Number.isFinite(parsed) || parsed < min || (min >= 1 && !Number.isInteger(parsed))) {
    throw new UsageError(`--${name} must be a number of at least ${min}, got "${value}"`);
  }
  return parsed;
}

function printResult(result: Result): void {
  const label = result.verdict === 'pass' ? 'PASS' : result.verdict === 'fail' ? 'FAIL' : 'NOTE';
  console.log(`${label}  ${result.id.padEnd(36)} ${result.actual}`);
  for (const problem of result.problems) {
    console.log(`      - ${problem}`);
  }
}

async function main(): Promise<number> {
  const { values } = parseArgs({
    options: {
      'base-url': { type: 'string', default: 'http://localhost:8080' },
      out: { type: 'string', default: path.join(import.meta.dirname, '..', 'report') },
      count: { type: 'string', default: '10' },
      seed: { type: 'string', default: String(Date.now() % 2_147_483_647) },
      timeout: { type: 'string', default: '120' },
      'fail-min-seconds': { type: 'string', default: String(DEFAULT_FAIL_MIN_SECONDS) },
      concurrency: { type: 'string', default: '8' },
      poll: { type: 'string', default: '500' },
      quiet: { type: 'boolean', default: false },
      help: { type: 'boolean', default: false },
    },
  });
  if (values.help) {
    console.log(USAGE);
    return 0;
  }
  const seed = integer('seed', values.seed, 0);
  const catalogue: CatalogueOptions = {
    run: runToken(seed),
    count: integer('count', values.count, 0),
    seed,
    failMinSeconds: integer('fail-min-seconds', values['fail-min-seconds'], 0),
  };
  const timeoutSeconds = integer('timeout', values.timeout, 1);
  const options: RunOptions = {
    concurrency: integer('concurrency', values.concurrency, 1),
    pollMs: integer('poll', values.poll, 1),
    processedTimeoutMs: (timeoutSeconds * 1000) / 2,
    failedTimeoutMs: timeoutSeconds * 1000,
  };
  const client = new ApiClient(values['base-url']);
  const readiness = await client.send({ method: 'GET', path: '/actuator/health/readiness' }).catch(() => undefined);
  if (readiness === undefined || readiness.status !== 200) {
    console.error(`${client.baseUrl} is not ready (${readiness?.status ?? 'unreachable'}); nothing was run`);
    return 2;
  }
  const startedAt = new Date();
  console.log(`Run ${catalogue.run} against ${client.baseUrl}: customer ids start with "${catalogue.run}-", seed ${seed}`);
  const { results } = await runCatalogue(client, catalogue, options, values.quiet ? undefined : printResult);
  const summary = summarize(results);
  const report: RunReport = {
    target: client.baseUrl,
    run: catalogue.run,
    startedAt: startedAt.toISOString(),
    durationMs: Date.now() - startedAt.getTime(),
    settings: { seed, count: catalogue.count, timeoutSeconds, failMinSeconds: catalogue.failMinSeconds, concurrency: options.concurrency, pollMs: options.pollMs },
    summary,
    results,
  };
  await mkdir(values.out, { recursive: true });
  const markdown = path.join(values.out, 'report.md');
  await writeFile(markdown, renderMarkdown(report));
  await writeFile(path.join(values.out, 'report.json'), renderJson(report));
  console.log(`${summary.fail === 0 ? 'PASS' : 'FAIL'}: ${summary.pass} passed, ${summary.fail} failed, ${summary.observed} observed in ${(report.durationMs / 1000).toFixed(1)} s; report ${markdown}`);
  return summary.fail === 0 ? 0 : 1;
}

try {
  process.exitCode = await main();
} catch (error) {
  if (error instanceof UsageError) {
    console.error(error.message);
    console.error(USAGE);
    process.exitCode = 2;
  } else {
    throw error;
  }
}
