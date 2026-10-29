create table plm_mold_application (
 application_id bigserial primary key,
 source_system varchar(24) not null default 'plm',
 process_code varchar(128) not null,
 external_instance_id varchar(128), approval_no varchar(128), title varchar(512),
 model varchar(255), tipo varchar(255), generated_codes text,
 form_data jsonb not null default '{}'::jsonb,
 remote_status varchar(32), remote_result varchar(32),
 status varchar(32) not null default 'draft', approval_granted boolean not null default false,
 schema_version varchar(128), schema_snapshot jsonb, raw_snapshot jsonb,
 originator_user_id varchar(128), originator_name varchar(128), department_id bigint, department_name varchar(512),
 source_created_at timestamptz, source_finished_at timestamptz, last_synced_at timestamptz,
 applicant_id bigint references sys_user(user_id),
 product_line_id bigint references plm_product(product_id), phone_code_item_id bigint references plm_code_item(code_item_id),
 project_id bigint references plm_product(product_id), item_id bigint references plm_collaboration_item(item_id),
 mold_set_id bigint references plm_collaboration_mold_set(mold_set_id),
 previous_application_id bigint references plm_mold_application(application_id),
 submission_key varchar(64), submission_snapshot jsonb, last_error varchar(512),
 version_no bigint not null default 1,
 created_at timestamptz not null default now(), created_by varchar(128) not null,
 updated_at timestamptz not null default now(), updated_by varchar(128) not null,
 deleted_flag integer not null default 0,
 check(status in ('draft','submitting','submission_unknown','submitted','withdrawing','withdrawal_unknown','terminated','completed','converted'))
);
create unique index uk_mold_application_instance on plm_mold_application(process_code,external_instance_id) where external_instance_id is not null;
create unique index uk_mold_submission_key on plm_mold_application(submission_key) where submission_key is not null;
create index ix_mold_app_created on plm_mold_application(source_created_at desc,application_id desc);
create index ix_mold_app_model on plm_mold_application(lower(btrim(model)),tipo);

create table plm_mold_application_activity (
 activity_id bigserial primary key, application_id bigint not null references plm_mold_application(application_id),
 source_key varchar(64) not null, activity_type varchar(32) not null,
 occurred_at timestamptz, actor_id varchar(128), actor_name varchar(128), task_id varchar(128),
 payload jsonb not null, unique(application_id,source_key)
);
create table plm_mold_application_config (
 tipo varchar(255) primary key, product_line_id bigint not null references plm_product(product_id),
 product_specific_code varchar(16) not null, material_codes jsonb not null,
 active boolean not null default true, version_no bigint not null default 1,
 updated_at timestamptz not null default now(), updated_by varchar(128) not null
);
create table plm_mold_application_schema (
 process_code varchar(128) primary key, schema_version varchar(128), schema_snapshot jsonb not null,
 fetched_at timestamptz not null default now()
);
create table plm_mold_code_reservation (
 request_id varchar(128) primary key, request_hash varchar(64) not null,
 application_id bigint references plm_mold_application(application_id),
 phone_code_item_id bigint not null references plm_code_item(code_item_id),
 response jsonb not null, created_at timestamptz not null default now(), created_by varchar(128) not null
);
create table plm_mold_code_sequence (
 sequence_key varchar(32) primary key, last_value integer not null check(last_value between 0 and 9999)
);
insert into plm_mold_code_sequence(sequence_key,last_value)
select 'phone_model',coalesce(max(code_value::integer),0) from plm_code_item where code_type='phone_model' and code_value~'^[0-9]{4}$';

alter table plm_attachment
 add column if not exists content_sha256 varchar(64),
 add column if not exists download_status varchar(32),
 add column if not exists source_component_id varchar(128),
 add column if not exists dingtalk_storage_json jsonb;
create unique index uk_mold_application_media on plm_attachment(owner_object_id,source_component_id,source_file_id)
 where owner_object_type='MoldApplication' and deleted_flag=0;
