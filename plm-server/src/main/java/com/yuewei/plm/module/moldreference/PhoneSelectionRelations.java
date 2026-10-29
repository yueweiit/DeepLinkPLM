package com.yuewei.plm.module.moldreference;

/** Exact, existing business links for read-only selectors. No name matching or identity creation. */
public final class PhoneSelectionRelations {
    private PhoneSelectionRelations() {}
    public static String members() {
        return """
            product_members as (
              select p.product_id,p.phone_model_member_id phone_id
                from plm_product p where p.deleted_flag=0 and p.phone_model_member_id is not null
              union
              select p.product_id,l.phone_model_id from plm_product p
                join plm_product_phone_model l on l.product_id=p.product_id
                where p.deleted_flag=0 and %s
              union
              select p.product_id,parent.phone_model_member_id from plm_product p
                join plm_product parent on parent.product_id=p.parent_product_id and parent.deleted_flag=0
                where p.deleted_flag=0 and p.product_type='sku' and %s
            ), item_members as (
              select item_id,phone_model_id phone_id from plm_collaboration_phone_member
              union select item_id,phone_model_id from plm_collaboration_phone_set_compatibility
              union select item_id,phone_model_id from plm_mold_application
                where deleted_flag=0 and item_id is not null and phone_model_id is not null
            ), set_members as (
              select mold_set_id,phone_model_id phone_id from plm_collaboration_phone_set_compatibility
              union select mold_set_id,phone_model_id from plm_mold_application
                where deleted_flag=0 and mold_set_id is not null and phone_model_id is not null
            )
            """.formatted(PhoneProductEvidence.memberRelationSql("p","l.phone_model_id"),
                PhoneProductEvidence.skuParentIdentitySql("p","parent"));
    }
    public static String jsonIds(String expression) {
        return "jsonb_array_elements_text(case when jsonb_typeof("+expression+")='array' then "+expression+" else '[]'::jsonb end)";
    }
    public static String safeId(String expression) {
        return "case when "+expression+" ~ '^[1-9][0-9]{0,17}$' then "+expression+"::bigint end";
    }
}
