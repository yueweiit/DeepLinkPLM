create table if not exists plm_crm_mold_schedule_state (
  mold_set_id bigint primary key,
  record_version bigint not null check(record_version > 0),
  snapshot_hash varchar(64),
  deleted boolean not null default false,
  delete_reason varchar(32),
  updated_at timestamptz not null default now(),
  check ((deleted and delete_reason is not null and delete_reason in ('deleted','voided','out_of_scope')) or (not deleted and delete_reason is null))
);

create table if not exists plm_crm_mold_schedule_outbox (
  event_id uuid primary key,
  mold_set_id bigint not null,
  record_version bigint not null check(record_version > 0),
  event_type varchar(64) not null check(event_type in ('moldSchedule.upsert','moldSchedule.delete')),
  sync_mode varchar(16) not null check(sync_mode in ('initial','change','reconcile')),
  trigger_source varchar(64) not null,
  payload_body text not null,
  status varchar(16) not null default 'pending' check(status in ('pending','sending','retrying','succeeded','failed')),
  attempt_count integer not null default 0 check(attempt_count >= 0),
  next_attempt_at timestamptz not null default now(),
  lease_token uuid,
  locked_until timestamptz,
  last_http_status integer,
  last_error varchar(2000),
  created_at timestamptz not null default now(),
  sent_at timestamptz,
  unique(mold_set_id,record_version)
);

create index if not exists ix_plm_crm_mold_schedule_outbox_due
  on plm_crm_mold_schedule_outbox(status,next_attempt_at,created_at)
  where status in ('pending','retrying','sending');
create index if not exists ix_plm_crm_mold_schedule_outbox_record
  on plm_crm_mold_schedule_outbox(mold_set_id,record_version desc);
