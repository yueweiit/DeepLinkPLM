-- Extend the colour-neutral archive; do not rewrite original names, codes or physical sets.
alter table plm_collaboration_item
  add column if not exists phone_model_code_item_id bigint references plm_code_item(code_item_id);

-- A code and its normalized label must both identify the same active master record.
-- Any collision is a review stop, never an automatic archive/project/production merge.
do $$
begin
  if exists (
    select i.product_line_id,c.code_item_id
      from plm_collaboration_item i
      join plm_code_item c on c.code_type='phone_model' and c.deleted_flag=0
        and c.code_value=btrim(i.phone_model_code)
        and lower(btrim(c.code_name))=lower(btrim(i.compatible_models))
     where i.deleted_flag=0 and i.product_line_id is not null
     group by i.product_line_id,c.code_item_id having count(*)>1
  ) then
    raise exception 'Collaboration master-link collision: review archives before migration; no automatic merge';
  end if;
end $$;

update plm_collaboration_item i
   set phone_model_code_item_id=c.code_item_id
  from plm_code_item c
 where i.phone_model_code_item_id is null and i.deleted_flag=0
   and c.code_type='phone_model' and c.deleted_flag=0
   and c.code_value=btrim(i.phone_model_code)
   and lower(btrim(c.code_name))=lower(btrim(i.compatible_models));

create unique index if not exists uq_collaboration_item_master_identity
  on plm_collaboration_item(product_line_id,phone_model_code_item_id)
  where deleted_flag=0 and product_line_id is not null and phone_model_code_item_id is not null;

-- Text identity remains only for candidates whose master-data relationship is incomplete.
drop index if exists uq_collaboration_item_identity;
create unique index uq_collaboration_item_identity
  on plm_collaboration_item(lower(btrim(style_name)),lower(btrim(compatible_models)))
  where deleted_flag=0 and (product_line_id is null or phone_model_code_item_id is null);
