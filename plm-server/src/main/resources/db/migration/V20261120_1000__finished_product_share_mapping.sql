create table plm_finished_product_mapping (
  mapping_id bigserial primary key,
  product_line_id bigint not null references plm_product(product_id),
  product_specific_code varchar(2) not null,
  operation_code varchar(4) not null,
  hole_type varchar(16) not null check (hole_type in ('large','precision','mixed')),
  reference_phone_model_id bigint not null references plm_phone_model_member(id),
  reference_phone_model_code varchar(4) not null,
  color_code varchar(2) not null,
  mapping_code varchar(32) not null unique,
  status varchar(16) not null default 'active' check (status in ('active','inactive')),
  evidence varchar(2000) not null,
  version bigint not null default 1,
  created_by varchar(128) not null,
  updated_by varchar(128) not null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (product_line_id, operation_code, hole_type, reference_phone_model_id, color_code)
);

create table plm_finished_product_mapping_sku (
  mapping_id bigint not null references plm_finished_product_mapping(mapping_id),
  product_id bigint not null references plm_product(product_id),
  phone_model_id bigint not null references plm_phone_model_member(id),
  phone_model_code varchar(4) not null,
  finished_product_code varchar(80) not null,
  created_at timestamptz not null default now(),
  primary key (mapping_id, product_id)
);

create index ix_finished_product_mapping_sku_product
  on plm_finished_product_mapping_sku(product_id);

create table plm_finished_product_mapping_mold_basis (
  mapping_id bigint not null references plm_finished_product_mapping(mapping_id),
  entity_id bigint not null references plm_mold_entity_identity(entity_id),
  mapping_code_id bigint references plm_mold_entity_code_mapping(mapping_code_id),
  phone_model_id bigint not null references plm_phone_model_member(id),
  material_code varchar(2) not null check (material_code in ('10','20')),
  mold_set_id bigint not null,
  mapping_code varchar(32) not null,
  primary_code varchar(32) not null,
  created_at timestamptz not null default now(),
  primary key (mapping_id, phone_model_id, material_code, mold_set_id),
  check (mapping_code_id is not null or mapping_code=primary_code)
);

create index ix_finished_product_mapping_status
  on plm_finished_product_mapping(status, product_line_id, mapping_id desc);
