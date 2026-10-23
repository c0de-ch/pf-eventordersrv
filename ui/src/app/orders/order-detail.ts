import { DatePipe, DecimalPipe } from '@angular/common';
import { Component, DestroyRef, effect, inject, input, numberAttribute, signal, untracked } from '@angular/core';
import { RouterLink } from '@angular/router';
import { isFinal, Order } from './order.model';
import { describeError, OrdersApi } from './orders-api';

const POLL_INTERVAL_MS = 1000;
const POLL_LIMIT = 120;

@Component({
  selector: 'app-order-detail',
  imports: [RouterLink, DatePipe, DecimalPipe],
  templateUrl: './order-detail.html',
})
export class OrderDetail {
  private readonly api = inject(OrdersApi);
  private readonly destroyRef = inject(DestroyRef);

  readonly id = input.required({ transform: numberAttribute });
  readonly order = signal<Order | null>(null);
  readonly error = signal<string | null>(null);
  readonly polling = signal(false);

  private timer: ReturnType<typeof setTimeout> | undefined;
  private polls = 0;

  constructor() {
    effect(() => {
      const id = this.id();
      untracked(() => this.start(id));
    });
    this.destroyRef.onDestroy(() => clearTimeout(this.timer));
  }

  private start(id: number): void {
    clearTimeout(this.timer);
    this.polls = 0;
    this.order.set(null);
    this.load(id);
  }

  // Polls once a second until the order reaches a final status, so the timeline fills in live
  load(id: number): void {
    this.api.get(id).subscribe({
      next: (order) => {
        this.order.set(order);
        this.error.set(null);
        const again = !isFinal(order.status) && this.polls++ < POLL_LIMIT;
        this.polling.set(again);
        if (again) {
          this.timer = setTimeout(() => this.load(id), POLL_INTERVAL_MS);
        }
      },
      error: (error: unknown) => {
        this.error.set(describeError(error));
        this.polling.set(false);
      },
    });
  }
}
