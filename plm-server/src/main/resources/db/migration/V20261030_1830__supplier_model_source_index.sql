-- Preserve the legacy bigint for older application versions; source labels are not generated supplier numbers.
-- Existing rows, original JSON snapshots and evidence digests are not rewritten.
alter table plm_phone_model_intake_source
  add column source_entry_label varchar(256);
alter table plm_phone_model_intake_source
  alter column supplier_index drop not null;
alter table plm_phone_model_intake_source
  add constraint ck_phone_intake_source_entry_label
  check ((source_entry_label is not null and length(btrim(source_entry_label)) between 1 and 256)
      or (source_entry_label is null and supplier_index is not null));
create unique index ux_phone_intake_source_entry_label on plm_phone_model_intake_source
  (intake_id,source_kind,record_id,row_id,(coalesce(source_entry_label,supplier_index::text)),component_key,source_digest);
