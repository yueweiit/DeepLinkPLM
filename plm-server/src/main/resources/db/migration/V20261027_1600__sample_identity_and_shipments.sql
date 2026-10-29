-- A durable type code groups existing SP receipts; no receipt, stock, event or old code is rewritten.
ALTER TABLE plm_partner_record DROP CONSTRAINT plm_partner_record_kind_check;
ALTER TABLE plm_partner_record ADD CONSTRAINT plm_partner_record_kind_check CHECK(kind IN
 ('product','sale','sample','supplier_quote','sample_fee','inspection','fulfilment','listing','selection','sample_identity','sample_shipment'));
CREATE TABLE plm_sample_identity (
 identity_id bigint PRIMARY KEY REFERENCES plm_partner_record(record_id),
 identity_key text NOT NULL UNIQUE,
 source_type varchar(32) NOT NULL,
 basis jsonb NOT NULL DEFAULT '{}',
 created_at timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE plm_partner_record ADD COLUMN sample_identity_id bigint REFERENCES plm_sample_identity(identity_id);
ALTER TABLE plm_partner_record ADD CONSTRAINT ck_sample_identity_batch_only CHECK(sample_identity_id IS NULL OR kind='sample');
CREATE INDEX ix_sample_identity_batches ON plm_partner_record(sample_identity_id,record_id) WHERE kind='sample' AND deleted_flag=0;
