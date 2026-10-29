-- Read-only CRM projection. Commercial sales and production quantities remain separate.
create table plm_mold_sales_snapshot (
 snapshot_id uuid primary key, source_hash char(64) not null unique,
 source_file varchar(200) not null, organization_id varchar(64) not null,
 captured_at timestamptz not null, row_count bigint not null,
 costs jsonb not null, source_summary jsonb not null,
 created_at timestamptz not null default now(), created_by varchar(128) not null
);
create table plm_mold_sales_line (
 source_id varchar(64) primary key, snapshot_id uuid not null references plm_mold_sales_snapshot,
 order_id varchar(64) not null, order_no varchar(100) not null,
 order_status varchar(200) not null, item_code varchar(160),
 style_code varchar(2), phone_code varchar(4), quantity numeric(20,4) not null,
 eligibility varchar(40) not null, source_data jsonb not null
);
create index ix_mold_sales_group on plm_mold_sales_line(style_code,phone_code,eligibility);
create index ix_mold_sales_order on plm_mold_sales_line(order_id);
