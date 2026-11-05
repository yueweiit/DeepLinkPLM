-- A scoped engineering relation, not another product, mold or stock ledger.
alter table plm_project_requirement_form add column manufacturing_structure_description varchar(1000);
create table plm_manufacturing_relation (
  relation_id bigserial primary key,
  product_line_id bigint not null references plm_product(product_id),
  item_id bigint not null,
  mold_set_id bigint not null,
  phone_model_id bigint not null references plm_phone_model_member(id),
  hole_type varchar(16) not null check(hole_type in ('large','precision','other')),
  structure_description varchar(1000) not null default '',
  shared_material boolean not null default false,
  manufacturing_product_id bigint references plm_product(product_id),
  sku_ids jsonb not null default '[]'::jsonb check(jsonb_typeof(sku_ids)='array'),
  mold_evidence varchar(2000) not null check(nullif(btrim(mold_evidence),'') is not null),
  material_evidence varchar(2000),
  compatibility_digest varchar(64) not null,
  status varchar(16) not null default 'active' check(status in ('active','inactive')),
  version bigint not null default 1,
  created_by varchar(128) not null, updated_by varchar(128) not null,
  created_at timestamptz not null default now(), updated_at timestamptz not null default now(),
  foreign key(item_id,mold_set_id) references plm_collaboration_mold_set(item_id,mold_set_id),
  check(hole_type<>'other' or nullif(btrim(structure_description),'') is not null),
  check((shared_material and manufacturing_product_id is not null and jsonb_array_length(sku_ids)>0 and nullif(btrim(material_evidence),'') is not null)
    or (not shared_material and manufacturing_product_id is null and sku_ids='[]'::jsonb))
);
-- Resolution still compares full text; the digest only bounds the B-tree key for long descriptions.
create unique index uq_manufacturing_relation_active on plm_manufacturing_relation(item_id,mold_set_id,phone_model_id,hole_type,md5(structure_description)) where status='active';
create index ix_manufacturing_relation_phone on plm_manufacturing_relation(phone_model_id,product_line_id,relation_id);
create index ix_manufacturing_relation_product on plm_manufacturing_relation(manufacturing_product_id) where manufacturing_product_id is not null;
