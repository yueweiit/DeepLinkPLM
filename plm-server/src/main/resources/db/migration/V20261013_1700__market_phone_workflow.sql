-- The source projection is a disposable, read-only crawl cache, not another phone master.
-- Standard phone IDs and confirmed mold compatibility remain the PLM authorities.
alter table plm_phone_brand add column if not exists aliases text[] not null default '{}';
alter table plm_phone_brand add column if not exists prefixes jsonb not null default '[]'::jsonb;
update plm_phone_brand set prefixes=case lower(btrim(name))
  when 'apple' then '[{"prefix":"IP","replacement":"iPhone"},{"prefix":"iPhone","replacement":"iPhone"}]'::jsonb
  when 'samsung' then '[{"prefix":"Galaxy","replacement":""}]'::jsonb
  else prefixes end;
update plm_phone_brand set aliases=case lower(btrim(name))
  when 'apple' then array['IP','iPhone','苹果']
  when 'xiaomi' then array['XM','小米']
  when 'huawei' then array['HW','华为']
  when 'samsung' then array['SM','三星']
  else aliases end;
create table plm_market_watch_source_projection (
  source_kind varchar(16) not null check(source_kind in ('phone','candidate')),
  source_id bigint not null,
  source_snapshot jsonb not null,
  phone_model_id bigint references plm_phone_model_member(id),
  match_state varchar(16) not null default 'unmatched' check(match_state in ('matched','unmatched','ambiguous')),
  match_origin varchar(16) not null default 'automatic' check(match_origin in ('automatic','manual')),
  candidate_ids bigint[] not null default '{}',
  active boolean not null default true,
  observed_at timestamptz not null default now(),
  mapped_by varchar(128), mapped_at timestamptz,
  primary key(source_kind,source_id)
);
create index ix_market_watch_phone_projection on plm_market_watch_source_projection(phone_model_id) where active;
create table plm_market_watch_projection_sync (
  id integer primary key check(id=1),
  last_attempt_at timestamptz,last_success_at timestamptz,last_error varchar(512)
);
insert into plm_market_watch_projection_sync(id) values(1);
-- Backfill only an exact original code AND a unique exact name/alias; groups are never split.
with candidates as (
  select p.product_id,min(m.id) phone_id
  from plm_product p join plm_phone_model_member m
    on m.code_item_id in (select code_item_id from plm_code_item c where c.code_type='phone_model'
      and c.code_value=p.phone_model_code and c.deleted_flag=0)
  where p.deleted_flag=0 and p.product_type='model_variant' and p.phone_model_member_id is null
    and (lower(btrim(p.model))=lower(btrim(m.model_name)) or exists(
      select 1 from unnest(m.aliases) a where lower(btrim(a))=lower(btrim(p.model))))
  group by p.product_id having count(distinct m.id)=1
)
update plm_product p set phone_model_member_id=c.phone_id from candidates c where p.product_id=c.product_id;
