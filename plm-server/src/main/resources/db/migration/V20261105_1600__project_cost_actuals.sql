-- Extend the original ledgers; existing declarations and historical versions remain unchanged.
alter table plm_development_budget add column cost_category varchar(24);
alter table plm_development_budget add constraint ck_budget_cost_category check(cost_category is null or cost_category in ('project_total','mold','transport','customs','design','sample','other'));
drop index uk_development_platform_budget;
create unique index uk_development_platform_budget on plm_development_budget(product_line_id,currency_code,coalesce(cost_category,'project_total')) where deleted_flag=0 and budget_kind='platform';
alter table plm_project_cost_item
 add column actual_amount numeric(18,2), add column actual_date date, add column actual_basis text,
 add column actual_status varchar(16) not null default 'draft',
 add column actual_confirmed_at timestamptz, add column actual_confirmed_by varchar(128),
 add column actual_reconciliation varchar(16) not null default 'pending' check(actual_reconciliation in ('pending','independent')),
 add column actual_manual_expense_id bigint references plm_project_cost_item(project_cost_item_id),
 add column actual_covered_amount numeric(18,2);
alter table plm_project_cost_item
 add constraint ck_actual_amount check(actual_amount is null or actual_amount>=0),
 add constraint ck_actual_status check(actual_status in ('draft','confirmed')),
 add constraint ck_actual_confirmation check(actual_status<>'confirmed' or (source_system='manual' and actual_amount is not null and actual_date is not null and length(trim(actual_basis))>0)),
 add constraint ck_actual_coverage check((actual_manual_expense_id is null and actual_covered_amount is null) or (source_system='erp' and actual_manual_expense_id is not null and actual_manual_expense_id<>project_cost_item_id and actual_covered_amount is not null));
create index ix_expense_actual_coverage on plm_project_cost_item(actual_manual_expense_id) where deleted_flag=0 and source_system='erp';
comment on column plm_project_cost_item.actual_amount is '独立的手工实际发生金额，不由申报或审批金额推断';
comment on column plm_project_cost_item.actual_covered_amount is '明确关联手工实际费用的ERP覆盖金额；同币种，支持分次入账及负数冲销';
