-- Audited historical classifications are deliberately separate from physical molds,
-- compatibility, original orders, production totals and cost denominators.
create table plm_mold_sales_history_batch (
 batch_id uuid primary key,
 singleton boolean not null default true unique check(singleton),
 policy_hash char(64) not null unique check(policy_hash ~ '^[a-f0-9]{64}$'),
 archive_hash char(64) not null references plm_mold_sales_archive_batch(source_hash),
 report_hash char(64) not null check(report_hash ~ '^[a-f0-9]{64}$'),
 recommendation_hash char(64) not null check(recommendation_hash ~ '^[a-f0-9]{64}$'),
 source_file text not null,
 approval_text text not null,
 reason text not null,
 summary jsonb not null check(jsonb_typeof(summary)='object'),
 created_by varchar(128) not null,
 created_at timestamptz not null default current_timestamp
);
create table plm_mold_sales_history_group (
 batch_id uuid not null references plm_mold_sales_history_batch(batch_id),
 style_code varchar(2) not null check(style_code ~ '^[A-Z]{2}$'),
 phone_code varchar(4) not null check(phone_code ~ '^[0-9]{4}$'),
 classification varchar(24) not null check(classification in ('coded_history','purchased','pending')),
 quantity numeric not null check(quantity>0 and quantity=trunc(quantity)),
 line_count bigint not null check(line_count>0),
 evidence jsonb not null check(jsonb_typeof(evidence)='object'),
 primary key(batch_id,style_code,phone_code)
);
create index ix_mold_sales_history_group_page on plm_mold_sales_history_group(classification,quantity desc,style_code,phone_code);
create table plm_mold_sales_history_line (
 source_id char(64) primary key references plm_mold_sales_archive_line(source_id),
 batch_id uuid not null,
 style_code varchar(2) not null,
 phone_code varchar(4) not null,
 display_phone_code varchar(4) check(display_phone_code ~ '^[0-9]{4}$'),
 display_name text,
 mapping_reason text,
 foreign key(batch_id,style_code,phone_code) references plm_mold_sales_history_group(batch_id,style_code,phone_code),
 check((display_name is null and mapping_reason is null and display_phone_code is null) or
       (nullif(btrim(display_name),'') is not null and nullif(btrim(mapping_reason),'') is not null))
);
create index ix_mold_sales_history_line_group on plm_mold_sales_history_line(batch_id,style_code,phone_code,source_id);
create index ix_mold_sales_history_line_reference on plm_mold_sales_history_line(display_phone_code) where display_phone_code is not null;
comment on table plm_mold_sales_history_group is '用户确认的历史销量分类和来源编码统计，不代表实物模具、套数、验收、产量或成本事实';
comment on table plm_mold_sales_history_line is '精确原单行绑定及仅显示/搜索的手机资料对照；原订单与原编码不改，不进入模具/共模/成本归属';
