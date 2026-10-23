import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { describeError, fieldErrors, OrdersApi } from './orders-api';

describe('OrdersApi', () => {
  let api: OrdersApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    api = TestBed.inject(OrdersApi);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('lists orders with filters and paging', () => {
    api.list({ customerId: '12345', status: 'ACCEPTED' }, 2, 10).subscribe();

    const request = http.expectOne((r) => r.url === '/api/orders');
    expect(request.request.params.get('customerId')).toBe('12345');
    expect(request.request.params.get('status')).toBe('ACCEPTED');
    expect(request.request.params.get('page')).toBe('2');
    expect(request.request.params.get('size')).toBe('10');
    request.flush({ content: [], page: { size: 10, number: 2, totalElements: 0, totalPages: 0 } });
  });

  it('creates an order with a JSON body', () => {
    api.create({ customerId: '12345', amount: 99.9 }).subscribe();

    const request = http.expectOne('/api/orders');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({ customerId: '12345', amount: 99.9 });
    request.flush({ id: 1 });
  });

  it('maps problem details to messages and field errors', () => {
    const error = new HttpErrorResponse({
      status: 400,
      error: { status: 400, title: 'Bad Request', detail: 'Invalid request content.', errors: [{ field: 'amount', message: 'must be greater than 0' }] },
    });

    expect(describeError(error)).toBe('Invalid request content.');
    expect(fieldErrors(error)).toEqual({ amount: 'must be greater than 0' });
    expect(describeError(new HttpErrorResponse({ status: 0 }))).toBe('Service not reachable');
  });
});
