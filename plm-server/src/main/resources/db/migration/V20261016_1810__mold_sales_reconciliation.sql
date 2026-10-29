-- Reviewed source identities are separate from engineering compatibility and raw CRM facts.
-- Append a new batch to revise a decision; never rewrite an earlier review.
create table plm_mold_sales_reconciliation (
 batch_id uuid primary key,
 batch_hash char(64) not null unique,
 source_hash char(64) not null references plm_mold_sales_snapshot(source_hash),
 catalog_url text not null,
 catalog_captured_at timestamptz not null,
 catalog jsonb not null check(jsonb_typeof(catalog)='object'),
 decisions jsonb not null check(jsonb_typeof(decisions)='array'),
 created_at timestamptz not null default now(),
 created_by varchar(128) not null
);
