-- Locations share partner record revisions/events; stock remains the original sample ledger.
ALTER TABLE plm_partner_record DROP CONSTRAINT plm_partner_record_kind_check;
ALTER TABLE plm_partner_record ADD CONSTRAINT plm_partner_record_kind_check CHECK(kind IN
 ('product','sale','sample','supplier_quote','sample_fee','inspection','fulfilment','listing','selection','sample_identity','sample_shipment','sample_location'));
CREATE INDEX idx_partner_sample_location_site ON plm_partner_record(site,deleted_flag,status) WHERE kind='sample_location';
