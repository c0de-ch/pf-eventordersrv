import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { OrderDetail } from './order-detail';

const processed = {
  id: 7,
  customerId: '12345',
  amount: 99.9,
  currency: 'CHF',
  status: 'PROCESSED',
  createdAt: '2026-10-23T10:00:00Z',
  updatedAt: '2026-10-23T10:00:02Z',
  history: [
    { from: null, to: 'ACCEPTED', reason: null, at: '2026-10-23T10:00:00Z' },
    { from: 'ACCEPTED', to: 'PROCESSING', reason: null, at: '2026-10-23T10:00:01Z' },
    { from: 'PROCESSING', to: 'PROCESSED', reason: null, at: '2026-10-23T10:00:02Z' },
  ],
};

describe('OrderDetail', () => {
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [OrderDetail],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('renders the lifecycle timeline and stops polling on a final status', async () => {
    const fixture = TestBed.createComponent(OrderDetail);
    fixture.componentRef.setInput('id', '7');
    await fixture.whenStable();

    http.expectOne('/api/orders/7').flush(processed);
    await fixture.whenStable();

    const items = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('.timeline li .badge')).map((e) => e.textContent?.trim());
    expect(items).toEqual(['ACCEPTED', 'PROCESSING', 'PROCESSED']);
    expect(fixture.componentInstance.polling()).toBe(false);
  });

  it('shows the problem detail for an unknown order', async () => {
    const fixture = TestBed.createComponent(OrderDetail);
    fixture.componentRef.setInput('id', '999');
    await fixture.whenStable();

    http.expectOne('/api/orders/999').flush({ status: 404, title: 'Order not found', detail: 'order 999 not found' }, { status: 404, statusText: 'Not Found' });
    await fixture.whenStable();

    expect(fixture.nativeElement.textContent).toContain('order 999 not found');
  });
});
