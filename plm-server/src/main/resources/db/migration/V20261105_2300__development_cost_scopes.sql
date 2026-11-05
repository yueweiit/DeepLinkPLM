-- Existing records remain unassigned. A cost scope is an estimating boundary, never a physical mold identity.
alter table plm_development_budget add column cost_scope_key varchar(64);
alter table plm_project_cost_item add column cost_scope_key varchar(64);
alter table plm_product_bom_cost_snapshot add column cost_scope_key varchar(64);
alter table plm_development_budget add constraint ck_budget_cost_scope check(cost_scope_key is null or cost_scope_key='shared' or cost_scope_key ~ '^[a-f0-9-]{36}$');
alter table plm_project_cost_item add constraint ck_expense_cost_scope check(cost_scope_key is null or cost_scope_key='shared' or cost_scope_key ~ '^[a-f0-9-]{36}$');
drop index uk_development_platform_budget;
create unique index uk_development_platform_budget on plm_development_budget(product_line_id,currency_code,coalesce(cost_category,'project_total'),coalesce(cost_scope_key,'unassigned')) where deleted_flag=0 and budget_kind='platform';
drop index uk_plm_product_bom_cost_current;
create unique index uk_plm_product_bom_cost_current on plm_product_bom_cost_snapshot(product_bom_id,product_bom_route_id,coalesce(cost_scope_key,'unassigned')) where status='current' and deleted_flag=0;
create index ix_project_cost_scope on plm_project_cost_item(product_id,cost_scope_key) where deleted_flag=0;
