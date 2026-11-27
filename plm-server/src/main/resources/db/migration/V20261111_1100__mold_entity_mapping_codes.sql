-- Additive entity identities and per-phone mold-code aliases. These rows do not create stock or mold sets.
create table plm_mold_entity_identity (
  entity_id bigserial primary key,
  product_line_id bigint not null references plm_product(product_id),
  item_id bigint not null,
  mold_set_id bigint not null,
  material_code varchar(2) not null check(material_code in ('10','20')),
  hole_type varchar(16) not null check(hole_type in ('large','precision','mixed')),
  structure_description varchar(1000) not null default '',
  primary_product_mold_code_id bigint not null references plm_product_mold_code(product_mold_code_id),
  primary_code varchar(32) not null,
  evidence varchar(2000) not null,
  status varchar(16) not null default 'active' check(status in ('active','inactive','needs_review')),
  created_by varchar(128) not null,
  created_at timestamptz not null default now(),
  unique(product_line_id,mold_set_id,material_code,hole_type,structure_description),
  foreign key(item_id,mold_set_id) references plm_collaboration_mold_set(item_id,mold_set_id)
);

create table plm_mold_entity_code_mapping (
  mapping_code_id bigserial primary key,
  entity_id bigint not null references plm_mold_entity_identity(entity_id),
  product_line_id bigint not null references plm_product(product_id),
  phone_model_id bigint not null references plm_phone_model_member(id),
  material_code varchar(2) not null check(material_code in ('10','20')),
  hole_type varchar(16) not null check(hole_type in ('large','precision','mixed')),
  structure_description varchar(1000) not null default '',
  mapping_code varchar(32) not null unique,
  source_application_id bigint references plm_mold_application(application_id),
  source_manufacturing_relation_id bigint references plm_manufacturing_relation(relation_id),
  status varchar(16) not null default 'active' check(status in ('active','inactive','needs_review')),
  evidence varchar(2000) not null,
  created_by varchar(128) not null,
  created_at timestamptz not null default now(),
  unique(entity_id,phone_model_id,material_code,hole_type,structure_description),
  unique(source_application_id,phone_model_id,material_code)
);
create index ix_mold_entity_code_lookup on plm_mold_entity_code_mapping(mapping_code,status);
create index ix_mold_entity_code_phone on plm_mold_entity_code_mapping(product_line_id,phone_model_id,status);
