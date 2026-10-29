-- Phone IDs and snapshots use existing versioned JSON. Keep combined model labels complete.
ALTER TABLE plm_partner_spec ALTER COLUMN name TYPE text;
