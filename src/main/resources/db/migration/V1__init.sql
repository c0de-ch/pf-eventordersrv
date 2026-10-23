create table orders (
  id          bigint generated always as identity primary key,
  customer_id varchar(64)   not null,
  amount      numeric(19,2) not null check (amount > 0),
  currency    varchar(3)    not null default 'CHF' check (currency ~ '^[A-Z]{3}$'),
  status      varchar(16)   not null check (status in ('ACCEPTED', 'PROCESSING', 'PROCESSED', 'FAILED')),
  version     bigint        not null default 0,
  created_at  timestamptz   not null,
  updated_at  timestamptz   not null
);
create index orders_customer_id_idx on orders (customer_id);
create index orders_status_created_at_idx on orders (status, created_at);

create table order_status_history (
  id          bigint generated always as identity primary key,
  order_id    bigint      not null references orders (id),
  from_status varchar(16),
  to_status   varchar(16) not null,
  reason      varchar(255),
  created_at  timestamptz not null,
  updated_at  timestamptz not null
);
create index order_status_history_order_id_idx on order_status_history (order_id, id);

create table outbox_event (
  id             bigint generated always as identity primary key,
  aggregate_type varchar(32)   not null,
  aggregate_id   bigint        not null,
  event_type     varchar(64)   not null,
  payload        jsonb         not null,
  trace_context  varchar(128),
  attempts       int           not null default 0,
  last_error     varchar(1000),
  published_at   timestamptz,
  created_at     timestamptz   not null,
  updated_at     timestamptz   not null
);
create index outbox_event_pending_idx on outbox_event (id) where published_at is null;
