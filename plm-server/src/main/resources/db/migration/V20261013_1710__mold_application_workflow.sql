-- Extend the existing application; source identities and execution intent are independent of OA snapshots.
alter table plm_mold_application
 add column phone_model_id bigint references plm_phone_model_member(id),
 add column source_snapshot_json jsonb,
 add column execution_intent varchar(16) not null default 'new' check(execution_intent in ('new','new_set','reuse')),
 add column execution_reason varchar(4000),
 add column new_application_requested boolean not null default false,
 add column draft_request_id varchar(128),
 add column target_item_id bigint references plm_collaboration_item(item_id),
 add column target_mold_set_id bigint references plm_collaboration_mold_set(mold_set_id),
 add column target_project_id bigint references plm_product(product_id),
 add column provisioning_status varchar(24) not null default 'not_requested' check(provisioning_status in ('not_requested','pending','ready','blocked')),
 add column provisioning_error varchar(512),
 add column execution_status varchar(16) not null default 'pending' check(execution_status in ('pending','active','paused','terminated')),
 add column authorization_json jsonb,
 add column execution_resolution_json jsonb,
 add column execution_exception_json jsonb;
create index ix_mold_application_phone on plm_mold_application(phone_model_id,product_line_id) where deleted_flag=0;
create unique index uk_mold_application_draft_request on plm_mold_application(applicant_id,draft_request_id) where draft_request_id is not null;
-- Only a previously explicit standard-phone selection can backfill phone identity.
update plm_mold_application a set phone_model_id=coalesce((r.response->>'phoneModelId')::bigint,(r.response->'compatibility'->>'phoneModelId')::bigint),
 execution_intent=case when r.response->'compatibility' is not null then 'reuse' else 'new' end
from (select distinct on(application_id) application_id,response from plm_mold_code_reservation order by application_id,created_at desc,request_id desc) r
where a.application_id=r.application_id and r.response->>'generatedCodes'=a.generated_codes;
-- Historical authorization is reconciled by V20261014_1730 using the same OA parser as live sync.
-- Until then existing linked execution is suspended, never authorized by a copied status label.
update plm_mold_application set approval_granted=false,
 provisioning_status=case when mold_set_id is not null then 'ready' else 'not_requested' end,
 execution_status=case when mold_set_id is null then 'pending' else 'paused' end;
-- Existing formal records remain read-only history: this migration never creates projects or physical sets.
update plm_pilot_account set dingtalk_user_id='16693147192083157833',updated_at=now(),updated_by='workflow-migration'
where user_id in (select user_id from sys_user where username='tiffany' and display_name='Tiffany' and deleted_flag=0)
 and dingtalk_user_id is null and not exists(select 1 from plm_pilot_account where dingtalk_user_id='16693147192083157833');
