-- Existing business objects remain the only editable product/BOM/process records.
-- Source identities are many-to-one: CRM item_code is explicitly not unique.
create table plm_master_data_source (
 source_key bigserial primary key,
 source_system varchar(16) not null check(source_system in ('crm','mes')),
 organization_id varchar(64) not null,
 source_kind varchar(64) not null, source_id varchar(128) not null,
 object_type varchar(32) not null, object_id bigint not null,
 phone_model_id bigint references plm_phone_model_member(id),
 source_code varchar(256), source_status varchar(64), source_hash char(64) not null,
 source_data jsonb not null, observed_at timestamptz not null,
 batch_hash char(64) not null, created_at timestamptz not null default now(),
 unique(source_system,organization_id,source_kind,source_id)
);
create index ix_master_source_object on plm_master_data_source(object_type,object_id);
create index ix_master_source_code on plm_master_data_source(source_system,source_kind,source_code);
create index ix_master_sku_phone on plm_master_data_source(phone_model_id) where source_kind='product_sku';

create table plm_master_data_import (
 import_id uuid primary key, actor_id bigint not null references sys_user(user_id),
 snapshot_name varchar(128) not null, source_hash char(64) not null,
 original_hash char(64) not null, preview jsonb not null, result jsonb,
 status varchar(16) not null default 'preview' check(status in ('preview','committed')),
 created_at timestamptz not null default now(), committed_at timestamptz,
 unique(source_hash,actor_id,original_hash)
);

-- Product compatibility is not physical mold compatibility or a new SKU table.
create table plm_product_phone_model (
 product_id bigint not null references plm_product(product_id),
 phone_model_id bigint not null references plm_phone_model_member(id),
 source_system varchar(16) not null default 'crm',
 created_at timestamptz not null default now(), created_by varchar(128) not null,
 primary key(product_id,phone_model_id)
);

alter table plm_process add column node_code varchar(160), add column local_node_code varchar(160),
 add column position_x numeric(18,4), add column position_y numeric(18,4);
alter table plm_process add constraint uk_process_parent_identity unique(process_id,parent_process_id);
create unique index uk_process_node_code on plm_process(node_code) where node_code is not null and deleted_flag=0;
create table plm_process_edge (
 edge_id bigserial primary key, route_id bigint not null references plm_process(process_id),
 from_process_id bigint not null, to_process_id bigint not null,
 edge_no integer, relation_type varchar(64) not null, label text, condition_json jsonb,
 unique(route_id,from_process_id,to_process_id), check(from_process_id<>to_process_id),
 foreign key(from_process_id,route_id) references plm_process(process_id,parent_process_id),
 foreign key(to_process_id,route_id) references plm_process(process_id,parent_process_id)
);

alter table plm_product_bom alter column quantity type numeric(24,8);
alter table plm_product_bom add column custom_bom_specification text;
alter table plm_product_bom_item alter column quantity type numeric(24,8),
 add column component_row_id varchar(160), add column child_code_normalized varchar(128),
 add column unit_raw varchar(128), add column unit_cn varchar(64), add column unit_es varchar(64);
alter table plm_product_bom_route add column article_codes text[],
 add column binding_priority integer not null default 100,
 add column binding_condition jsonb, add column source_binding_id varchar(128);
alter table plm_product_bom_item add constraint uk_bom_item_parent unique(product_bom_item_id,product_bom_id);
alter table plm_product_bom_route add constraint uk_bom_route_parent unique(product_bom_route_id,product_bom_id);
alter table plm_product_bom_route add constraint uk_bom_route_process unique(product_bom_route_id,process_id);
create table plm_bom_item_target (
 product_bom_item_id bigint not null,
 product_bom_id bigint not null, product_bom_route_id bigint not null,
 route_process_id bigint not null, process_id bigint not null,
 article_codes text[],
 primary key(product_bom_item_id,product_bom_route_id),
 foreign key(product_bom_item_id,product_bom_id) references plm_product_bom_item(product_bom_item_id,product_bom_id),
 foreign key(product_bom_route_id,product_bom_id) references plm_product_bom_route(product_bom_route_id,product_bom_id),
 foreign key(product_bom_route_id,route_process_id) references plm_product_bom_route(product_bom_route_id,process_id),
 foreign key(process_id,route_process_id) references plm_process(process_id,parent_process_id),
 check(article_codes is null or array_position(article_codes,null) is null)
);
create index ix_bom_item_target_scope on plm_bom_item_target using gin(article_codes);

-- Source-effective versions are immutable baselines, not a fabricated PLM approval.
-- Import never creates ERP delivery events or changes existing project/mold states.
