-- Reuse the commercial record/event/revision/file infrastructure, without reinterpreting sales.
ALTER TABLE plm_partner_record DROP CONSTRAINT plm_partner_record_kind_check;
ALTER TABLE plm_partner_record ADD CONSTRAINT plm_partner_record_kind_check
 CHECK(kind IN ('product','sale','sample','supplier_quote','sample_fee','inspection','fulfilment','listing'));
ALTER TABLE plm_partner_record ADD COLUMN parent_record_id bigint REFERENCES plm_partner_record(record_id);
CREATE INDEX ix_partner_parent ON plm_partner_record(parent_record_id,kind,record_id DESC)
 WHERE deleted_flag=0;
-- A listing confirmation with unchanged evidence is reused, including after an unknown outcome.
CREATE UNIQUE INDEX uk_partner_listing_basis ON plm_partner_record(parent_record_id,(data->>'basisHash'))
 WHERE kind='listing' AND deleted_flag=0;
ALTER TABLE plm_pilot_account DROP CONSTRAINT plm_pilot_account_role_code_check;
ALTER TABLE plm_pilot_account ADD CONSTRAINT plm_pilot_account_role_code_check
 CHECK(role_code IN ('administrator','domestic','finance','supplier','mexico','overseas_sales'));
