import { Component, inject, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { describeError, fieldErrors, OrdersApi } from './orders-api';

@Component({
  selector: 'app-order-form',
  imports: [ReactiveFormsModule, RouterLink],
  templateUrl: './order-form.html',
})
export class OrderForm {
  private readonly api = inject(OrdersApi);
  private readonly router = inject(Router);
  private readonly fb = inject(NonNullableFormBuilder);

  readonly form = this.fb.group({
    customerId: ['', [Validators.required, Validators.maxLength(64)]],
    amount: ['', [Validators.required, Validators.pattern(/^\d+(\.\d{1,2})?$/), Validators.min(0.01)]],
    currency: ['', [Validators.pattern(/^[A-Z]{3}$/)]],
  });
  readonly submitting = signal(false);
  readonly error = signal<string | null>(null);
  readonly serverErrors = signal<Record<string, string>>({});

  // Server-side violations win over client-side hints: they are the contract
  problem(field: 'customerId' | 'amount' | 'currency'): string | null {
    const server = this.serverErrors()[field];
    if (server) {
      return server;
    }
    const control = this.form.controls[field];
    if (!control.touched || control.valid) {
      return null;
    }
    if (control.hasError('required')) {
      return 'required';
    }
    if (control.hasError('maxlength')) {
      return 'at most 64 characters';
    }
    if (field === 'amount') {
      return 'positive number with at most two decimals';
    }
    return 'three upper-case letters, e.g. CHF';
  }

  submit(): void {
    this.form.markAllAsTouched();
    if (this.form.invalid || this.submitting()) {
      return;
    }
    const value = this.form.getRawValue();
    this.submitting.set(true);
    this.error.set(null);
    this.serverErrors.set({});
    this.api
      .create({ customerId: value.customerId.trim(), amount: Number(value.amount), currency: value.currency || undefined })
      .subscribe({
        next: (order) => void this.router.navigate(['/orders', order.id]),
        error: (error: unknown) => {
          this.serverErrors.set(fieldErrors(error));
          this.error.set(describeError(error));
          this.submitting.set(false);
        },
      });
  }
}
