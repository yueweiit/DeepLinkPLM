-- Independent of the schedule OpenAPI. No historical backfill or implicit permissions.
create table plm_crm_listing_request (
  request_id varchar(80) not null,
  actor_id bigint not null,
  request_hash varchar(64) not null,
  task_ids jsonb not null,
  created_at timestamptz not null default now(),
  primary key(actor_id,request_id)
);
create table plm_crm_listing_task (
  task_id bigserial primary key,
  listing_id bigint not null references plm_partner_record(record_id),
  product_id bigint not null references plm_partner_record(record_id),
  snapshot_version varchar(64) not null,
  snapshot jsonb not null,
  status varchar(24) not null default 'pending' check(status in ('pending','processing','partial','synced','failed')),
  crm_product_id varchar(255),
  attempts integer not null default 0,
  lease_token uuid,
  lease_until timestamptz,
  next_attempt_at timestamptz not null default now(),
  error_code varchar(80) not null default '',
  error_message varchar(255) not null default '',
  created_by bigint not null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create unique index uq_crm_listing_active on plm_crm_listing_task(listing_id) where status in ('pending','processing');
create index ix_crm_listing_claim on plm_crm_listing_task(next_attempt_at,task_id) where status='pending';
create index ix_crm_listing_latest on plm_crm_listing_task(listing_id,task_id desc);
create table plm_crm_listing_sku (
  task_id bigint not null references plm_crm_listing_task(task_id),
  internal_sku_id bigint not null references plm_product(product_id),
  status varchar(24) not null default 'pending' check(status in ('pending','synced','failed')),
  listing_status varchar(24) not null default 'not_listed' check(listing_status in ('not_listed','listing','listed','listing_failed','off_shelf')),
  crm_sku_id varchar(255),
  error_code varchar(80) not null default '',
  error_message varchar(255) not null default '',
  updated_at timestamptz not null default now(),
  primary key(task_id,internal_sku_id)
);
