-- Resumable transfer and durable staging extend the existing attachment library.
CREATE TABLE plm_partner_upload_session (
 upload_id uuid PRIMARY KEY, record_id bigint NOT NULL REFERENCES plm_partner_record(record_id),
 owner_user_id bigint NOT NULL REFERENCES sys_user(user_id), request_id varchar(80) NOT NULL,
 file_name varchar(255) NOT NULL, file_size bigint NOT NULL CHECK(file_size>0),
 chunk_size integer NOT NULL, part_count bigint NOT NULL, purpose varchar(32) NOT NULL,
 status varchar(24) NOT NULL DEFAULT 'uploading', expected_sha256 varchar(64) NOT NULL DEFAULT '',
 attachment_id bigint REFERENCES plm_attachment(attachment_id), checksum varchar(64), error text,
 created_at timestamp NOT NULL DEFAULT now(), updated_at timestamp NOT NULL DEFAULT now(),
 UNIQUE(record_id,owner_user_id,request_id)
);
CREATE TABLE plm_partner_upload_part (
 upload_id uuid NOT NULL REFERENCES plm_partner_upload_session(upload_id), part_index bigint NOT NULL,
 byte_size bigint NOT NULL, checksum varchar(64) NOT NULL, created_at timestamp NOT NULL DEFAULT now(),
 PRIMARY KEY(upload_id,part_index)
);
CREATE TABLE plm_partner_file_job (
 job_id uuid PRIMARY KEY, record_id bigint NOT NULL REFERENCES plm_partner_record(record_id),
 owner_user_id bigint NOT NULL REFERENCES sys_user(user_id), kind varchar(16) NOT NULL CHECK(kind IN ('import','export')),
 request_id varchar(80) NOT NULL, status varchar(24) NOT NULL, base_version bigint NOT NULL,
 source_attachment_id bigint REFERENCES plm_attachment(attachment_id), parameters jsonb NOT NULL DEFAULT '{}',
 stats jsonb NOT NULL DEFAULT '{}', result jsonb, error text, lease_until timestamp,
 created_at timestamp NOT NULL DEFAULT now(), updated_at timestamp NOT NULL DEFAULT now(),
 UNIQUE(record_id,owner_user_id,kind,request_id)
);
CREATE INDEX ix_partner_file_job_queue ON plm_partner_file_job(status,lease_until,created_at);
CREATE TABLE plm_partner_file_job_row (
 job_id uuid NOT NULL REFERENCES plm_partner_file_job(job_id), row_id uuid NOT NULL,
 ordinal bigint NOT NULL, source_sheet text NOT NULL, source_row bigint NOT NULL,
 action varchar(16) NOT NULL, data jsonb NOT NULL, original jsonb NOT NULL DEFAULT '{}',
 messages jsonb NOT NULL DEFAULT '[]', version bigint NOT NULL DEFAULT 1,
 PRIMARY KEY(job_id,row_id), UNIQUE(job_id,ordinal)
);
CREATE TABLE plm_partner_file_job_image (
 job_id uuid NOT NULL REFERENCES plm_partner_file_job(job_id), image_id uuid NOT NULL,
 source_sheet text NOT NULL, source_row bigint, row_id uuid, image_name text NOT NULL,
 storage_key text NOT NULL, checksum varchar(64) NOT NULL, content_type varchar(80) NOT NULL,
 byte_size bigint NOT NULL, attachment_id bigint REFERENCES plm_attachment(attachment_id),
 PRIMARY KEY(job_id,image_id)
);
