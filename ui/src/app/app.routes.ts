import { Routes } from '@angular/router';

export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'orders' },
  { path: 'orders', loadComponent: () => import('./orders/order-list').then((m) => m.OrderList), title: 'Orders' },
  { path: 'orders/new', loadComponent: () => import('./orders/order-form').then((m) => m.OrderForm), title: 'New order' },
  { path: 'orders/:id', loadComponent: () => import('./orders/order-detail').then((m) => m.OrderDetail), title: 'Order' },
  { path: '**', redirectTo: 'orders' },
];
