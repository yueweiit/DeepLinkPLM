-- Keep the original identities, physical facts and full four-phase snapshot.
-- A historical mold or trial never invents a preceding 3D prototype confirmation.
insert into plm_product_development_event(product_id,kind,data,created_by)
select p.product_id,'phase_model_migrated',jsonb_build_object(
  'from',coalesce(s.data->>'phaseModel','four_phase'),'to','eight_step',
  'oldCurrentStepNo',p.current_step_no,'oldConfirmed',p.timeline_current_confirmed,
  'oldConfirmedNodeKey',p.timeline_confirmed_node_key,'oldState',coalesce(s.data,'{}'::jsonb)),
  'eight_step_migration'
from plm_product p left join plm_product_development_state s on s.product_id=p.product_id
where p.development_mode='launch_v1' and p.deleted_flag=0
  and coalesce(s.data->>'phaseModel','four_phase')<>'eight_step';

insert into plm_product_development_state(product_id,version,data)
select p.product_id,coalesce(s.version,0)+1,coalesce(s.data,'{}'::jsonb)||jsonb_build_object(
  'phaseModel','eight_step',
  'legacyPhase',jsonb_build_object('phaseModel',coalesce(s.data->>'phaseModel','four_phase'),
    'oldStage',p.current_step_no,'oldState',coalesce(s.data,'{}'::jsonb),
    'prototypeEvidenceMissing',coalesce(p.current_step_no,1)>1),
  'validationBasis',coalesce(s.data->'stage3Basis','{}'::jsonb))
from plm_product p left join plm_product_development_state s on s.product_id=p.product_id
where p.development_mode='launch_v1' and p.deleted_flag=0
  and coalesce(s.data->>'phaseModel','four_phase')<>'eight_step'
on conflict(product_id) do update set version=excluded.version,data=excluded.data,updated_at=now();

update plm_product p set current_step_no=case
    when p.status in ('released','archived') then 8
    when coalesce((s.data->'legacyPhase'->>'oldStage')::int,1)=1 then 1
    when (s.data->'legacyPhase'->>'oldStage')::int=2 then
      case when exists(select 1 from plm_mold_application a where a.deleted_flag=0
        and (a.project_id=p.product_id or a.product_line_id=p.product_id) and a.mold_set_id is not null)
        or exists(select 1 from jsonb_array_elements(coalesce(s.data->'moldLinks','[]'::jsonb)) l
          where coalesce(l->>'moldSetId','0')::bigint>0) then 6 else 5 end
    when (s.data->'legacyPhase'->>'oldStage')::int=3 then 6
    else 7 end,
  timeline_current_confirmed=false,
  timeline_confirmed_node_key=null,
  updated_at=now(),updated_by='eight_step_migration'
from plm_product_development_state s where s.product_id=p.product_id and s.data->>'phaseModel'='eight_step'
  and p.development_mode='launch_v1' and p.deleted_flag=0;

update plm_product_development_state s set data=jsonb_set(s.data,'{revision,stage}',
  case when coalesce((s.data->'revision'->>'technical')::boolean,false) then '2'::jsonb else '7'::jsonb end)
where s.data->>'phaseModel'='eight_step' and s.data->'revision' is not null;
