-- A phone identity is not its shared business code. Never split free-text slash groups here.
create table plm_phone_brand (
  id bigserial primary key,
  name varchar(128) not null,
  status varchar(16) not null default 'enabled' check(status in ('enabled','disabled')),
  source_system varchar(32) not null default 'plm',
  source_record_id varchar(128),
  source_snapshot jsonb,
  created_by varchar(128) not null,
  updated_by varchar(128) not null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create unique index uq_phone_brand_name on plm_phone_brand(lower(btrim(name)));
create unique index uq_phone_brand_source on plm_phone_brand(source_system,source_record_id) where source_record_id is not null;

create table plm_phone_model_member (
  id bigserial primary key,
  brand_id bigint not null references plm_phone_brand(id),
  model_name varchar(256) not null,
  aliases text[] not null default '{}',
  code_item_id bigint references plm_code_item(code_item_id),
  status varchar(16) not null default 'enabled' check(status in ('enabled','disabled')),
  source_system varchar(32) not null default 'plm',
  source_record_id varchar(128),
  source_snapshot jsonb,
  created_by varchar(128) not null,
  updated_by varchar(128) not null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create unique index uq_phone_member_identity on plm_phone_model_member(brand_id,lower(btrim(model_name)));
create index ix_phone_member_code on plm_phone_model_member(code_item_id);
create unique index uq_phone_member_source on plm_phone_model_member(source_system,source_record_id) where source_record_id is not null;

alter table plm_collaboration_phone_member
  add constraint fk_collaboration_phone_member foreign key(phone_model_id) references plm_phone_model_member(id);
alter table plm_collaboration_item
  add constraint fk_collaboration_phone_brand foreign key(brand_id) references plm_phone_brand(id);
-- New projects can keep their selected phone identity even if a legacy business code is retired.
alter table plm_product add column phone_model_member_id bigint references plm_phone_model_member(id);
