-- Candidate selections reuse existing records, revisions, files and audit events.
-- No historical product, supplier, sample or physical stock is rewritten.
ALTER TABLE plm_partner_record DROP CONSTRAINT plm_partner_record_kind_check;
ALTER TABLE plm_partner_record ADD CONSTRAINT plm_partner_record_kind_check
 CHECK(kind IN ('product','sale','sample','supplier_quote','sample_fee','inspection','fulfilment','listing','selection'));
