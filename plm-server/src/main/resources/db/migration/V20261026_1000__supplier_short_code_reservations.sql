-- Current-profile uniqueness cannot remember a short code after it is changed or cleared.
-- Reserve each explicitly recorded spelling against the existing supplier identity permanently.
-- No supplier is created, inferred from its name, or assigned a new short code by this migration.
ALTER TABLE plm_supplier_profile ALTER COLUMN short_code TYPE text;

CREATE TABLE plm_supplier_short_code_reservation (
 short_code text PRIMARY KEY,
 supplier_code varchar(64) NOT NULL REFERENCES plm_supplier_profile(supplier_code),
 original_short_code text NOT NULL,
 created_at timestamp NOT NULL DEFAULT now(),
 created_by varchar(128) NOT NULL,
 CHECK (short_code<>'' AND short_code=upper(btrim(short_code)))
);
CREATE INDEX ix_supplier_short_code_reservation_owner ON plm_supplier_short_code_reservation(supplier_code);

-- Preserve current values and trustworthy prior metadata results, including inactive/deleted owners.
-- The raw spelling is retained; legacy punctuation or Chinese is not silently rewritten.
CREATE TEMP TABLE supplier_short_code_history_seed ON COMMIT DROP AS
 SELECT p.supplier_code,p.short_code AS original_short_code,0 AS source_order
 FROM plm_supplier_profile p WHERE btrim(p.short_code)<>''
 UNION ALL
 SELECT e.supplier_code,v->>'shortCode',1
 FROM plm_partner_event e
 JOIN plm_supplier_profile p ON p.supplier_code=e.supplier_code
 CROSS JOIN LATERAL jsonb_array_elements(CASE WHEN jsonb_typeof(e.data->'before')='array' THEN e.data->'before' ELSE '[]'::jsonb END) v
 WHERE e.action='supplier_metadata' AND jsonb_typeof(v->'shortCode')='string' AND btrim(v->>'shortCode')<>''
 UNION ALL
 SELECT e.supplier_code,e.data->'after'->>'shortCode',2
 FROM plm_partner_event e JOIN plm_supplier_profile p ON p.supplier_code=e.supplier_code
 WHERE e.action='supplier_metadata' AND jsonb_typeof(e.data->'after'->'shortCode')='string' AND btrim(e.data->'after'->>'shortCode')<>''
 UNION ALL
 SELECT r.supplier_code,r.result->>'shortCode',3
 FROM plm_supplier_range_request r JOIN plm_supplier_profile p ON p.supplier_code=r.supplier_code
 WHERE r.action='metadata' AND jsonb_typeof(r.result->'shortCode')='string' AND btrim(r.result->>'shortCode')<>'';

DO $$
DECLARE conflicts text;
BEGIN
 SELECT string_agg(short_code||' ['||owners||']','; ' ORDER BY short_code) INTO conflicts
 FROM (
  SELECT upper(btrim(original_short_code)) AS short_code,string_agg(DISTINCT supplier_code,', ' ORDER BY supplier_code) AS owners
  FROM supplier_short_code_history_seed GROUP BY upper(btrim(original_short_code)) HAVING count(DISTINCT supplier_code)>1
 ) c;
 IF conflicts IS NOT NULL THEN
  RAISE EXCEPTION '供应商简码存在历史归属冲突，未修改任何原码，请先人工核对: %',conflicts;
 END IF;
END $$;

INSERT INTO plm_supplier_short_code_reservation(short_code,supplier_code,original_short_code,created_by)
 SELECT DISTINCT ON (upper(btrim(original_short_code))) upper(btrim(original_short_code)),supplier_code,original_short_code,'migration:preserve-existing'
 FROM supplier_short_code_history_seed ORDER BY upper(btrim(original_short_code)),source_order,original_short_code;

CREATE FUNCTION plm_validate_supplier_short_code() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE normalized text;
BEGIN
 normalized:=upper(btrim(coalesce(NEW.short_code,'')));
 IF TG_OP='UPDATE' THEN
  IF normalized=upper(btrim(OLD.short_code)) THEN
   NEW.short_code:=OLD.short_code;
   RETURN NEW;
  END IF;
  IF upper(btrim(OLD.short_code))='XD' THEN
   RAISE EXCEPTION 'XD为已确认的供应商简码，不能清空或改给其他供应商' USING ERRCODE='23514';
  END IF;
 END IF;
 IF normalized<>'' AND normalized!~'^[A-Z0-9][A-Z0-9-]*$' THEN
  RAISE EXCEPTION '供应商简码仅允许英文字母、数字和半角横线，不能包含中文或下划线' USING ERRCODE='23514';
 END IF;
 NEW.short_code:=normalized;
 RETURN NEW;
END $$;
CREATE TRIGGER trg_validate_supplier_short_code BEFORE INSERT OR UPDATE OF short_code ON plm_supplier_profile
 FOR EACH ROW EXECUTE FUNCTION plm_validate_supplier_short_code();

CREATE FUNCTION plm_reserve_supplier_short_code() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE normalized text; owner_code text;
BEGIN
 normalized:=upper(btrim(NEW.short_code));
 IF normalized<>'' THEN
  INSERT INTO plm_supplier_short_code_reservation(short_code,supplier_code,original_short_code,created_by)
   VALUES(normalized,NEW.supplier_code,normalized,coalesce(NEW.updated_by,'supplier-profile')) ON CONFLICT DO NOTHING;
  SELECT supplier_code INTO owner_code FROM plm_supplier_short_code_reservation WHERE short_code=normalized;
  IF owner_code IS DISTINCT FROM NEW.supplier_code THEN
   RAISE EXCEPTION '供应商简码已被使用或作为历史简码保留，请另选简码' USING ERRCODE='23505',CONSTRAINT='plm_supplier_short_code_reservation_pkey';
  END IF;
 END IF;
 RETURN NEW;
END $$;

-- AFTER permits a supplier inserted with an explicit code to satisfy the existing supplier FK.
CREATE TRIGGER trg_supplier_short_code_reservation AFTER INSERT OR UPDATE OF short_code ON plm_supplier_profile
 FOR EACH ROW EXECUTE FUNCTION plm_reserve_supplier_short_code();

CREATE FUNCTION plm_keep_supplier_short_code_reservation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 RAISE EXCEPTION '已确认供应商简码必须保留历史归属，不可删除或改给其他供应商' USING ERRCODE='23514';
END $$;
CREATE TRIGGER trg_keep_supplier_short_code_reservation BEFORE UPDATE OR DELETE ON plm_supplier_short_code_reservation
 FOR EACH ROW EXECUTE FUNCTION plm_keep_supplier_short_code_reservation();
