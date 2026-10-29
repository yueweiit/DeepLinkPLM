-- Catalogue identity is not a Product, SKU or physical sample. Never rewrite legacy source JSON.
CREATE TABLE plm_partner_catalog_style (
 style_id bigserial PRIMARY KEY,
 code text GENERATED ALWAYS AS ('CP-' || lpad(style_id::text,greatest(6,length(style_id::text)),'0')) STORED UNIQUE,
 supplier_code varchar(64) NOT NULL REFERENCES plm_supplier_profile(supplier_code),
 created_at timestamptz NOT NULL DEFAULT now(),
 created_by varchar(128) NOT NULL,
 UNIQUE(style_id,supplier_code)
);
CREATE TABLE plm_partner_catalog_style_source (
 source_kind varchar(16) NOT NULL CHECK(source_kind IN ('quote','selection')),
 record_id bigint NOT NULL REFERENCES plm_partner_record(record_id),
 row_id uuid NOT NULL,
 style_id bigint NOT NULL REFERENCES plm_partner_catalog_style(style_id),
 fields jsonb NOT NULL DEFAULT '{}',
 source_version bigint NOT NULL,
 source_row_version bigint NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),
 created_by varchar(128) NOT NULL,
 PRIMARY KEY(source_kind,record_id,row_id)
);
CREATE INDEX ix_catalog_style_source_style ON plm_partner_catalog_style_source(style_id,record_id);
-- A stable catalogue identity may have several source versions and selection references.
-- Sequences are never reset, deleted identities are retained, and imports cannot guess a link by name/photo.
