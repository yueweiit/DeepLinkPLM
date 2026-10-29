-- New projects opt in through development_mode=launch_v1; no existing project is remapped.
-- Reuse product draft/events and rounds. Only canonical mold-node state is missing on the original mold object.
alter table plm_collaboration_mold_set add column development_data jsonb not null default '{}'::jsonb;
create index idx_research_event_kind on plm_product_development_event(product_id,kind,event_id desc);
