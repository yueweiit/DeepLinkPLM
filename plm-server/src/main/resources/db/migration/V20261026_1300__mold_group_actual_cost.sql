-- Manual group-level cost annotations for sales analysis, not expense/payment facts.
-- Original budget snapshots, CRM order facts and financial ledgers remain unchanged.
create table plm_mold_group_actual_cost (
 cost_id bigserial primary key,
 group_key char(64) not null unique,
 group_token char(64) not null,
 item_ids bigint[] not null check(cardinality(item_ids)>0),
 manufacturing_cost numeric(18,2) not null check(manufacturing_cost>=0),
 freight_cost numeric(18,2) not null check(freight_cost>=0),
 customs_cost numeric(18,2) not null check(customs_cost>=0),
 other_cost numeric(18,2) not null check(other_cost>=0),
 total_cost numeric(20,2) generated always as (manufacturing_cost+freight_cost+customs_cost+other_cost) stored,
 currency char(3) not null default 'CNY' check(currency='CNY'),
 version integer not null default 1 check(version>0),
 created_by varchar(100) not null,
 created_at timestamptz not null default current_timestamp,
 updated_by varchar(100) not null,
 updated_at timestamptz not null default current_timestamp
);
create index ix_mold_actual_cost_members on plm_mold_group_actual_cost using gin(item_ids);

create table plm_mold_group_actual_cost_history (
 history_id bigserial primary key,
 cost_id bigint not null references plm_mold_group_actual_cost(cost_id),
 version integer not null check(version>0),
 amounts jsonb not null check(jsonb_typeof(amounts)='object'),
 reason varchar(2000) not null check(length(btrim(reason))>0),
 actor_name varchar(100) not null,
 changed_at timestamptz not null default current_timestamp,
 unique(cost_id,version)
);
comment on table plm_mold_group_actual_cost is '人工维护的共模组实际成本分析标注；不代表财务申报、审批、入账或付款，不按套数和兼容型号重复计算';
comment on table plm_mold_group_actual_cost_history is '共模组实际成本标注历史，原预算及每次人工修改均不覆盖';
