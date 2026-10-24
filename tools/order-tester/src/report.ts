import { GROUPS, type Group, type Result } from './model.ts';

export interface GroupTotals {
  pass: number;
  fail: number;
  observed: number;
}

export interface Summary extends GroupTotals {
  total: number;
  byGroup: Record<Group, GroupTotals>;
}

export interface RunReport {
  target: string;
  run: string;
  startedAt: string;
  durationMs: number;
  settings: Record<string, string | number>;
  summary: Summary;
  results: Result[];
}

export function summarize(results: Result[]): Summary {
  const byGroup = Object.fromEntries(GROUPS.map((group) => [group, { pass: 0, fail: 0, observed: 0 }])) as Record<Group, GroupTotals>;
  const summary: Summary = { total: results.length, pass: 0, fail: 0, observed: 0, byGroup };
  for (const result of results) {
    summary[result.verdict]++;
    byGroup[result.group][result.verdict]++;
  }
  return summary;
}

export function renderJson(report: RunReport): string {
  return `${JSON.stringify(report, null, 2)}\n`;
}

export function renderMarkdown(report: RunReport): string {
  const summary = report.summary;
  const checked = summary.pass + summary.fail;
  const lines: string[] = ['# Order service black-box report', ''];
  const verdict = summary.fail === 0 ? 'PASS' : 'FAIL';
  const observed = summary.observed > 0 ? `, ${summary.observed} observed` : '';
  lines.push(`Verdict: **${verdict}**, ${summary.pass} of ${checked} checked scenarios behaved as specified${observed}.`, '');
  lines.push('| Target | Run | Started | Duration | Scenarios | Passed | Failed | Observed |', '|---|---|---|---|---|---|---|---|');
  lines.push(`| ${cell(report.target)} | ${report.run} | ${report.startedAt} | ${(report.durationMs / 1000).toFixed(1)} s | ${summary.total} | ${summary.pass} | ${summary.fail} | ${summary.observed} |`, '');
  lines.push(`Settings: ${Object.entries(report.settings).map(([key, value]) => `${key} ${value}`).join(', ')}`, '');
  lines.push('## By group', '', '| Group | Passed | Failed | Observed |', '|---|---|---|---|');
  for (const group of GROUPS) {
    const totals = summary.byGroup[group];
    if (totals.pass + totals.fail + totals.observed > 0) {
      lines.push(`| ${group} | ${totals.pass} | ${totals.fail} | ${totals.observed} |`);
    }
  }
  lines.push('');
  const failures = report.results.filter((result) => result.verdict === 'fail');
  if (failures.length > 0) {
    lines.push('## Failures', '');
    for (const failure of failures) {
      lines.push(`### ${failure.id}: ${failure.title}`, '', `- Request: \`${cell(failure.request)}\``, `- Expected: ${failure.expected}`, `- Actual: ${failure.actual}`);
      for (const problem of failure.problems) {
        lines.push(`- ${problem}`);
      }
      lines.push('');
    }
  }
  lines.push('## Scenarios', '');
  for (const group of GROUPS) {
    const rows = report.results.filter((result) => result.group === group && result.verdict !== 'observed');
    if (rows.length === 0) {
      continue;
    }
    lines.push(`### ${group}`, '', '| Result | Scenario | Request | Expected | Actual | Time |', '|---|---|---|---|---|---|');
    for (const row of rows) {
      lines.push(`| ${row.verdict.toUpperCase()} | ${cell(row.id)}: ${cell(row.title)} | \`${cell(row.request)}\` | ${cell(row.expected)} | ${cell(row.actual)} | ${(row.durationMs / 1000).toFixed(1)} s |`);
    }
    lines.push('');
  }
  const observations = report.results.filter((result) => result.verdict === 'observed');
  if (observations.length > 0) {
    lines.push('## Observations', '', 'Behaviour the contract leaves open: recorded, not judged.', '', '| Scenario | Question | Request | Observed |', '|---|---|---|---|');
    for (const row of observations) {
      lines.push(`| ${cell(row.id)} | ${cell(row.title)} | \`${cell(row.request)}\` | ${cell(row.actual)} |`);
    }
    lines.push('');
  }
  return lines.join('\n');
}

function cell(text: string): string {
  return text.replace(/\|/g, '\\|').replace(/\s+/g, ' ').trim();
}
