-- Sales availability is independent of production and archive lifecycle.
alter table plm_product add column sales_status varchar(16) not null default 'unconfirmed'
    check (sales_status in ('on_sale','off_sale','unconfirmed'));
alter table plm_product add column application_aliases text[] not null default '{}';
alter table plm_product add column mold_development_variant text not null default '';
alter table plm_phone_model_member add column mold_plan_status varchar(16) not null default 'planned'
    check (mold_plan_status in ('planned','not_planned'));
alter table plm_phone_model_member add column mold_plan_reason text;
alter table plm_phone_model_member add column mold_plan_updated_at timestamptz;
alter table plm_phone_model_member add column mold_plan_updated_by varchar(128);

-- Explicitly confirmed by the business owner on 2026-10-15, matched by formal product code.
update plm_product set sales_status=case when product_specific_code in ('HA','BD','DN','FA','BA','FB','WV') then 'on_sale' else 'off_sale' end,
 application_aliases=case product_specific_code
 when 'HA' then array['超队Súper Capitán'] when 'BD' then array['圣殿Case Blindaje']
 when 'DN' then array['Alterna幻甲'] when 'FA' then array['幻影Fantasía Case']
 when 'BA' then array['亮甲Rainbow 2.0'] when 'FB' then array['titanio 骑士2.0']
 when 'WV' then array['waves薇武士'] else '{}'::text[] end
 where product_type='product_line' and deleted_flag=0 and product_specific_code in ('HA','BD','DN','FA','BA','FB','WV','LT','RU','IM','HB');
create index idx_product_history_project_identity on plm_product(parent_product_id,phone_model_member_id,mold_development_variant)
 where deleted_flag=0 and project_kind='model_extension' and color is null;
