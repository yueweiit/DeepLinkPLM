-- Functions are invoker-rights, bound to the migration's schema, with no HTTP/secret data.
create function plm_crm_openapi_lifecycle(kind text,target bigint) returns text
language plpgsql stable set search_path from current as $$
declare r jsonb; p jsonb; ancestor bigint; seen bigint[]='{}';
begin
  if kind='PHONE_MODEL' then
    select to_jsonb(m),to_jsonb(b) into r,p from plm_phone_model_member m left join plm_phone_brand b on b.id=m.brand_id where m.id=target;
    if r is null or coalesce((r->>'deleted_flag')::int,0)<>0 then return 'DELETED'; end if;
    if r->>'status'<>'enabled' or p is null or p->>'status'<>'enabled' or coalesce((p->>'deleted_flag')::int,0)<>0 then return 'INACTIVE'; end if;
    return 'ACTIVE';
  elsif kind='MOLD_SET' then
    select to_jsonb(s),to_jsonb(i) into r,p from plm_collaboration_mold_set s left join plm_collaboration_item i on i.item_id=s.item_id where s.mold_set_id=target;
    if r is null or coalesce((r->>'deleted_flag')::int,0)<>0 then return 'DELETED'; end if;
    -- A deleted parent makes the existing PLM schedule unreadable as well: emit a tombstone.
    if p is null or coalesce((p->>'deleted_flag')::int,0)<>0 then return 'DELETED'; end if;
    if coalesce(r->>'master_data_status','active')<>'active' or coalesce(p->>'master_data_status','active')<>'active' then return 'INACTIVE'; end if;
    ancestor=(p->>'product_line_id')::bigint;
    while ancestor is not null loop
      if ancestor=any(seen) then return 'INACTIVE'; end if;
      seen=array_append(seen,ancestor);
      select to_jsonb(x) into p from plm_product x where x.product_id=ancestor;
      if p is null or coalesce((p->>'deleted_flag')::int,0)<>0 then return 'DELETED'; end if;
      if coalesce(p->>'master_data_status','active')<>'active' then return 'INACTIVE'; end if;
      ancestor=(p->>'parent_product_id')::bigint;
    end loop;
    return 'ACTIVE';
  end if;
  raise exception 'Unsupported CRM entity type';
end $$;

create function plm_crm_openapi_touch(kind text,target bigint) returns void
language plpgsql volatile set search_path from current as $$
declare epoch uuid; prior text; next_state text; rev bigint; operation text;
begin
  if target is null then return; end if;
  select stream_epoch into epoch from plm_crm_openapi_control where singleton and capture_enabled;
  if epoch is null then return; end if;
  -- Lock/version before allocating capture_id; serializes each resource's committed revisions.
  insert into plm_crm_openapi_resource(entity_type,entity_id,revision,lifecycle) values(kind,target,1,'ACTIVE') on conflict do nothing;
  select lifecycle into prior from plm_crm_openapi_resource where entity_type=kind and entity_id=target for update;
  next_state=plm_crm_openapi_lifecycle(kind,target);
  update plm_crm_openapi_resource set revision=revision+1,lifecycle=next_state,projection_updated_at=clock_timestamp()
    where entity_type=kind and entity_id=target returning revision into rev;
  operation=case when next_state='DELETED' then 'DELETE'
    when next_state='INACTIVE' and prior<>'INACTIVE' then 'DISABLE'
    when next_state='ACTIVE' and prior in ('INACTIVE','DELETED') then 'ENABLE' else 'UPSERT' end;
  insert into plm_crm_openapi_capture(stream_epoch,entity_type,entity_id,revision,action,lifecycle)
    values(epoch,kind,target,rev,operation,next_state);
end $$;

create function plm_crm_openapi_phone_sets(phone bigint) returns table(id bigint)
language sql stable set search_path from current as $$
  select mold_set_id from plm_collaboration_phone_set_compatibility where phone_model_id=phone
  union select mold_set_id from plm_mold_application where phone_model_id=phone and mold_set_id is not null
  union select s.mold_set_id from plm_collaboration_phone_member m join plm_collaboration_mold_set s on s.item_id=m.item_id where m.phone_model_id=phone
$$;

create function plm_crm_openapi_capture_source() returns trigger
language plpgsql volatile set search_path from current as $$
declare old_row jsonb=case when tg_op='INSERT' then '{}'::jsonb else to_jsonb(old) end;
  new_row jsonb=case when tg_op='DELETE' then '{}'::jsonb else to_jsonb(new) end;
  keys text[]; k text; kinds text[]='{}'; targets bigint[]='{}'; target bigint; phone bigint; source jsonb; ix int;
