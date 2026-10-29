-- Maintenance lifecycle is independent of research/production/physical completion.
-- Tombstones and original identities remain in the existing master tables.
DO $$ DECLARE target text; BEGIN
  FOREACH target IN ARRAY ARRAY['plm_product','plm_collaboration_item','plm_collaboration_mold_set'] LOOP
    EXECUTE format('ALTER TABLE %I ADD COLUMN master_data_status varchar(16) NOT NULL DEFAULT ''active'' CHECK(master_data_status IN (''active'',''inactive'')), ADD COLUMN master_data_version bigint NOT NULL DEFAULT 1, ADD COLUMN master_data_reason text, ADD COLUMN master_data_updated_at timestamptz, ADD COLUMN master_data_updated_by varchar(128)',target);
  END LOOP;
END $$;

-- Existing project/ledger/import writers also invalidate an open maintenance form.
CREATE FUNCTION plm_master_data_row_version() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.master_data_version=OLD.master_data_version THEN
    NEW.master_data_version=OLD.master_data_version+1;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER plm_product_master_data_version BEFORE UPDATE ON plm_product FOR EACH ROW EXECUTE FUNCTION plm_master_data_row_version();
CREATE TRIGGER plm_item_master_data_version BEFORE UPDATE ON plm_collaboration_item FOR EACH ROW EXECUTE FUNCTION plm_master_data_row_version();
CREATE TRIGGER plm_mold_set_master_data_version BEFORE UPDATE ON plm_collaboration_mold_set FOR EACH ROW EXECUTE FUNCTION plm_master_data_row_version();
