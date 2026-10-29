-- Online source metadata only; original mold, part, date and history tables remain authoritative.
create table plm_mold_sheet_config (
  config_id integer primary key check(config_id=1),
  node_id varchar(128) not null,
  style_aliases jsonb not null default '{"Waveshell 薇武士":"WV","Súper Capitán超队":"HA","Rainbow 2.0亮甲":"BA","Fantasía幻影":"FA","Blindaje圣殿":"BD"}',
  automatic_enabled boolean not null default false,
  interval_minutes integer not null default 60 check(interval_minutes between 15 and 1440),
  operator_user_id bigint references sys_user(user_id),
  initialized_at timestamptz,
  last_success_at timestamptz,
  last_attempt_at timestamptz,
  last_error varchar(1000),
  running_id uuid,
  running_since timestamptz,
  version_no bigint not null default 1,
  updated_at timestamptz not null default now()
);
insert into plm_mold_sheet_config(config_id,node_id) values(1,'Y1OQX0akWm3ZYp24tb32l4mjJGlDd3mE');

create table plm_mold_sheet_binding (
  source_key varchar(128) primary key,
  mold_set_id bigint not null unique references plm_collaboration_mold_set(mold_set_id),
  source_identity jsonb not null,
  field_state jsonb not null default '{}',
  source_notes text not null default '',
  source_row_no integer not null,
  version_no bigint not null default 1,
  updated_at timestamptz not null default now()
);
create table plm_mold_sheet_run (
  run_id uuid primary key,
  actor_id bigint not null references sys_user(user_id),
  mode varchar(16) not null check(mode in ('manual','automatic')),
  status varchar(16) not null check(status in ('running','completed','failed')),
  started_at timestamptz not null default now(),
  finished_at timestamptz,
  source_hash varchar(64),
  source_snapshot jsonb,
  result jsonb not null default '{}'
);

-- Every saved manual date change is protected, including clearing a date and writers
-- outside the schedule screen. The trusted sync writer sets its marker only inside
-- its own transaction and restores it before leaving; this is never a request option.
create function plm_protect_manual_mold_sheet_dates() returns trigger language plpgsql as $$
declare
  names jsonb := '{"openedDate":"opened_date","plannedMoldDeliveryDate":"planned_mold_delivery_date","forecastDeliveryDate":"forecast_delivery_date","forecastFactoryDate":"forecast_factory_date","plannedFactoryArrivalDate":"planned_factory_arrival_date","actualShipDate":"actual_ship_date","actualMexicoArrivalDate":"actual_mexico_arrival_date"}';
  pair record;
begin
  if current_setting('plm.mold_sheet_sync_write',true)='on' then return new; end if;
  for pair in select key,value #>> '{}' as col from jsonb_each(names) loop
    if (to_jsonb(old)->pair.col) is distinct from (to_jsonb(new)->pair.col) then
      -- Row zero holds manual intent until an actual source row is associated.
      insert into plm_mold_sheet_binding(source_key,mold_set_id,source_identity,source_row_no)
        values('manual:'||new.mold_set_id,new.mold_set_id,'{"pendingManual":true}',0)
        on conflict(mold_set_id) do nothing;
      update plm_mold_sheet_binding set
        field_state=jsonb_set(field_state,array[pair.key],coalesce(field_state->pair.key,'{}'::jsonb)
          ||jsonb_build_object('manual',true,'manualValue',to_jsonb(new)->pair.col,'manualAt',clock_timestamp()),true),
        version_no=version_no+1,updated_at=now()
      where mold_set_id=new.mold_set_id;
    end if;
  end loop;
  return new;
end $$;
create trigger protect_manual_mold_sheet_dates after update on plm_collaboration_mold_set
  for each row execute function plm_protect_manual_mold_sheet_dates();
