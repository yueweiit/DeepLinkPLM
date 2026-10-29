-- User retired the prototype workflow. Retire only that mode, never standard model projects or launch_v1.
-- Existing related evidence is left intact for database recovery; no active archive/compatibility entry remains.
UPDATE plm_product SET deleted_flag=1, updated_at=CURRENT_TIMESTAMP, updated_by='retire_legacy_product_research'
WHERE product_type='product_line' AND development_mode='lightweight' AND deleted_flag=0;
