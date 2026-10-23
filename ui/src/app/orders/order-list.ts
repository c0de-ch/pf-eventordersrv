import { DatePipe, DecimalPipe } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ORDER_STATUSES, OrderStatus, OrderSummary, Page } from './order.model';
import { describeError, OrdersApi } from './orders-api';

@Component({
  selector: 'app-order-list',
  imports: [RouterLink, DatePipe, DecimalPipe],
  templateUrl: './order-list.html',
})
export class OrderList {
  private readonly api = inject(OrdersApi);

  readonly statuses = ORDER_STATUSES;
  readonly customerId = signal('');
  readonly status = signal<OrderStatus | ''>('');
  readonly page = signal(0);
  readonly result = signal<Page<OrderSummary> | null>(null);
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);

  constructor() {
    this.load();
  }

  search(): void {
    this.page.set(0);
    this.load();
  }

  goTo(page: number): void {
    this.page.set(page);
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.error.set(null);
    this.api.list({ customerId: this.customerId().trim(), status: this.status() }, this.page()).subscribe({
      next: (result) => {
        this.result.set(result);
        this.loading.set(false);
      },
      error: (error: unknown) => {
        this.error.set(describeError(error));
        this.loading.set(false);
      },
    });
  }
}
