-- Existing standard Plus member and the exact OA spelling refer to the same Samsung suffix.
-- Preserve both original forms; no global punctuation or generation stripping.
update plm_phone_model_member m set aliases=array_append(m.aliases,'Samsung S26 +')
 from plm_phone_brand b where b.id=m.brand_id and lower(b.name)='samsung'
 and m.model_name='Samsung Galaxy S26 Plus' and not ('Samsung S26 +'=any(m.aliases));
