-- Independent supplier profiles and commercial records. Existing inventory and research facts stay intact.
CREATE TABLE plm_supplier_profile (
 supplier_id bigserial UNIQUE NOT NULL, supplier_code varchar(64) PRIMARY KEY,
 supplier_name varchar(255) NOT NULL, supplier_type varchar(16) NOT NULL DEFAULT 'unknown'
 CHECK(supplier_type IN ('company','person','unknown')),
 status varchar(16) NOT NULL DEFAULT 'draft' CHECK(status IN ('draft','active','inactive')),
 version bigint NOT NULL DEFAULT 0, data jsonb NOT NULL DEFAULT '{}',
 needs_review boolean NOT NULL DEFAULT false, created_at timestamp NOT NULL DEFAULT now(),
 created_by varchar(128) NOT NULL DEFAULT 'migration', updated_at timestamp NOT NULL DEFAULT now(),
 updated_by varchar(128) NOT NULL DEFAULT 'migration', deleted_flag smallint NOT NULL DEFAULT 0
);
CREATE FUNCTION plm_supplier_legacy_code(n text) RETURNS text LANGUAGE plpgsql IMMUTABLE AS $$
DECLARE h bigint:=0; cp integer; v integer; i integer;
BEGIN
 FOR i IN 1..char_length(n) LOOP
  cp:=ascii(substr(n,i,1));
  IF cp>65535 THEN
   v:=cp-65536; h:=mod(h*31+55296+(v/1024),4294967296);
   h:=mod(h*31+56320+mod(v,1024),4294967296);
  ELSE h:=mod(h*31+cp,4294967296); END IF;
 END LOOP;
 IF h>=2147483648 THEN h:=h-4294967296; END IF;
 RETURN 'SUP-'||lpad(mod(mod(h,100000)+100000,100000)::text,5,'0');
END $$;
INSERT INTO plm_supplier_profile(supplier_code,supplier_name,status,data,needs_review)
SELECT code,min(name),CASE WHEN bool_or(legacy_status IN ('available','active')) THEN 'active' WHEN bool_and(legacy_status IN ('closed','inactive')) THEN 'inactive' ELSE 'draft' END,jsonb_build_object('legacyNames',jsonb_agg(distinct name),
 'shortName',max(short_name),'contactPerson',max(person),'contactPhone',max(phone),
 'contactEmail',max(email),'region',max(region)),count(distinct name)>1
FROM (
 SELECT coalesce(nullif(trim(si.supplier_code),''),plm_supplier_legacy_code(coalesce(nullif(trim(i.supplier_name),''),nullif(trim(si.supplier_name),'')))) code,
 coalesce(nullif(trim(i.supplier_name),''),nullif(trim(si.supplier_name),'')) name,
 coalesce(si.status,i.status) legacy_status,si.supplier_short_name short_name,si.supplier_contact_person person,si.supplier_contact_phone phone,
 si.supplier_contact_email email,si.supplier_region region
 FROM plm_inventory i LEFT JOIN plm_inventory_supplier_item si ON si.inventory_id=i.inventory_id AND si.deleted_flag=0
 WHERE i.deleted_flag=0 AND coalesce(nullif(trim(i.supplier_name),''),nullif(trim(si.supplier_name),'')) IS NOT NULL
) legacy GROUP BY code;
DO $$ DECLARE n text; t text; c text; matches integer;
BEGIN
 FOR n,t,c IN SELECT * FROM (VALUES ('喜中喜','company','SUP-XX'),('C&T','company','SUP-CT'),('Tim','person','SUP-TIM'),('Toby','person','SUP-TOBY')) x LOOP
  SELECT count(*) INTO matches FROM plm_supplier_profile WHERE lower(trim(supplier_name))=lower(n);
  IF matches=1 THEN UPDATE plm_supplier_profile SET supplier_type=t WHERE lower(trim(supplier_name))=lower(n);
  ELSIF matches=0 THEN INSERT INTO plm_supplier_profile(supplier_code,supplier_name,supplier_type) VALUES(c,n,t);
  ELSE UPDATE plm_supplier_profile SET needs_review=true WHERE lower(trim(supplier_name))=lower(n);
  END IF;
 END LOOP;
