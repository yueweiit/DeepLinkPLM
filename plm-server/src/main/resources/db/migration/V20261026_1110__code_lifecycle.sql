-- Preserve original identities and their historical codes; deletion is a tombstone.
alter table plm_code_item add column if not exists version bigint not null default 0;
alter table plm_phone_brand add column if not exists version bigint not null default 0,
 add column if not exists deleted_flag integer not null default 0;
alter table plm_phone_model_member add column if not exists version bigint not null default 0,
 add column if not exists deleted_flag integer not null default 0;
-- Existing active uniqueness remains for historical duplicates. Every new insert also reserves
-- all historic values under the shared maintenance lock; old tombstones are never reused.

-- Guard old import/allocation writers too. All historical codes remain occupied, including
-- soft-deleted rows. Inserts share the same lock as engineering code maintenance.
create or replace function plm_guard_code_identity_lifecycle() returns trigger language plpgsql as $$
begin
 if tg_op='INSERT' then
  perform pg_advisory_xact_lock(66817399);
  if exists(select 1 from plm_code_item c where c.code_type=new.code_type and c.code_value=new.code_value) then
   raise exception using errcode='23505', message='编码身份已存在或历史保留，不能创建同码新身份';
  end if;
  return new;
 end if;
 if old.code_type is distinct from new.code_type or old.code_value is distinct from new.code_value then
  raise exception using errcode='23514', message='原编码和类型不可改写';
 end if;
 if old.deleted_flag<>0 and new.deleted_flag=0 then
  raise exception using errcode='23514', message='已删除编码不可复活，请核对历史档案';
 end if;
 if new.version is null or new.version<=old.version then new.version=old.version+1; end if;
 return new;
end $$;
create trigger trg_code_identity_lifecycle before insert or update on plm_code_item
 for each row execute function plm_guard_code_identity_lifecycle();

create or replace function plm_guard_phone_reference_lifecycle() returns trigger language plpgsql as $$
begin
 if old.deleted_flag<>0 and new.deleted_flag=0 then
  raise exception using errcode='23514', message='已删除手机或品牌不可复活，请核对历史身份';
 end if;
 if new.version is null or new.version<=old.version then new.version=old.version+1; end if;
 return new;
end $$;
create trigger trg_phone_brand_lifecycle before update on plm_phone_brand
 for each row execute function plm_guard_phone_reference_lifecycle();
create trigger trg_phone_member_lifecycle before update on plm_phone_model_member
 for each row execute function plm_guard_phone_reference_lifecycle();
