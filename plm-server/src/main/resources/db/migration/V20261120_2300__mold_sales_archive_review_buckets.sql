-- Keep order-review row counts independent of the effective sales quantities.
create table plm_mold_sales_archive_review_bucket (
    style_code text,
    phone_code text,
    line_count bigint not null,
    order_days date[] not null,
    day_lines bigint[] not null
);

create function plm_refresh_mold_sales_archive_review_bucket() returns void
language plpgsql
security invoker
set search_path from current
as $$
begin
    lock table plm_mold_sales_archive_review_bucket in share row exclusive mode;
    delete from plm_mold_sales_archive_review_bucket;

    insert into plm_mold_sales_archive_review_bucket (style_code, phone_code, line_count, order_days, day_lines)
    with daily as (
        select style_code, phone_code, business_date, count(*) as line_count
        from plm_mold_sales_archive_line
        where record_kind = 'detail'
          and business_date < date '2025-01-01'
          and eligibility not in ('eligible', 'not_finished_stage')
        group by style_code, phone_code, business_date
    )
    select style_code, phone_code, sum(line_count)::bigint,
           array_agg(business_date order by business_date),
           array_agg(line_count order by business_date)
    from daily
    group by style_code, phone_code;
end;
$$;

create function plm_refresh_mold_sales_archive_review_on_publish() returns trigger
language plpgsql
security invoker
set search_path from current
as $$
begin
    perform plm_refresh_mold_sales_archive_review_bucket();
    return null;
end;
$$;

revoke execute on function plm_refresh_mold_sales_archive_review_bucket() from public;
revoke execute on function plm_refresh_mold_sales_archive_review_on_publish() from public;

-- The archive publisher inserts its order rows after the header in the same transaction.
create constraint trigger plm_mold_sales_archive_review_after_import
after insert on plm_mold_sales_archive_batch
deferrable initially deferred for each row
execute function plm_refresh_mold_sales_archive_review_on_publish();

select plm_refresh_mold_sales_archive_review_bucket();

analyze plm_mold_sales_archive_review_bucket;
