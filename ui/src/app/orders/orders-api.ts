import { HttpClient, HttpErrorResponse, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { CreateOrder, Order, OrderStatus, OrderSummary, Page, ProblemDetail } from './order.model';

export interface OrderFilter {
  customerId?: string;
  status?: OrderStatus | '';
}

// Same origin: the dev proxy and nginx forward /api to the service and strip the prefix
@Injectable({ providedIn: 'root' })
export class OrdersApi {
  private readonly http = inject(HttpClient);
  private readonly base = '/api/orders';

  list(filter: OrderFilter, page = 0, size = 20): Observable<Page<OrderSummary>> {
    let params = new HttpParams().set('page', page).set('size', size);
    if (filter.customerId) {
      params = params.set('customerId', filter.customerId);
    }
    if (filter.status) {
      params = params.set('status', filter.status);
    }
    return this.http.get<Page<OrderSummary>>(this.base, { params });
  }

  get(id: number): Observable<Order> {
    return this.http.get<Order>(`${this.base}/${id}`);
  }

  create(request: CreateOrder): Observable<Order> {
    return this.http.post<Order>(this.base, request);
  }
}

export function describeError(error: unknown): string {
  if (error instanceof HttpErrorResponse) {
    const problem = error.error as ProblemDetail | null;
    if (problem && typeof problem === 'object' && (problem.detail || problem.title)) {
      return problem.detail ?? problem.title ?? `HTTP ${error.status}`;
    }
    return error.status === 0 ? 'Service not reachable' : `HTTP ${error.status}`;
  }
  return 'Unexpected error';
}

export function fieldErrors(error: unknown): Record<string, string> {
  if (error instanceof HttpErrorResponse && error.status === 400) {
    const problem = error.error as ProblemDetail | null;
    return Object.fromEntries((problem?.errors ?? []).map((e) => [e.field, e.message]));
  }
  return {};
}
