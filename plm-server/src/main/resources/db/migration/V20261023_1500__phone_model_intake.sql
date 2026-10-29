-- Engineering intake is not a second phone master and never allocates a phone business code.
create table plm_phone_model_intake (
  id bigserial primary key,
  identity_key varchar(64) not null unique,
  component_text varchar(256) not null,
  brand_id bigint references plm_phone_brand(id),
  classification varchar(16) not null check(classification in ('missing','review')),
  review_reason varchar(32) not null default '' check(review_reason in ('','name','variant','shared_group','incomplete','code')),
  reason text not null,
  state varchar(16) not null default 'pending' check(state in ('pending','resolved','deferred')),
  version bigint not null default 0,
  resolved_phone_ids bigint[] not null default '{}',
  created_by varchar(128) not null,
  updated_by varchar(128) not null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index ix_phone_intake_state on plm_phone_model_intake(state,classification,updated_at desc,id desc);
create table plm_phone_model_intake_source (
  id bigserial primary key,
  intake_id bigint not null references plm_phone_model_intake(id),
  supplier_code varchar(64) not null,
  source_kind varchar(16) not null check(source_kind in ('quote','selection')),
  record_id bigint not null references plm_partner_record(record_id),
  row_id uuid not null,
  supplier_index bigint not null check(supplier_index>0),
  component_key varchar(64) not null,
  source_digest varchar(64) not null,
  source_snapshot jsonb not null,
  created_by varchar(128) not null,
  created_at timestamptz not null default now(),
  unique(intake_id,source_kind,record_id,row_id,supplier_index,component_key,source_digest)
);
create index ix_phone_intake_source_scope on plm_phone_model_intake_source(record_id,supplier_code,intake_id);
create index ix_phone_intake_source_parent on plm_phone_model_intake_source(intake_id,id desc);
