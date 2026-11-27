-- Existing, uncoded research projects opt in only when a proposal is saved.
-- Reading a page or checking availability must never change its identity mode.
create function plm_can_enroll_product_identity(target bigint) returns boolean
language sql stable set search_path from current as $$
    with recursive family(product_id) as (
        select product_id from plm_product where product_id=target
        union select p.product_id from plm_product p join family f on p.parent_product_id=f.product_id
    )
    select exists(select 1 from plm_product p where p.product_id=target
        and p.product_identity_mode='legacy' and p.product_type='product_line'
        and p.development_mode='launch_v1' and p.parent_product_id is null
        and p.deleted_flag=0 and p.status not in ('released','archived')
        and coalesce(p.lock_status,'') not in ('frozen','abandoned')
        and p.current_step_no between 2 and 8
        and not (p.timeline_current_confirmed is true and coalesce(p.timeline_confirmed_node_key,'')='LAUNCH_SHIP'))
    and not exists(select 1 from plm_product p join family f using(product_id)
        where nullif(btrim(p.product_specific_code),'') is not null
           or nullif(btrim(p.product_code_prefix),'') is not null
           or nullif(btrim(p.mold_code_prefix),'') is not null
           or nullif(btrim(p.finished_product_code),'') is not null
           or upper(coalesce(p.product_code,'')) ~ '^[MN]')
    and not exists(select 1 from plm_product_identity_evidence e join family f on e.owner_id=f.product_id)
    and not exists(select 1 from plm_product_mold_code m join family f using(product_id))
    and not exists(select 1 from plm_inventory i join family f on f.product_id=nullif(to_jsonb(i)->>'product_id','')::bigint)
    and not exists(select 1 from plm_mold_application a
        where (a.project_id in (select product_id from family) or a.product_line_id in (select product_id from family))
          and (nullif(btrim(a.generated_codes),'') is not null
            or nullif(to_jsonb(a)->>'mold_set_id','') is not null
            or nullif(to_jsonb(a)->>'target_mold_set_id','') is not null
            or exists(select 1 from plm_mold_code_source s where s.approval_instance_id=a.external_instance_id)))
$$;

create or replace function plm_guard_reserved_product_code() returns trigger language plpgsql as $$
declare code text;
begin
    if TG_OP='UPDATE' and old.product_identity_mode<>new.product_identity_mode then
        if not (old.product_identity_mode='legacy' and new.product_identity_mode='reserved_v1'
            and plm_can_enroll_product_identity(old.product_id)
            and exists(select 1 from plm_product_development_event e where e.product_id=old.product_id
                and e.kind='product_code_enrolled')) then
            raise exception '已有正式编码或业务编码引用的项目不能切换产品编码机制';
        end if;
    end if;
    if new.product_type='product_line' then
        for code in select distinct v.code from (values
            (upper(btrim(new.product_specific_code))),
            (regexp_replace(upper(btrim(new.product_code_prefix)),'^[MN](?=[A-Z]{2}$)','')),
            (substring(upper(new.mold_code_prefix) from '^M([A-Z]{2})')),
            (substring(upper(new.product_code) from '^[MN]([A-Z]{2})[0-9]')),
            (substring(upper(new.finished_product_code) from '^N([A-Z]{2})[0-9]'))
        ) v(code) where v.code ~ '^[A-Z]{2}$' order by v.code loop
            perform pg_advisory_xact_lock(hashtextextended('product-specific:'||code,0));
            if exists(select 1 from plm_product_code_reservation r where r.specific_code=code
                and r.state in ('proposed','confirmed','invalid_held') and r.product_id<>new.product_id) then
                raise exception '产品编码已被拟定或正式身份占用';
            end if;
        end loop;
    end if;
    return new;
end $$;
