-- Additive identities only. Existing mold codes and physical stock are not rewritten.
-- Preserve the existing evidence view and extend its mold extraction in this new migration only.
do $$
begin
  if to_regclass('plm_product_identity_evidence') is not null then
    execute 'create or replace view plm_product_identity_evidence as ' ||
      replace(pg_get_viewdef('plm_product_identity_evidence'::regclass,true),
        'M([A-Z]{2})[0-9]{6}', 'M([A-Z]{2})[0-9]{2}[DJH]?[0-9]{4}');
  end if;
end $$;

alter table plm_manufacturing_relation drop constraint plm_manufacturing_relation_hole_type_check;
alter table plm_manufacturing_relation add constraint plm_manufacturing_relation_hole_type_check
  check(hole_type in ('large','precision','mixed','other'));

create table plm_mold_share_mapping (
  mapping_id bigserial primary key,
  product_line_id bigint not null references plm_product(product_id),
  source_phone_model_id bigint not null references plm_phone_model_member(id),
  reference_phone_model_id bigint not null references plm_phone_model_member(id),
  material_code varchar(2) not null check(material_code in ('10','20')),
  hole_type varchar(16) not null check(hole_type in ('large','precision','mixed')),
  structure_description varchar(1000) not null default '',
  product_mold_code_id bigint not null references plm_product_mold_code(product_mold_code_id),
  manufacturing_relation_id bigint not null references plm_manufacturing_relation(relation_id),
  manufacturing_relation_version bigint not null,
  evidence varchar(2000) not null check(nullif(btrim(evidence),'') is not null),
  status varchar(16) not null default 'active' check(status in ('active','inactive')),
  version bigint not null default 1,
  created_by varchar(128) not null, updated_by varchar(128) not null,
  created_at timestamptz not null default now(), updated_at timestamptz not null default now(),
  check(source_phone_model_id<>reference_phone_model_id)
);
create unique index uq_mold_share_active_scope on plm_mold_share_mapping
  (product_line_id,source_phone_model_id,material_code,hole_type,md5(structure_description)) where status='active';
create index ix_mold_share_reference on plm_mold_share_mapping(product_mold_code_id);

-- A real engineering adoption, never a fabricated approved application.
create table plm_mold_share_confirmation (
  confirmation_id bigserial primary key,
  request_id varchar(128) not null unique,
  request_hash varchar(64) not null,
  product_line_id bigint not null references plm_product(product_id),
  phone_model_id bigint not null references plm_phone_model_member(id),
  item_id bigint not null,
  mold_set_id bigint not null,
  snapshot jsonb not null,
  reason varchar(2000) not null,
  created_by varchar(128) not null,
  created_at timestamptz not null default now(),
  foreign key(item_id,mold_set_id) references plm_collaboration_mold_set(item_id,mold_set_id)
);
