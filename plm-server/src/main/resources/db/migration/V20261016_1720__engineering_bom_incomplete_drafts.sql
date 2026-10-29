-- New engineering drafts may lack a confirmed output basis. Never substitute 1.
-- Existing values/defaults and released historical versions remain unchanged.
alter table plm_product_bom alter column source_type type varchar(32);
alter table plm_product_bom alter column quantity drop not null;
