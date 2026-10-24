export type Json = null | boolean | number | string | Json[] | { [key: string]: Json };

export type Method = 'GET' | 'POST' | 'PUT' | 'DELETE';

export interface RequestSpec {
  method: Method;
  path: string;
  headers?: Record<string, string>;
  body?: string | Uint8Array;
}

export interface HttpResponse {
  status: number;
  headers: Headers;
  text: string;
  json: Json | undefined;
}

export const JSON_HEADERS: Record<string, string> = { 'Content-Type': 'application/json' };

export class ApiClient {
  readonly baseUrl: string;
  readonly timeoutMs: number;

  constructor(baseUrl: string, timeoutMs = 10_000) {
    this.baseUrl = baseUrl.replace(/\/+$/, '');
    this.timeoutMs = timeoutMs;
  }

  async send(spec: RequestSpec): Promise<HttpResponse> {
    const response = await fetch(this.baseUrl + spec.path, {
      method: spec.method,
      headers: spec.headers ?? {},
      ...(spec.body === undefined ? {} : { body: spec.body }),
      redirect: 'manual',
      signal: AbortSignal.timeout(this.timeoutMs),
    });
    const text = await response.text();
    return { status: response.status, headers: response.headers, text, json: parseJson(text, response.headers.get('content-type')) };
  }
}

export function parseJson(text: string, contentType: string | null): Json | undefined {
  if (!contentType?.includes('json')) {
    return undefined;
  }
  try {
    return JSON.parse(text) as Json;
  } catch {
    return undefined;
  }
}

export function pathOf(spec: RequestSpec): string {
  const query = spec.path.indexOf('?');
  return query < 0 ? spec.path : spec.path.slice(0, query);
}

export function describeRequest(spec: RequestSpec): string {
  const type = spec.headers?.['Content-Type'];
  const typeNote = spec.body !== undefined && type !== 'application/json' ? ` (${type ?? 'no Content-Type'})` : '';
  const body = spec.body === undefined ? '' : typeof spec.body === 'string' ? ` ${spec.body}` : ` <${spec.body.byteLength} bytes>`;
  return `${spec.method} ${spec.path}${typeNote}${body}`;
}
