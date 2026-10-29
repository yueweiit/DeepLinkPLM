-- Each phone/set pair carries independent PC and TPU engineering evidence.
alter table plm_phone_brand add column if not exists prefixes jsonb not null default '[]'::jsonb;
do $$ declare constraint_name text; begin
  for constraint_name in select conname from pg_constraint
    where conrelid='plm_collaboration_phone_member'::regclass and contype='c'
      and pg_get_constraintdef(oid) like '%pc_confirmed%'
  loop execute format('alter table plm_collaboration_phone_member drop constraint %I',constraint_name); end loop;
end $$;
create unique index if not exists uq_collaboration_set_archive_id
  on plm_collaboration_mold_set(item_id,mold_set_id);
create table plm_collaboration_phone_set_compatibility (
  item_id bigint not null,
  phone_model_id bigint not null,
  mold_set_id bigint not null,
  pc_confirmed boolean not null default false,
  tpu_confirmed boolean not null default false,
  pc_evidence varchar(2000),
  tpu_evidence varchar(2000),
  created_by varchar(128) not null,
  updated_by varchar(128) not null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  primary key(item_id,phone_model_id,mold_set_id),
  foreign key(item_id,phone_model_id) references plm_collaboration_phone_member(item_id,phone_model_id) on delete cascade,
  foreign key(item_id,mold_set_id) references plm_collaboration_mold_set(item_id,mold_set_id),
  check(not pc_confirmed or nullif(btrim(pc_evidence),'') is not null),
  check(not tpu_confirmed or nullif(btrim(tpu_evidence),'') is not null)
);
create index ix_phone_set_compatibility_phone on plm_collaboration_phone_set_compatibility(phone_model_id,mold_set_id);
-- Expand only previously selected, correctly owned sets. Never create a set, stock or fit claim.
insert into plm_collaboration_phone_set_compatibility
  (item_id,phone_model_id,mold_set_id,pc_confirmed,tpu_confirmed,pc_evidence,tpu_evidence,created_by,updated_by)
select cm.item_id,cm.phone_model_id,s.mold_set_id,
  cm.pc_confirmed and nullif(btrim(cm.evidence),'') is not null,
  cm.tpu_confirmed and nullif(btrim(cm.evidence),'') is not null,
  nullif(btrim(cm.evidence),''),nullif(btrim(cm.evidence),''),cm.created_by,cm.updated_by
from plm_collaboration_phone_member cm
join plm_collaboration_mold_set s on s.item_id=cm.item_id and s.mold_set_id=any(cm.mold_set_ids) and s.deleted_flag=0;
