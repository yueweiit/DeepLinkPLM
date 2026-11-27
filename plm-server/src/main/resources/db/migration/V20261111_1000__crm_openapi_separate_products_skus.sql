-- New projection columns/classification must invalidate ETags and notify existing CRM clients.
-- Keep the original source-capture function: it already follows OLD/NEW product and item links.
drop trigger crm_openapi_capture on plm_product;
create trigger crm_openapi_capture after insert or update or delete on plm_product
for each row execute function plm_crm_openapi_capture_source(
  'parent_product_id,product_name,product_code,master_data_status,deleted_flag,product_type,project_kind,finished_product_code,phone_model_member_id,phone_model_code,color,color_code');

-- If already initialized, enqueue a bounded-by-current-resources refresh without resetting epoch
-- or old cursors. If not initialized, the existing first initialization seeds the new projection.
do $$
declare target bigint;
begin
  if exists(select 1 from plm_crm_openapi_control where singleton and ready and capture_enabled) then
    for target in select entity_id from plm_crm_openapi_resource
      where entity_type='MOLD_SET' and lifecycle<>'DELETED' order by entity_id
    loop
      perform plm_crm_openapi_touch('MOLD_SET',target);
    end loop;
  end if;
end $$;