begin
  if not exists(select 1 from plm_crm_openapi_control where singleton and capture_enabled) then return null; end if;
  keys=string_to_array(tg_argv[0],',');
  if tg_op='UPDATE' and not exists(select 1 from unnest(keys) f where old_row->f is distinct from new_row->f) then return null; end if;
  -- OLD and NEW are both retained, including links removed by DELETE or reparenting.
  foreach source in array array[old_row,new_row] loop
    if source='{}'::jsonb then continue; end if;
    if tg_table_name='plm_collaboration_mold_set' then
      kinds=array_append(kinds,'MOLD_SET');targets=array_append(targets,(source->>'mold_set_id')::bigint);
    elsif tg_table_name in ('plm_collaboration_mold_part','plm_collaboration_phone_set_compatibility','plm_mold_application') then
      kinds=array_append(kinds,'MOLD_SET');targets=array_append(targets,(source->>'mold_set_id')::bigint);
    elsif tg_table_name in ('plm_collaboration_item','plm_collaboration_item_project','plm_collaboration_phone_member') then
      for target in select mold_set_id from plm_collaboration_mold_set where item_id=(source->>'item_id')::bigint loop
        kinds=array_append(kinds,'MOLD_SET');targets=array_append(targets,target);
      end loop;
    elsif tg_table_name in ('plm_phone_model_member','plm_phone_brand','plm_code_item') then
      for phone in select m.id from plm_phone_model_member m where
        (tg_table_name='plm_phone_model_member' and m.id=(source->>'id')::bigint)
        or (tg_table_name='plm_phone_brand' and m.brand_id=(source->>'id')::bigint)
        or (tg_table_name='plm_code_item' and m.code_item_id=(source->>'code_item_id')::bigint)
      loop
        kinds=array_append(kinds,'PHONE_MODEL');targets=array_append(targets,phone);
        for target in select id from plm_crm_openapi_phone_sets(phone) loop
          kinds=array_append(kinds,'MOLD_SET');targets=array_append(targets,target);
        end loop;
      end loop;
      if tg_table_name='plm_phone_model_member' then
        phone=(source->>'id')::bigint;kinds=array_append(kinds,'PHONE_MODEL');targets=array_append(targets,phone);
        for target in select id from plm_crm_openapi_phone_sets(phone) loop
          kinds=array_append(kinds,'MOLD_SET');targets=array_append(targets,target);
        end loop;
      elsif tg_table_name='plm_code_item' then
        for target in select s.mold_set_id from plm_collaboration_mold_set s join plm_collaboration_item i on i.item_id=s.item_id where i.phone_model_code_item_id=(source->>'code_item_id')::bigint loop
          kinds=array_append(kinds,'MOLD_SET');targets=array_append(targets,target);
        end loop;
      end if;
    elsif tg_table_name='plm_product' then
      for target in
        with recursive descendants(id) as (
          select (source->>'product_id')::bigint union select p.product_id from plm_product p join descendants d on p.parent_product_id=d.id
        ) select distinct s.mold_set_id from plm_collaboration_mold_set s join plm_collaboration_item i on i.item_id=s.item_id
          where i.product_line_id in (select id from descendants) or exists(select 1 from plm_collaboration_item_project ip where ip.item_id=i.item_id and ip.project_id in (select id from descendants))
      loop kinds=array_append(kinds,'MOLD_SET');targets=array_append(targets,target); end loop;
    elsif tg_table_name='plm_product_mold_code' then
      for target in select distinct mold_set_id from plm_collaboration_mold_part where product_mold_code_id=(source->>'product_mold_code_id')::bigint
      loop kinds=array_append(kinds,'MOLD_SET');targets=array_append(targets,target); end loop;
    elsif tg_table_name='sys_user' then
      for target in select mold_set_id from plm_collaboration_mold_set where owner_user_id=(source->>'user_id')::bigint
      loop kinds=array_append(kinds,'MOLD_SET');targets=array_append(targets,target); end loop;
    end if;
  end loop;
  for k,target in select distinct kinds[n],targets[n] from generate_subscripts(targets,1) n where targets[n] is not null order by 1,2
  loop perform plm_crm_openapi_touch(k,target); end loop;
  return null;
end $$;

