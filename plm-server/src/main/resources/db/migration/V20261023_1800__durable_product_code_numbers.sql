-- PRD project identities keep their original text; only future allocations change.
create sequence plm_product_code_seq as bigint minvalue 1 start with 1 no cycle;

-- numeric is intentional: source codes may have more digits than bigint permits.
-- Such text identities cannot be emitted by this bigint allocator and remain untouched.
with suffixes as (
    select substring(product_code from '^PRD-.*-([0-9]+)$')::numeric as suffix
    from plm_product
), bounds as (
    select max(case when suffix between 1 and 9223372036854775807 then suffix end)::bigint as highest
    from suffixes
)
select setval('plm_product_code_seq', coalesce(highest, 1), highest is not null) from bounds;
