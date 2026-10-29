-- Production labels are independent of registry enablement and project lifecycle.
alter table plm_code_item add column production_status varchar(16) not null default 'unknown'
  check (production_status in ('unknown','active','discontinued'));
alter table plm_product add column production_status varchar(16) not null default 'unknown'
  check (production_status in ('unknown','active','discontinued'));
create index ix_phone_code_production on plm_code_item(production_status,code_value desc)
  where code_type='phone_model' and deleted_flag=0;
create index ix_product_production on plm_product(production_status) where deleted_flag=0;

-- Explicit code-group classification covers legacy groups that have no standard members.
-- Confirmed member brands are derived, never copied or guessed from free-text names.
create table plm_phone_code_brand (
  code_item_id bigint not null references plm_code_item(code_item_id),
  brand_id bigint not null references plm_phone_brand(id),
  created_by varchar(128) not null,
  created_at timestamptz not null default now(),
  primary key(code_item_id,brand_id)
);
create index ix_phone_code_brand_brand on plm_phone_code_brand(brand_id,code_item_id);
create view plm_phone_code_brand_effective as
  select code_item_id,brand_id from plm_phone_code_brand
  union
  select code_item_id,brand_id from plm_phone_model_member where code_item_id is not null;