END $$;
ALTER TABLE plm_customer ADD COLUMN version bigint NOT NULL DEFAULT 0;
ALTER TABLE plm_customer ADD COLUMN profile_data jsonb NOT NULL DEFAULT '{}';
CREATE TABLE plm_partner_record (
 record_id bigserial PRIMARY KEY, kind varchar(24) NOT NULL CHECK(kind IN ('product','sale','sample')),
 code varchar(64) UNIQUE NOT NULL, name varchar(255) NOT NULL, status varchar(32) NOT NULL DEFAULT 'draft',
 version bigint NOT NULL DEFAULT 0, data jsonb NOT NULL DEFAULT '{}',
 site varchar(128) NOT NULL DEFAULT '', external_id varchar(128) NOT NULL DEFAULT '',
 customer_id bigint REFERENCES plm_customer(customer_id),
 source_project_id bigint REFERENCES plm_product(product_id),
 supplier_code varchar(64) REFERENCES plm_supplier_profile(supplier_code),
 created_at timestamp NOT NULL DEFAULT now(), updated_at timestamp NOT NULL DEFAULT now(),
 created_by varchar(128) NOT NULL, updated_by varchar(128) NOT NULL,
 owner_user_id bigint NOT NULL REFERENCES sys_user(user_id), deleted_flag smallint NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX uk_partner_external ON plm_partner_record(kind,site,external_id)
 WHERE site<>'' AND external_id<>'' AND deleted_flag=0;
CREATE INDEX ix_partner_page ON plm_partner_record(kind,updated_at DESC) WHERE deleted_flag=0;
CREATE TABLE plm_partner_spec (
 spec_id bigserial PRIMARY KEY, record_id bigint NOT NULL REFERENCES plm_partner_record(record_id),
 code varchar(64) UNIQUE NOT NULL, name varchar(255) NOT NULL, site varchar(128) NOT NULL DEFAULT '',
 website_sku varchar(128) NOT NULL DEFAULT '', data jsonb NOT NULL DEFAULT '{}', deleted_flag smallint NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX uk_partner_website_sku ON plm_partner_spec(site,website_sku)
 WHERE site<>'' AND website_sku<>'' AND deleted_flag=0;
CREATE TABLE plm_partner_event (
 event_id bigserial PRIMARY KEY, record_id bigint REFERENCES plm_partner_record(record_id),
 customer_id bigint REFERENCES plm_customer(customer_id),
 supplier_code varchar(64) REFERENCES plm_supplier_profile(supplier_code),
 action varchar(64) NOT NULL, data jsonb NOT NULL, request_id varchar(80),
 created_at timestamp NOT NULL DEFAULT now(), created_by varchar(128) NOT NULL, actor_id bigint NOT NULL REFERENCES sys_user(user_id)
);
CREATE UNIQUE INDEX uk_partner_event_request ON plm_partner_event(record_id,request_id) WHERE request_id IS NOT NULL;
CREATE INDEX ix_partner_event_record ON plm_partner_event(record_id,event_id);
CREATE TABLE plm_partner_revision (
 revision_id bigserial PRIMARY KEY, record_id bigint NOT NULL REFERENCES plm_partner_record(record_id),
 revision bigint NOT NULL, data jsonb NOT NULL, created_at timestamp NOT NULL DEFAULT now(),
 created_by varchar(128) NOT NULL, UNIQUE(record_id,revision)
);
CREATE TABLE plm_partner_file (
 record_id bigint NOT NULL REFERENCES plm_partner_record(record_id),
 attachment_id bigint NOT NULL REFERENCES plm_attachment(attachment_id),
 purpose varchar(32) NOT NULL DEFAULT 'material', created_at timestamp NOT NULL DEFAULT now(),
 PRIMARY KEY(record_id,attachment_id)
);
CREATE TABLE plm_partner_import (
 token varchar(80) PRIMARY KEY, kind varchar(24) NOT NULL, owner_user_id bigint NOT NULL REFERENCES sys_user(user_id),
 data jsonb NOT NULL, result jsonb, created_at timestamp NOT NULL DEFAULT now()
);
ALTER TABLE plm_pilot_scope DROP CONSTRAINT plm_pilot_scope_entity_type_check;
ALTER TABLE plm_pilot_scope ADD CONSTRAINT plm_pilot_scope_entity_type_check
 CHECK(entity_type IN ('item','mold','product','file','partner','customer','supplier_profile'));
ALTER TABLE plm_document_reference DROP CONSTRAINT plm_document_reference_object_type_check;
ALTER TABLE plm_document_reference ADD CONSTRAINT plm_document_reference_object_type_check
 CHECK(object_type IN ('product','sku','item','mold_code','source_code','mold_set','partner'));
-- Existing domestic/finance accounts gain only the business actions appropriate to their role.
UPDATE plm_pilot_account SET permissions=permissions||'["partner:view","partner:write","samples:write","customer:view","customer:create","customer:edit"]'::jsonb
 WHERE role_code='domestic';
UPDATE plm_pilot_account SET permissions=permissions||'["partner:view","customer:view","supplier:view"]'::jsonb
 WHERE role_code='finance';

CREATE TABLE plm_partner_create_request (actor_id bigint NOT NULL REFERENCES sys_user(user_id),kind varchar(32) NOT NULL,request_id varchar(80) NOT NULL,object_id bigint NOT NULL,created_at timestamp NOT NULL DEFAULT now(),PRIMARY KEY(actor_id,kind,request_id));
