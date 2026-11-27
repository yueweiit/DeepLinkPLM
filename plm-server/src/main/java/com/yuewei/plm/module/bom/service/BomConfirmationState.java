package com.yuewei.plm.module.bom.service;

import org.springframework.jdbc.core.JdbcTemplate;

/** Shared by the confirmation preview and the transactional writer. */
public final class BomConfirmationState {
    private BomConfirmationState() {}

    public static void lock(JdbcTemplate jdbc, long bomId) {
        jdbc.queryForList("""
            select b.product_bom_id from plm_product_bom selected
              join plm_product_bom b on b.product_id=selected.product_id
               and b.product_bom_id in (selected.product_bom_id,selected.source_product_bom_id)
             where selected.product_bom_id=?
             order by case when b.product_bom_id=selected.source_product_bom_id then 0 else 1 end
             for update of b
            """,bomId);
    }

    // A derivative also replaces the imported baseline when no PLM revision has been selected yet.
    public static final String PREVIOUS_ROUTES = """
        select r.product_bom_route_id,r.article_codes
          from plm_product_bom c join plm_product_bom m on m.product_id=c.product_id
           and coalesce(m.source_product_bom_id,m.product_bom_id)=coalesce(c.source_product_bom_id,c.product_bom_id)
          join plm_product_bom_route r on r.product_bom_id=m.product_bom_id and r.status='active' and r.deleted_flag=0
         where c.product_bom_id=? and m.product_bom_id<>c.product_bom_id and m.deleted_flag=0 and (
           exists(select 1 from plm_product_bom_route_formal_selection f where f.product_bom_route_id=r.product_bom_route_id
                    and f.product_bom_id=m.product_bom_id and f.status='active' and f.deleted_flag=0)
           or (m.product_bom_id=c.source_product_bom_id and m.source_type='mes_baseline' and m.status in ('reviewing','frozen','released')
             and not exists(select 1 from plm_product_bom d join plm_product_bom_route_formal_selection f on f.product_bom_id=d.product_bom_id
                           where d.source_product_bom_id=m.product_bom_id and d.source_type='mes_derivative' and d.status='released'
                             and d.deleted_flag=0 and f.status='active' and f.deleted_flag=0)))
        """;

    public static String revision(JdbcTemplate jdbc, long bomId) {
        return jdbc.queryForObject("with previous_routes as ("+PREVIOUS_ROUTES+") "+"""
            select md5(jsonb_build_array(b.product_bom_id,b.version_no,b.status,
              (select jsonb_agg(jsonb_build_array(pr.product_bom_route_id,pr.article_codes) order by pr.product_bom_route_id) from previous_routes pr),
              (select jsonb_agg(jsonb_build_array(r.product_bom_route_id,r.process_id,r.article_codes) order by r.product_bom_route_id)
                 from plm_product_bom_route r where r.product_bom_id=b.product_bom_id and r.deleted_flag=0 and r.status='active'),
              (select jsonb_agg(jsonb_build_array(f.product_bom_id,f.product_bom_route_id) order by f.product_bom_id,f.product_bom_route_id)
                 from plm_product_bom member join plm_product_bom_route_formal_selection f on f.product_bom_id=member.product_bom_id
                where f.deleted_flag=0 and f.status='active' and member.deleted_flag=0 and member.product_id=b.product_id
                  and coalesce(member.source_product_bom_id,member.product_bom_id)=coalesce(b.source_product_bom_id,b.product_bom_id))
            )::text) from plm_product_bom b where b.product_bom_id=?
            """,String.class,bomId,bomId);
    }
}
