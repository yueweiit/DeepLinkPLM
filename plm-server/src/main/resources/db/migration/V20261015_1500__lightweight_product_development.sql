-- Existing products retain their original workflow. Only formal new product creation opts in.
alter table plm_product
    add column if not exists development_mode varchar(32),
    add column if not exists development_requirements text,
    add column if not exists target_cost numeric(14,4);

create table plm_product_development_round (
    round_id bigserial primary key,
    product_id bigint not null references plm_product(product_id),
    round_no integer not null,
    kind varchar(16) not null check (kind in ('sample','pilot')),
    record_date date not null,
    title varchar(255) not null,
    result varchar(16) not null check (result in ('pending','passed','failed')),
    issues text not null default '',
    changes text not null default '',
    retest_result text not null default '',
    references_json jsonb not null default '[]'::jsonb,
    status varchar(16) not null default 'draft' check (status in ('draft','confirmed')),
    confirmed_at timestamp,
    confirmed_by varchar(128),
    created_at timestamp not null default now(),
    created_by varchar(128) not null,
    updated_at timestamp not null default now(),
    updated_by varchar(128) not null,
    deleted_flag integer not null default 0,
    unique(product_id, round_no)
);
create index idx_product_development_round on plm_product_development_round(product_id, deleted_flag, round_no);

create table plm_product_development_release (
    release_id bigserial primary key,
    product_id bigint not null unique references plm_product(product_id),
    version_no varchar(32) not null,
    references_json jsonb not null,
    product_snapshot jsonb not null,
    rounds_snapshot jsonb not null,
    missing_items jsonb not null,
    exception_reason text,
    supplement_due_date date,
    responsible_user_id bigint,
    published_at timestamp not null default now(),
    published_by varchar(128) not null
);
