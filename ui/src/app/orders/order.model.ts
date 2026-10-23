export type OrderStatus = 'ACCEPTED' | 'PROCESSING' | 'PROCESSED' | 'FAILED';

export const ORDER_STATUSES: readonly OrderStatus[] = ['ACCEPTED', 'PROCESSING', 'PROCESSED', 'FAILED'];

export interface StatusChange {
  from: OrderStatus | null;
  to: OrderStatus;
  reason: string | null;
  at: string;
}

export interface OrderSummary {
  id: number;
  customerId: string;
  amount: number;
  currency: string;
  status: OrderStatus;
  createdAt: string;
  updatedAt: string;
}

export interface Order extends OrderSummary {
  history: StatusChange[];
}

export interface Page<T> {
  content: T[];
  page: { size: number; number: number; totalElements: number; totalPages: number };
}

export interface CreateOrder {
  customerId: string;
  amount: number;
  currency?: string;
}

export interface FieldViolation {
  field: string;
  message: string;
}

export interface ProblemDetail {
  type?: string;
  title?: string;
  status: number;
  detail?: string;
  instance?: string;
  errors?: FieldViolation[];
}

export function isFinal(status: OrderStatus): boolean {
  return status === 'PROCESSED' || status === 'FAILED';
}
