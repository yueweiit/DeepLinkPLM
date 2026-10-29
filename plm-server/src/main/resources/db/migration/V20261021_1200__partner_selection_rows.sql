-- Headers keep their existing identities. Candidate rows are no longer an unbounded JSON array.
create table plm_partner_selection_row (
 row_id uuid primary key,
 record_id bigint not null references plm_partner_record(record_id),
 row_version bigint not null default 1,
 sort_order bigint not null,
 data jsonb not null,
 confirmed boolean not null default false,
 created_at timestamptz not null default now(),
 updated_at timestamptz not null default now(),
 updated_by varchar(100) not null
);
create index idx_partner_selection_row_page on plm_partner_selection_row(record_id,sort_order,row_id);
create index idx_partner_selection_row_supplier on plm_partner_selection_row(record_id,(data->>'supplierCode'));
create index idx_partner_selection_row_product on plm_partner_selection_row((data->>'productId'),(data->>'specId')) where confirmed;
create table plm_partner_selection_row_revision (
 revision_id bigserial primary key,
 record_id bigint not null references plm_partner_record(record_id),
 row_id uuid not null references plm_partner_selection_row(row_id),
 row_version bigint not null,
 request_id varchar(80) not null,
 data jsonb not null,
 created_at timestamptz not null default now(),
 created_by varchar(100) not null,
 unique(row_id,row_version)
);
create table plm_partner_quote_line (
 line_id uuid primary key,
 record_id bigint not null references plm_partner_record(record_id),
 row_version bigint not null default 1,
 sort_order bigint not null,
 data jsonb not null,
 created_at timestamptz not null default now(),
 updated_at timestamptz not null default now(),
 updated_by varchar(100) not null
);
create index idx_partner_quote_line_page on plm_partner_quote_line(record_id,sort_order,line_id);
create table plm_partner_quote_line_revision (
 revision_id bigserial primary key,
 line_id uuid not null references plm_partner_quote_line(line_id),
 record_id bigint not null references plm_partner_record(record_id),
 row_version bigint not null,
 request_id varchar(80) not null,
 data jsonb not null,
 created_at timestamptz not null default now(),
 created_by varchar(100) not null,
 unique(line_id,row_version)
);

-- Keep original rows and their frozen evidence. A deterministic ID is assigned only if absent.
insert into plm_partner_selection_row(row_id,record_id,sort_order,data,confirmed,updated_by)
select case when x.value->>'rowId' ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
 then (x.value->>'rowId')::uuid else md5(r.record_id::text||':legacy-selection:'||x.ordinality)::uuid end,
 r.record_id,x.ordinality,
 x.value || jsonb_build_object('legacyRow',true,'legacyRowId',x.value->>'rowId'),
 coalesce(x.value->>'confirmedAt','')<>'',coalesce(r.updated_by,'migration')
from plm_partner_record r cross join lateral jsonb_array_elements(coalesce(r.data->'rows','[]')) with ordinality x
where r.kind='selection';
update plm_partner_record r set data=(r.data-'rows'-'summary') || jsonb_build_object('schemaVersion',2,
 'rowCount',(select count(*) from plm_partner_selection_row s where s.record_id=r.record_id),
 'confirmedCount',(select count(*) from plm_partner_selection_row s where s.record_id=r.record_id and s.confirmed))
where r.kind='selection';
