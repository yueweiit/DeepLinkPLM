-- Extend the existing material masters. Quantities and original inventory statuses remain inventory facts.
alter table plm_inventory add column if not exists version bigint not null default 0;
alter table plm_inventory add column if not exists lifecycle_status varchar(16) not null default 'active' check (lifecycle_status in ('active','inactive'));
alter table plm_inventory add column if not exists maintenance_data jsonb not null default '{}';
alter table plm_material_group add column if not exists version bigint not null default 0;
alter table plm_material_group add column if not exists maintenance_data jsonb not null default '{}';
-- A historical code remains reserved even after an accidental master is deleted. Existing rows are not rewritten.
create or replace function plm_inventory_identity_guard() returns trigger language plpgsql as $$
begin
 if TG_OP='UPDATE' then
  if NEW.inventory_code is distinct from OLD.inventory_code then raise exception '物料编码创建后不可更改'; end if;
  if OLD.deleted_flag<>0 and NEW.deleted_flag=0 then raise exception '已删除物料不能由同步或保存恢复'; end if;
 else
  perform pg_advisory_xact_lock(hashtextextended('inventory-code:'||btrim(NEW.inventory_code),0));
  if exists(select 1 from plm_inventory where btrim(inventory_code)=btrim(NEW.inventory_code)) then raise exception '物料编码已存在或已被历史档案保留'; end if;
 end if;
 return NEW;
end $$;
create trigger tr_inventory_identity_guard before insert or update on plm_inventory for each row execute function plm_inventory_identity_guard();
create or replace function plm_material_group_identity_guard() returns trigger language plpgsql as $$
begin
 if TG_OP='UPDATE' then
  if NEW.group_code is distinct from OLD.group_code then raise exception '物料组编码创建后不可更改'; end if;
  if OLD.deleted_flag<>0 and NEW.deleted_flag=0 then raise exception '已删除物料组不能恢复'; end if;
 elsif NEW.source_system='PLM' then
  perform pg_advisory_xact_lock(hashtextextended('material-group-code:'||btrim(NEW.group_code),0));
  if exists(select 1 from plm_material_group where btrim(group_code)=btrim(NEW.group_code)) then raise exception '物料组编码已存在或已被历史档案保留'; end if;
 end if;
 return NEW;
end $$;
create trigger tr_material_group_identity_guard before insert or update on plm_material_group for each row execute function plm_material_group_identity_guard();
