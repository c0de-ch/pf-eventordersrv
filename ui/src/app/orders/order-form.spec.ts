import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { OrderForm } from './order-form';

describe('OrderForm', () => {
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [OrderForm],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
  });

  it('does not submit an invalid form', async () => {
    const fixture = TestBed.createComponent(OrderForm);
    await fixture.whenStable();

    fixture.componentInstance.submit();
    await fixture.whenStable();

    http.expectNone('/api/orders');
    expect(fixture.nativeElement.textContent).toContain('required');
  });

  it('shows server-side field errors from a problem detail', async () => {
    const fixture = TestBed.createComponent(OrderForm);
    const form = fixture.componentInstance.form;
    form.setValue({ customerId: '12345', amount: '10.00', currency: '' });

    fixture.componentInstance.submit();
    http.expectOne('/api/orders').flush(
      { status: 400, title: 'Bad Request', detail: 'Invalid request content.', errors: [{ field: 'amount', message: 'must be greater than 0' }] },
      { status: 400, statusText: 'Bad Request' },
    );
    await fixture.whenStable();

    expect(fixture.nativeElement.textContent).toContain('must be greater than 0');
    expect(fixture.nativeElement.textContent).toContain('Invalid request content.');
  });

  it('navigates to the new order after a successful create', async () => {
    const fixture = TestBed.createComponent(OrderForm);
    const router = TestBed.inject(Router);
    const navigate = vi.spyOn(router, 'navigate').mockResolvedValue(true);
    fixture.componentInstance.form.setValue({ customerId: '12345', amount: '99.9', currency: 'CHF' });

    fixture.componentInstance.submit();
    const request = http.expectOne('/api/orders');
    expect(request.request.body).toEqual({ customerId: '12345', amount: 99.9, currency: 'CHF' });
    request.flush({ id: 42 });
    await fixture.whenStable();

    expect(navigate).toHaveBeenCalledWith(['/orders', 42]);
  });
});
