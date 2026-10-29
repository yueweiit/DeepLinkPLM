-- Product remains the single identity; catalog imports are not development projects.
alter table plm_product
 add column project_kind varchar(24),
 add column project_registered_at timestamp;
alter table plm_product add constraint ck_product_project_registration check (
 (project_kind is null and project_registered_at is null) or
 (project_kind is not null and project_registered_at is not null and
   ((project_kind='product_research' and product_type='product_line') or
    (project_kind='model_extension' and product_type='model_variant'))));
create index ix_product_registered_project on plm_product(project_kind,project_registered_at desc)
 where deleted_flag=0 and project_kind is not null;
-- Only already-converted PLM applications with real task authority establish a project.
-- Imported OA approvals and seed timeline clicks do not create or activate projects.
with evidence as (
 select a.project_id,min(a.created_at) registered_at from plm_mold_application a
 where a.deleted_flag=0 and a.source_system='plm' and a.status='converted'
   and a.provisioning_status='ready' and a.authorization_json->>'evidenceSource'='task'
   and nullif(a.authorization_json->>'taskId','') is not null
   and a.authorization_json->>'approverUserId'='16693147192083157833'
   and nullif(a.authorization_json->>'approvedAt','') is not null
   and not coalesce((a.execution_resolution_json->'historicalAssociation'->>'associationOnly')::boolean,false)
 group by a.project_id
)
update plm_product p set project_kind='model_extension',project_registered_at=e.registered_at
from evidence e where p.product_id=e.project_id and p.deleted_flag=0 and p.product_type='model_variant';
