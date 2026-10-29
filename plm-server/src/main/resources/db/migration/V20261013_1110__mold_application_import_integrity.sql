-- Extend the existing constraint; keep every previously supported owner type.
alter table plm_attachment drop constraint if exists ck_plm_attachment_owner_type;
alter table plm_attachment add constraint ck_plm_attachment_owner_type check (
 owner_object_type in ('Customer','Product','Order','ProductionOrder','Process','Inventory','Workstation','MoldApplication')
);
create table plm_mold_import_job (
 manifest_sha256 varchar(64) primary key, source_directory text not null,
 started_at timestamptz not null default now(), completed_at timestamptz, report jsonb
);
create table plm_mold_master_snapshot (
 source_database varchar(128) not null, source_table varchar(128) not null,
 source_key varchar(256) not null, payload jsonb not null, manifest_sha256 varchar(64) not null,
 imported_at timestamptz not null default now(),
 primary key(source_database,source_table,source_key)
);
create table plm_mold_model_alias (
 source_database varchar(128) not null, source_id varchar(128) not null,
 phone_code varchar(4) not null, normalized_name varchar(255) not null,
 source_name varchar(255) not null, payload jsonb not null,
 primary key(source_database,source_id)
);
create index ix_mold_model_alias_name on plm_mold_model_alias(normalized_name,phone_code);
-- Historic codes and imports never cause reservations to be reclaimed.
create table plm_mold_dingtalk_directory (
 user_id varchar(128) primary key, display_name varchar(128), departments jsonb,
 fetched_at timestamptz not null default now()
);
