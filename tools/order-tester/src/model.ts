import type { RequestSpec } from './http.ts';

export const GROUPS = ['create', 'processing', 'validation', 'read', 'list', 'operations', 'probe'] as const;
export type Group = (typeof GROUPS)[number];

export const STATUSES = ['ACCEPTED', 'PROCESSING', 'PROCESSED', 'FAILED'] as const;
export type OrderStatus = (typeof STATUSES)[number];
export type Outcome = 'PROCESSED' | 'FAILED';
export const TERMINAL_STATUSES: ReadonlySet<string> = new Set<Outcome>(['PROCESSED', 'FAILED']);

// A created order is followed until it is final; amount is the text the API prints (two decimals)
export interface OrderExpectation {
  kind: 'order';
  customerId: string;
  amount: string;
  currency: string;
  outcome: Outcome;
  reasonContains?: string;
  minSeconds?: number;
}

export interface ProblemExpectation {
  kind: 'problem';
  status: number;
  fields?: string[];
  title?: string;
  detail?: string;
}

export interface PageExpectation {
  kind: 'page';
  size?: number;
  number?: number;
  totalElements?: number;
  totalPages?: number;
  contentLength?: number;
  everyStatus?: OrderStatus;
  descendingIds?: boolean;
}

export interface TextExpectation {
  kind: 'text';
  status: number;
  contains: string[];
}

export interface HealthExpectation {
  kind: 'health';
}

// Behaviour the contract leaves open: recorded in the report, never a failure
export interface ProbeExpectation {
  kind: 'probe';
  question: string;
}

export type Expectation =
  | OrderExpectation
  | ProblemExpectation
  | PageExpectation
  | TextExpectation
  | HealthExpectation
  | ProbeExpectation;

export interface Scenario {
  id: string;
  group: Group;
  title: string;
  request: RequestSpec;
  expect: Expectation;
}

export type Verdict = 'pass' | 'fail' | 'observed';

export interface Result {
  id: string;
  group: Group;
  title: string;
  request: string;
  expected: string;
  actual: string;
  verdict: Verdict;
  problems: string[];
  durationMs: number;
}

export interface CreatedOrder {
  scenarioId: string;
  id: number;
  customerId: string;
  status: string;
}

export function describeExpectation(expectation: Expectation): string {
  switch (expectation.kind) {
    case 'order': {
      const reason = expectation.reasonContains === undefined ? '' : ` with reason "${expectation.reasonContains}"`;
      const timing = expectation.minSeconds === undefined ? '' : ` after at least ${expectation.minSeconds} s`;
      return `201, then ${expectation.outcome}${reason}${timing}; ${expectation.amount} ${expectation.currency}, consistent history`;
    }
    case 'problem': {
      const fields = expectation.fields === undefined ? '' : ` on ${expectation.fields.join(', ')}`;
      const title = expectation.title === undefined ? '' : `, title "${expectation.title}"`;
      return `${expectation.status} problem detail${fields}${title}`;
    }
    case 'page': {
      const parts: string[] = [];
      if (expectation.totalElements !== undefined) parts.push(`${expectation.totalElements} elements`);
      if (expectation.size !== undefined) parts.push(`size ${expectation.size}`);
      if (expectation.number !== undefined) parts.push(`page ${expectation.number}`);
      if (expectation.totalPages !== undefined) parts.push(`${expectation.totalPages} pages`);
      if (expectation.contentLength !== undefined) parts.push(`${expectation.contentLength} on this page`);
      if (expectation.everyStatus !== undefined) parts.push(`all ${expectation.everyStatus}`);
      if (expectation.descendingIds) parts.push('newest first');
      return `200 page${parts.length === 0 ? '' : ': ' + parts.join(', ')}`;
    }
    case 'text':
      return expectation.contains.length === 0
        ? `${expectation.status}`
        : `${expectation.status} containing ${expectation.contains.map((c) => `"${c}"`).join(', ')}`;
    case 'health':
      return '200, status UP';
    case 'probe':
      return `observed: ${expectation.question}`;
  }
}
