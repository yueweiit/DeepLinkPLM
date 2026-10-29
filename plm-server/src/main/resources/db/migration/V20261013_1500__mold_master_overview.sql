-- Reuse archive and physical-set identities; do not infer sets from tooling quantities.
alter table plm_collaboration_item
  add column if not exists mold_name varchar(255),
  add column if not exists engraved_code varchar(64),
  add column if not exists engraved_code_confirmed boolean not null default false,
  add column if not exists brand_id bigint;
alter table plm_collaboration_mold_set
  add column if not exists quote_amount numeric(16,2) check (quote_amount>=0),
  add column if not exists quote_currency varchar(12),
  add column if not exists quote_basis varchar(2000),
  add column if not exists quote_confirmed boolean not null default false;
alter table plm_collaboration_mold_part
  add column if not exists physical_status varchar(16) check (physical_status in ('unknown','making','confirmed'));
create table if not exists plm_collaboration_phone_member (
  item_id bigint not null references plm_collaboration_item(item_id),
  phone_model_id bigint not null,
  mold_set_ids bigint[] not null,
  pc_confirmed boolean not null,
  tpu_confirmed boolean not null,
  evidence varchar(2000) not null,
  created_by varchar(128) not null,
  updated_by varchar(128) not null,
  primary key(item_id,phone_model_id),
  check(cardinality(mold_set_ids)>0),
  check(pc_confirmed and tpu_confirmed)
);
create index if not exists ix_collaboration_phone_member_model on plm_collaboration_phone_member(phone_model_id,item_id);
-- A legacy phone-code anchor cannot be the identity of a multi-member mold archive.
drop index if exists uq_collaboration_item_master_identity;
create unique index uq_collaboration_item_master_identity
  on plm_collaboration_item(product_line_id,phone_model_code_item_id)
  where deleted_flag=0 and product_line_id is not null and phone_model_code_item_id is not null and mold_name is null;
drop index if exists uq_collaboration_item_identity;
create unique index uq_collaboration_item_identity
  on plm_collaboration_item(lower(btrim(style_name)),lower(btrim(compatible_models)))
  where deleted_flag=0 and mold_name is null;
