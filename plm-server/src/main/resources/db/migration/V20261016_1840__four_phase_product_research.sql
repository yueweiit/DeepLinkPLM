-- Preserve the five-phase snapshot before moving the same project to the confirmed four-phase model.
-- No review is inferred as passed and no physical mold/sample/pilot fact is manufactured.
insert into plm_product_development_event(product_id,kind,data,created_by)
select p.product_id,'phase_model_migrated',jsonb_build_object(
  'from','five_phase','to','four_phase','oldCurrentStepNo',p.current_step_no,
  'oldConfirmed',p.timeline_current_confirmed,'oldConfirmedNodeKey',p.timeline_confirmed_node_key,
  'oldState',coalesce(s.data,'{}'::jsonb)), 'four_phase_migration'
from plm_product p left join plm_product_development_state s on s.product_id=p.product_id
where p.development_mode='launch_v1' and p.deleted_flag=0;

insert into plm_product_development_state(product_id,version,data)
select p.product_id,coalesce(s.version,0)+1,
  coalesce(s.data,'{}'::jsonb)||jsonb_build_object('phaseModel','four_phase','legacyPhase',jsonb_build_object(
    'oldStage',p.current_step_no,'oldConfirmedNodeKey',p.timeline_confirmed_node_key,
    'note','原五阶段事实保留在迁移事件中；阶段映射不代表设计、试模、可靠性或试产评审已通过'))
from plm_product p left join plm_product_development_state s on s.product_id=p.product_id
where p.development_mode='launch_v1' and p.deleted_flag=0
on conflict(product_id) do update set version=excluded.version,data=excluded.data,updated_at=now();

update plm_product set current_step_no=case when current_step_no>=5 then 4 when current_step_no=4 then 2 else 1 end,
 timeline_confirmed_node_key=case when status in ('released','archived') and timeline_current_confirmed then 'LAUNCH_PILOT' else null end,
 timeline_current_confirmed=case when status in ('released','archived') then timeline_current_confirmed else false end,
 updated_at=now(),updated_by='four_phase_migration'
where development_mode='launch_v1' and deleted_flag=0;

-- Active revisions restart at design for technical changes, or pilot closeout for documentation-only changes.
update plm_product_development_state s set data=jsonb_set(s.data,'{revision,stage}',
  case when coalesce((s.data->'revision'->>'technical')::boolean,false) then '1'::jsonb else '4'::jsonb end)
where s.data->>'phaseModel'='four_phase' and s.data->'revision' is not null;
