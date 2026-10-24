-- Evidence of every dead-lettered message; order_id has no foreign key on purpose: a dead letter may name an order that does not exist
create table dead_letter (
  id                  bigint generated always as identity primary key,
  order_id            bigint,
  event_id            bigint,
  event_type          varchar(64),
  message_id          varchar(255) not null unique,
  original_message_id varchar(255),
  origin_queue        varchar(255),
  correlation_id      varchar(255),
  trace_id            varchar(32),
  sent_at             timestamptz,
  body                text,
  properties          jsonb       not null default '{}',
  created_at          timestamptz not null,
  updated_at          timestamptz not null
);
create index dead_letter_order_id_idx on dead_letter (order_id);
