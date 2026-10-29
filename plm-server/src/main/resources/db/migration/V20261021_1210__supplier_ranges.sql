-- Supplier-owned ranges reuse existing supplier, phone, attachment and audit identities.
-- No supplier (including XD), phone, company colour or business stock is created here.
ALTER TABLE plm_supplier_profile ADD COLUMN short_code varchar(32) NOT NULL DEFAULT '';
CREATE UNIQUE INDEX uk_supplier_short_code ON plm_supplier_profile(lower(short_code)) WHERE short_code<>'';

CREATE TABLE plm_supplier_color (
 color_id bigserial PRIMARY KEY, supplier_code varchar(64) NOT NULL REFERENCES plm_supplier_profile(supplier_code),
 code varchar(64) NOT NULL UNIQUE, name varchar(255) NOT NULL, supplier_color_code varchar(128) NOT NULL DEFAULT '',
 style_key varchar(255) NOT NULL DEFAULT '', config_key varchar(255) NOT NULL DEFAULT '',
 status varchar(16) NOT NULL DEFAULT 'active' CHECK(status IN ('active','inactive')),
 version bigint NOT NULL DEFAULT 1, data jsonb NOT NULL DEFAULT '{}',
 created_at timestamp NOT NULL DEFAULT now(), updated_at timestamp NOT NULL DEFAULT now(),
 created_by varchar(128) NOT NULL, updated_by varchar(128) NOT NULL
);
CREATE INDEX ix_supplier_color_scope ON plm_supplier_color(supplier_code,style_key,config_key,color_id);
CREATE TABLE plm_supplier_color_version (
 color_id bigint NOT NULL REFERENCES plm_supplier_color(color_id), version bigint NOT NULL,
 snapshot jsonb NOT NULL, created_at timestamp NOT NULL DEFAULT now(), created_by varchar(128) NOT NULL,
 PRIMARY KEY(color_id,version)
);
CREATE TABLE plm_supplier_range (
 range_id bigserial PRIMARY KEY, supplier_code varchar(64) NOT NULL REFERENCES plm_supplier_profile(supplier_code),
 code varchar(64) NOT NULL UNIQUE, type varchar(16) NOT NULL CHECK(type IN ('model','color')),
 name varchar(255) NOT NULL, style_key varchar(255) NOT NULL DEFAULT '', config_key varchar(255) NOT NULL DEFAULT '',
 status varchar(16) NOT NULL DEFAULT 'draft' CHECK(status IN ('draft','active','inactive')),
 version bigint NOT NULL DEFAULT 1, data jsonb NOT NULL DEFAULT '{}',
 created_at timestamp NOT NULL DEFAULT now(), updated_at timestamp NOT NULL DEFAULT now(),
 created_by varchar(128) NOT NULL, updated_by varchar(128) NOT NULL
);
CREATE INDEX ix_supplier_range_scope ON plm_supplier_range(supplier_code,type,style_key,config_key,range_id);
CREATE TABLE plm_supplier_range_version (
 range_id bigint NOT NULL REFERENCES plm_supplier_range(range_id), version bigint NOT NULL,
 snapshot jsonb NOT NULL, created_at timestamp NOT NULL DEFAULT now(), created_by varchar(128) NOT NULL,
 PRIMARY KEY(range_id,version)
);
CREATE TABLE plm_supplier_range_request (
 supplier_code varchar(64) NOT NULL REFERENCES plm_supplier_profile(supplier_code), actor_id bigint NOT NULL REFERENCES sys_user(user_id),
 request_id varchar(80) NOT NULL, action varchar(32) NOT NULL, payload text NOT NULL, result jsonb NOT NULL,
 created_at timestamp NOT NULL DEFAULT now(), PRIMARY KEY(supplier_code,actor_id,request_id)
);
