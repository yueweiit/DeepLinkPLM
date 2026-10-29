create unique index uk_mold_application_revision on plm_mold_application(previous_application_id)
 where previous_application_id is not null and deleted_flag=0;
create table plm_mold_event_inbox (
 event_key varchar(64) primary key, instance_id varchar(128) not null,
 payload jsonb not null, attempts integer not null default 0,
 next_attempt_at timestamptz not null default now(), completed_at timestamptz,
 last_error varchar(256), received_at timestamptz not null default now()
);
create index ix_mold_event_pending on plm_mold_event_inbox(next_attempt_at) where completed_at is null;

-- Published historical codes preserve exact model aliases (e.g. OPPO / OP), never fuzzy-match.
insert into plm_mold_model_alias(source_database,source_id,phone_code,normalized_name,source_name,payload)
select 'dingtalk-formal',a.external_instance_id,min(matches.code[1]),
 lower(regexp_replace(btrim(a.model),'\s+',' ','g')),a.model,a.form_data
from plm_mold_application a
cross join lateral regexp_matches(upper(a.generated_codes),'M[A-Z]{2}[0-9]{2}([0-9]{4})','g') matches(code)
where a.process_code='PROC-89949932-19DE-48EB-A20B-2595CB0F804D'
 and a.external_instance_id is not null and length(a.model) between 1 and 255
 and a.generated_codes~*'^[[:space:]]*M[A-Z]{2}[0-9]{6}([[:space:]/，,、;；]+M[A-Z]{2}[0-9]{6})*[[:space:]]*$'
group by a.external_instance_id,a.model,a.form_data
having count(distinct matches.code[1])=1
on conflict(source_database,source_id) do nothing;

-- Disabled/deleted historical numbers must not be reused either.
update plm_mold_code_sequence set last_value=greatest(last_value,
 (select coalesce(max(phone_code::integer),0) from plm_mold_model_alias),
 (select coalesce(max((payload->>'code')::integer),0) from plm_mold_master_snapshot
  where source_table='model' and payload->>'code'~'^[0-9]{4}$'))
where sequence_key='phone_model';
