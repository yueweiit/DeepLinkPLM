package com.yuewei.plm.module.project;

/** Shared relation projection for overview and exact destination filters. No name/code guessing. */
public final class ProjectBusinessLinks {
    private ProjectBusinessLinks() {}
    private static final String DEVELOPMENT_SETS="""
      select st.product_id as project_id,s.item_id,s.mold_set_id from plm_product_development_state st
      join plm_product p on p.product_id=st.product_id and p.deleted_flag=0 and p.development_mode='launch_v1'
      cross join lateral jsonb_array_elements(coalesce(st.data->'moldLinks','[]'::jsonb)) link
      join plm_collaboration_mold_set s on s.mold_set_id=(link->>'moldSetId')::bigint and s.deleted_flag=0
      """;
    public static final String SETS="""
      select a.project_id,a.item_id,a.mold_set_id from plm_mold_application a
      join plm_collaboration_mold_set s on s.mold_set_id=a.mold_set_id and s.item_id=a.item_id and s.deleted_flag=0
      where a.deleted_flag=0 and a.project_id is not null
      union
      select ip.project_id,cs.item_id,cs.mold_set_id from plm_collaboration_item_project ip
      join plm_product p on p.product_id=ip.project_id and p.deleted_flag=0
      join plm_collaboration_phone_set_compatibility cs on cs.item_id=ip.item_id and cs.phone_model_id=p.phone_model_member_id
      join plm_collaboration_mold_set s on s.mold_set_id=cs.mold_set_id and s.item_id=cs.item_id and s.deleted_flag=0
      """+" union "+DEVELOPMENT_SETS;
    public static final String ITEMS="""
      select project_id,item_id from plm_collaboration_item_project
      union select project_id,item_id from plm_mold_application where deleted_flag=0 and project_id is not null and item_id is not null
      """+" union select project_id,item_id from ("+DEVELOPMENT_SETS+") development_items";
    public static String setExists(String setId) {return "exists(select 1 from ("+SETS+") project_sets where project_sets.project_id=? and project_sets.mold_set_id="+identifier(setId)+")";}
    public static String itemExists(String itemId) {return "exists(select 1 from ("+ITEMS+") project_items where project_items.project_id=? and project_items.item_id="+identifier(itemId)+")";}
    private static String identifier(String value){if(!value.matches("[A-Za-z_][A-Za-z0-9_]*\\.[A-Za-z_][A-Za-z0-9_]*"))throw new IllegalArgumentException("Unsafe relation identifier");return value;}
}
