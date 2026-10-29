package com.yuewei.plm.module.project;

import com.yuewei.plm.repository.entity.Product;
import java.time.LocalDateTime;
import org.springframework.jdbc.core.JdbcTemplate;

/** Registration is part of formal creation/conversion, never a catalog-maintenance action. */
public final class ProjectRegistration {
    private ProjectRegistration() {}
    /** Registered projects may carry aggregate production colors; imported color SKUs are never projects. */
    public static String modelCandidateSql(String alias) {
        return "("+alias+".product_type='model_variant' and ("+alias+".project_kind='model_extension' or ("+alias+".project_kind is null and nullif(btrim("+alias+".color),'') is null and nullif(btrim("+alias+".finished_product_code),'') is null)))";
    }
    public static void initialize(Product product,LocalDateTime now) {
        String kind=switch(product.getProductType()){case "product_line"->"product_research";case "model_variant"->"model_extension";default->null;};
        if(kind!=null&&product.getProjectKind()==null){product.setProjectKind(kind);product.setProjectRegisteredAt(now);}
    }
    public static void registerConverted(JdbcTemplate jdbc,long projectId) {
        jdbc.update("update plm_product p set project_kind='model_extension',project_registered_at=now() where product_id=? and project_kind is null and deleted_flag=0 and "+modelCandidateSql("p"),projectId);
    }
}
