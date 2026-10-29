-- Immutable audited ECOUNT order evidence; CRM refresh never replaces these rows.
create table plm_mold_sales_archive_batch (
 batch_id uuid primary key,
 source_hash char(64) not null unique check(source_hash ~ '^[a-f0-9]{64}$'),
 singleton boolean not null default true unique check(singleton),
 source_file varchar(200) not null,
 captured_at timestamptz not null,
 row_count bigint not null check(row_count>=0),
 detail_rows bigint not null check(detail_rows>=0),
 master_rows bigint not null check(master_rows>=0),
 pre2025_detail_rows bigint not null check(pre2025_detail_rows>=0),
 pre2025_master_rows bigint not null check(pre2025_master_rows>=0),
 manifest jsonb not null check(jsonb_typeof(manifest)='array'),
 source_summary jsonb not null check(jsonb_typeof(source_summary)='object'),
 created_by varchar(128) not null,
 created_at timestamptz not null default current_timestamp,
 check(row_count=detail_rows+master_rows)
);

create table plm_mold_sales_archive_line (
 source_id char(64) primary key check(source_id ~ '^[a-f0-9]{64}$'),
 batch_id uuid not null references plm_mold_sales_archive_batch(batch_id),
 record_kind varchar(6) not null check(record_kind in ('detail','master')),
 company varchar(8) not null check(company in ('YW','Lemos','UV')),
 business_date date not null,
 internal_order_id varchar(13) not null check(internal_order_id ~ '^[0-9]{13}$'),
 order_no text,
 item_code text,
 style_code varchar(2),
 phone_code varchar(4),
 quantity numeric,
 unit text,
 order_status text not null,
 eligibility varchar(40) not null,
 source_data jsonb not null check(jsonb_typeof(source_data)='object')
);
create index ix_mold_archive_group on plm_mold_sales_archive_line(style_code,phone_code,eligibility,business_date) where record_kind='detail' and business_date<date '2025-01-01';
create index ix_mold_archive_order on plm_mold_sales_archive_line(company,internal_order_id,record_kind);

-- Only the immutable pre-2025 detail and the existing post-2025 CRM projection
-- participate in the common read path. The post-2025 Excel evidence stays stored.
create view plm_mold_sales_fact as
 select l.source_id::text,l.snapshot_id,l.order_id::text,l.order_no::text,l.order_status::text,
        l.item_code::text,l.style_code::text,l.phone_code::text,l.quantity,l.eligibility::text,l.source_data,
        'crm'::text source_kind,l.source_data->>'company' company,
        (l.source_data->>'businessDate')::date business_date,l.source_data->>'unit' unit,
        s.source_file::text source_file,null::text source_sheet,null::bigint source_row
 from plm_mold_sales_line l join plm_mold_sales_snapshot s on s.snapshot_id=l.snapshot_id
 where l.source_data->>'businessDate'>='2025-01-01'
 union all
 select 'eccount:'||a.source_id,null::uuid,'eccount:'||a.company||':'||a.internal_order_id,
        a.order_no,a.order_status,a.item_code,a.style_code::text,a.phone_code::text,a.quantity,
        a.eligibility::text,a.source_data,'eccount'::text,a.company::text,a.business_date,a.unit,
        a.source_data->'source'->>'file',a.source_data->'source'->>'sheet',
        (a.source_data->'source'->>'row')::bigint
 from plm_mold_sales_archive_line a
 where a.record_kind='detail' and a.business_date<date '2025-01-01';

comment on table plm_mold_sales_archive_line is '历史销售订单原始证据，含主表和同期Excel对账资料；不代表出库、收款或实际生产，不自动建产品/库存';
comment on view plm_mold_sales_fact is '用户确认三公司订单相加；2025年前eccount、2025年起CRM，按已有模具及最终工序计订单量，非集团去重外销';
