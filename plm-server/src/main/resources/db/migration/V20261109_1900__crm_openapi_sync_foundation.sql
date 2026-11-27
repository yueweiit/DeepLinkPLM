-- Isolated inbound CRM API. Capture and external access are both opt-in.
create table plm_crm_openapi_control (
  singleton boolean primary key default true check(singleton),
  capture_enabled boolean not null default false,
  ready boolean not null default false,
  stream_epoch uuid not null default gen_random_uuid(),
  published_head bigint not null default 0,
  retained_after bigint not null default 0,
  last_business_date date,
  check(published_head>=retained_after and retained_after>=0)
);
insert into plm_crm_openapi_control(singleton) values(true);
create table plm_crm_openapi_resource (
  entity_type varchar(16) not null check(entity_type in ('MOLD_SET','PHONE_MODEL')),
  entity_id bigint not null,
  revision bigint not null check(revision>0),
  lifecycle varchar(16) not null check(lifecycle in ('ACTIVE','INACTIVE','DELETED')),
  projection_updated_at timestamptz not null default clock_timestamp(),
  business_date date,
  primary key(entity_type,entity_id)
);
-- No cascading business FK: deleted identities must survive physical deletion.
create table plm_crm_openapi_capture (
  capture_id bigserial primary key,
  stream_epoch uuid not null,
  entity_type varchar(16) not null,
  entity_id bigint not null,
  revision bigint not null,
  action varchar(16) not null check(action in ('UPSERT','DELETE','DISABLE','ENABLE')),
  lifecycle varchar(16) not null,
  captured_at timestamptz not null default clock_timestamp(),
  published boolean not null default false
);
create index ix_crm_openapi_capture_pending on plm_crm_openapi_capture(stream_epoch,capture_id) where not published;
create table plm_crm_openapi_change (
  stream_epoch uuid not null,
  change_id bigint not null,
  capture_id bigint not null unique,
  entity_type varchar(16) not null,
  entity_id bigint not null,
  revision bigint not null,
  action varchar(16) not null,
  lifecycle varchar(16) not null,
  changed_at timestamptz not null,
  published_at timestamptz not null default clock_timestamp(),
  primary key(stream_epoch,change_id)
);
create index ix_crm_openapi_change_expiry on plm_crm_openapi_change(published_at);
create table plm_crm_openapi_idempotency (
  subject_id varchar(128) not null,
  request_key varchar(128) not null,
  request_hash varchar(64) not null,
  response_json jsonb not null,
  etag text not null,
  created_at timestamptz not null default clock_timestamp(),
  expires_at timestamptz not null,
  primary key(subject_id,request_key)
);
create index ix_crm_openapi_idempotency_expiry on plm_crm_openapi_idempotency(expires_at);
