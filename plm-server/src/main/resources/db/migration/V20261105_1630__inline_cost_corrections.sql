-- Direct freight totals and append-only actual-cost correction references use the existing ledgers.
alter table plm_development_budget add column freight_amount numeric(18,2) check(freight_amount is null or freight_amount>=0);
alter table plm_project_cost_item
 add column actual_correction_of_id bigint references plm_project_cost_item(project_cost_item_id),
 add column actual_replaced_by_id bigint references plm_project_cost_item(project_cost_item_id);
create unique index uk_expense_pending_actual_correction on plm_project_cost_item(actual_correction_of_id)
 where deleted_flag=0 and status<>'void' and actual_status='draft' and actual_correction_of_id is not null;
comment on column plm_development_budget.freight_amount is '明确填写的模具运费总额，包含燃油等附加费；空值继续按原重量费率计算';
comment on column plm_project_cost_item.actual_replaced_by_id is '实际更正采用的新记录；原金额、申报、审批与发生依据保留';
