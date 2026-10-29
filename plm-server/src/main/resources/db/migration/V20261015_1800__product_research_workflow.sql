-- Additive migration: existing research facts and original project workflows remain intact.
alter table plm_product_development_round drop constraint plm_product_development_round_kind_check;
alter table plm_product_development_round add constraint plm_product_development_round_kind_check check (kind in ('sample','test','pilot'));
alter table plm_product_development_round alter column record_date drop not null;
alter table plm_product_development_round add column details_json jsonb not null default '{}'::jsonb;
alter table plm_product_development_round add column request_id varchar(80);
create unique index uq_research_round_request on plm_product_development_round(product_id,request_id) where request_id is not null;
alter table plm_product_development_release drop constraint plm_product_development_release_product_id_key;
alter table plm_product_development_release add column package_no integer;
update plm_product_development_release set package_no=1;
alter table plm_product_development_release alter column package_no set not null;
alter table plm_product_development_release alter column package_no set default 1;
create unique index uq_research_release_package on plm_product_development_release(product_id,package_no);
alter table plm_product_development_release add column basis_json jsonb not null default '{}'::jsonb;
-- Draft state is replaceable; confirmed business facts below are append-only.
create table plm_product_development_state (
 product_id bigint primary key references plm_product(product_id),
 version bigint not null default 0,
 data jsonb not null default '{}'::jsonb,
 updated_at timestamp not null default now()
);
create table plm_product_development_event (
 event_id bigserial primary key,
 product_id bigint not null references plm_product(product_id),
 kind varchar(48) not null,
 data jsonb not null,
 request_id varchar(80),
 created_at timestamp not null default now(),
 created_by varchar(128) not null,
 unique(product_id,request_id)
);
create index idx_research_event_project on plm_product_development_event(product_id,event_id desc);

alter table plm_product_development_round drop constraint plm_product_development_round_product_id_round_no_key;
alter table plm_product_development_round add constraint uq_research_round_kind unique(product_id,kind,round_no);
