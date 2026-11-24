-- Preserve archived source identities. Reports still apply current SKU eligibility and object scope.
create table plm_mold_sales_archive_report_bucket (
    style_code text,
    phone_code text,
    item_code text,
    original_product text not null,
    sale_name text not null,
    sealed boolean not null,
    classification text not null,
    display_name text not null,
    quantity numeric,
    line_count bigint not null,
    first_order_date date not null,
    order_days date[] not null,
    day_quantities numeric[] not null,
    day_lines bigint[] not null
);

create function plm_refresh_mold_sales_archive_report_bucket() returns void
language plpgsql
security invoker
set search_path from current
as $$
begin
    -- Serialize publishers while ordinary report transactions keep reading the committed version.
    lock table plm_mold_sales_archive_report_bucket in share row exclusive mode;
    delete from plm_mold_sales_archive_report_bucket;

    insert into plm_mold_sales_archive_report_bucket (
        style_code, phone_code, item_code, original_product, sale_name, sealed, classification, display_name,
        quantity, line_count, first_order_date, order_days, day_quantities, day_lines
    )
    with facts as (
        select a.style_code::text, a.phone_code::text, a.item_code, a.business_date, a.quantity,
               coalesce(a.source_data->'raw'->>'product', '') as original_product,
               coalesce(a.source_data->>'name', '') as sale_name,
               h.source_id is not null as sealed,
               coalesce(g.classification, '') as classification,
               coalesce(h.display_name, '') as display_name
        from plm_mold_sales_archive_line a
        left join plm_mold_sales_history_line h on h.source_id = a.source_id
        left join plm_mold_sales_history_group g
            on g.batch_id = h.batch_id and g.style_code = h.style_code and g.phone_code = h.phone_code
        where a.record_kind = 'detail'
          and a.business_date < date '2025-01-01'
          and a.eligibility in ('eligible', 'not_finished_stage')
    ), daily as (
        select style_code, phone_code, item_code, original_product, sale_name, sealed, classification, display_name,
               business_date, sum(quantity) as quantity, count(*) as line_count
        from facts
        group by style_code, phone_code, item_code, original_product, sale_name, sealed, classification, display_name,
                 business_date
    )
    select style_code, phone_code, item_code, original_product, sale_name, sealed, classification, display_name,
           sum(quantity), sum(line_count)::bigint, min(business_date),
           array_agg(business_date order by business_date),
           array_agg(quantity order by business_date),
           array_agg(line_count order by business_date)
    from daily
    group by style_code, phone_code, item_code, original_product, sale_name, sealed, classification, display_name;
end;
$$;

create function plm_refresh_mold_sales_archive_report_on_publish() returns trigger
language plpgsql
security invoker
set search_path from current
as $$
begin
    perform plm_refresh_mold_sales_archive_report_bucket();
    return null;
end;
$$;

revoke execute on function plm_refresh_mold_sales_archive_report_bucket() from public;
revoke execute on function plm_refresh_mold_sales_archive_report_on_publish() from public;

-- Each supported publication inserts the batch header and all its children before committing.
create constraint trigger plm_mold_sales_archive_report_after_import
after insert on plm_mold_sales_archive_batch
deferrable initially deferred for each row
execute function plm_refresh_mold_sales_archive_report_on_publish();

create constraint trigger plm_mold_sales_archive_report_after_history
after insert on plm_mold_sales_history_batch
deferrable initially deferred for each row
execute function plm_refresh_mold_sales_archive_report_on_publish();

select plm_refresh_mold_sales_archive_report_bucket();

create index ix_mold_sales_archive_report_phone
    on plm_mold_sales_archive_report_bucket (style_code, phone_code);

-- Legacy unconfirmed archive rows still use the live eligibility checks.
create index ix_mold_sales_archive_legacy_unconfirmed
    on plm_mold_sales_archive_line (source_id)
    where record_kind = 'detail'
      and business_date < date '2025-01-01'
      and eligibility::text = 'unconfirmed_order';

analyze plm_mold_sales_archive_report_bucket;