-- No source audit/body/password columns are persisted, only the following dependencies are watched.
create trigger crm_openapi_capture after insert or update or delete on plm_collaboration_mold_set for each row execute function plm_crm_openapi_capture_source('item_id,set_no,set_name,opened_date,planned_mold_delivery_date,actual_ship_date,planned_factory_arrival_date,actual_mexico_arrival_date,planned_acceptance_date,planned_production_date,owner_user_id,actual_trial_date,actual_production_date,market_ready_date,master_data_status,deleted_flag');
create trigger crm_openapi_capture after insert or update or delete on plm_collaboration_mold_part for each row execute function plm_crm_openapi_capture_source('mold_set_id,part_type,product_mold_code_id,original_mold_code,status,factory_arrival_date,acceptance_status,acceptance_date,current_location,deleted_flag');
create trigger crm_openapi_capture after insert or update or delete on plm_collaboration_item for each row execute function plm_crm_openapi_capture_source('product_line_id,style_name,compatible_models,phone_model_code,phone_model_code_item_id,master_data_status,deleted_flag');
create trigger crm_openapi_capture after insert or update or delete on plm_collaboration_item_project for each row execute function plm_crm_openapi_capture_source('item_id,project_id');
create trigger crm_openapi_capture after insert or update or delete on plm_collaboration_phone_member for each row execute function plm_crm_openapi_capture_source('item_id,phone_model_id,mold_set_ids,pc_confirmed,tpu_confirmed,evidence');
create trigger crm_openapi_capture after insert or update or delete on plm_collaboration_phone_set_compatibility for each row execute function plm_crm_openapi_capture_source('item_id,phone_model_id,mold_set_id,pc_confirmed,tpu_confirmed,pc_evidence,tpu_evidence');
create trigger crm_openapi_capture after insert or update or delete on plm_mold_application for each row execute function plm_crm_openapi_capture_source('mold_set_id,phone_model_id,source_system,execution_intent,execution_status,provisioning_status,execution_resolution_json,deleted_flag');
create trigger crm_openapi_capture after insert or update or delete on plm_phone_model_member for each row execute function plm_crm_openapi_capture_source('brand_id,model_name,code_item_id,status,deleted_flag');
create trigger crm_openapi_capture after insert or update or delete on plm_phone_brand for each row execute function plm_crm_openapi_capture_source('name,status,deleted_flag');
create trigger crm_openapi_capture after insert or update or delete on plm_code_item for each row execute function plm_crm_openapi_capture_source('code_value,status,deleted_flag');
create trigger crm_openapi_capture after insert or update or delete on plm_product for each row execute function plm_crm_openapi_capture_source('parent_product_id,product_name,product_code,master_data_status,deleted_flag');
create trigger crm_openapi_capture after insert or update or delete on plm_product_mold_code for each row execute function plm_crm_openapi_capture_source('mold_code,status,deleted_flag');
create trigger crm_openapi_capture after insert or update or delete on sys_user for each row execute function plm_crm_openapi_capture_source('display_name,status,deleted_flag');

-- Operator-only maintenance function; never exposed via the public HTTP API.
-- Requires a controlled maintenance window, briefly blocking writes to the source tables.
create function plm_crm_openapi_initialize() returns uuid
language plpgsql volatile set search_path from current as $$
declare epoch uuid; today date=(clock_timestamp() at time zone 'Asia/Shanghai')::date;
begin
  lock table plm_code_item,plm_collaboration_item,plm_collaboration_item_project,plm_collaboration_mold_part,
    plm_collaboration_mold_set,plm_collaboration_phone_member,plm_collaboration_phone_set_compatibility,
    plm_mold_application,plm_phone_brand,plm_phone_model_member,plm_product,plm_product_mold_code,sys_user in share row exclusive mode;
  update plm_crm_openapi_control set capture_enabled=false,ready=false,stream_epoch=gen_random_uuid(),published_head=0,retained_after=0,last_business_date=today where singleton returning stream_epoch into epoch;
  insert into plm_crm_openapi_resource(entity_type,entity_id,revision,lifecycle,business_date)
    select 'MOLD_SET',mold_set_id,1,plm_crm_openapi_lifecycle('MOLD_SET',mold_set_id),today from plm_collaboration_mold_set where deleted_flag=0
    on conflict(entity_type,entity_id) do update set revision=plm_crm_openapi_resource.revision+1,lifecycle=excluded.lifecycle,business_date=today,projection_updated_at=clock_timestamp();
  insert into plm_crm_openapi_resource(entity_type,entity_id,revision,lifecycle)
    select 'PHONE_MODEL',id,1,plm_crm_openapi_lifecycle('PHONE_MODEL',id) from plm_phone_model_member where coalesce((to_jsonb(plm_phone_model_member)->>'deleted_flag')::int,0)=0
    on conflict(entity_type,entity_id) do update set revision=plm_crm_openapi_resource.revision+1,lifecycle=excluded.lifecycle,projection_updated_at=clock_timestamp();
  -- Reconcile tombstones already tracked before reinitialization, not historical snapshots.
  update plm_crm_openapi_resource r set lifecycle='DELETED',revision=revision+1,projection_updated_at=clock_timestamp()
    where plm_crm_openapi_lifecycle(r.entity_type,r.entity_id)='DELETED';
  update plm_crm_openapi_control set capture_enabled=true,ready=true where singleton;
  return epoch;
end $$;
