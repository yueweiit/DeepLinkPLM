-- CRM has multiple legitimate SKU identities with the SAME article code.
-- Keep website_sku's meaning; never replace an article code with a source UUID.
alter table plm_partner_spec add constraint uk_partner_spec_parent unique(spec_id,record_id);
alter table plm_master_data_source add column partner_spec_id bigint,
 add constraint fk_master_partner_spec_parent foreign key(partner_spec_id,object_id) references plm_partner_spec(spec_id,record_id),
 add constraint ck_master_partner_spec_kind check(partner_spec_id is null or (object_type='partner' and source_kind='product_sku'));
drop index uk_partner_website_sku;
create unique index uk_partner_website_sku on plm_partner_spec(site,website_sku)
 where site<>'' and site<>'crm' and website_sku<>'' and deleted_flag=0;
create unique index uk_partner_crm_source_id on plm_partner_spec(record_id,(data->>'externalId'))
 where site='crm' and deleted_flag=0 and nullif(data->>'externalId','') is not null;
