package com.yuewei.plm.module.project.development;

import com.yuewei.plm.module.pilot.PilotAccessService;
import com.yuewei.plm.module.productionmetadata.ProductionMetadataService;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import static com.yuewei.plm.module.project.development.DevelopmentRules.require;
import static com.yuewei.plm.module.project.development.DevelopmentWorkflowStore.text;

/** First sales admission uses the existing product identity and metadata writer. */
@Service @RequiredArgsConstructor
public class ResearchLaunchService {
    private final JdbcTemplate jdbc;
    private final PilotAccessService access;
    private final ProductionMetadataService catalog;

    public void enrich(long id,Map<String,Object> detail){
        var row=productIdentity(id);detail.put("salesStatus",row.get("salesStatus"));
        detail.put("productSpecificCode",row.get("productSpecificCode"));
        detail.put("canMaintainProductIdentity",canMaintainProductIdentity(text(row.get("productSpecificCode")),text(row.get("status")),text(row.get("lockStatus")),access));
        boolean first=DevelopmentWorkflowStore.map(DevelopmentWorkflowStore.map(detail.get("workflow")).get("launched")).isEmpty();
        var issues=first?identityIssues(row):List.<String>of();
        detail.put("canLaunch",canLaunch()&&issues.isEmpty());
        detail.put("launchIssues",issues.stream().map(message->Map.of("code","PRODUCT_IDENTITY","message",message,"target","product-identity")).toList());
    }
    public static boolean canMaintainProductIdentity(String code,String status,String lock,PilotAccessService access){
        return text(code).isBlank()&&!"archived".equals(status)&&!Set.of("frozen","abandoned").contains(text(lock))&&access.hasPermission("project:view")&&access.hasPermission("documents:write")&&access.hasPermission("product:write")&&access.canMaintainEngineeringData();
    }
    public boolean canLaunch(){return access.hasPermission("documents:write")&&access.hasPermission("documents:publish")&&access.canMaintainEngineeringData();}
    public void requireLaunchPermission(){access.requirePermission("documents:write");access.requirePermission("documents:publish");access.requireEngineeringMaintenance();}

    @Transactional(propagation=Propagation.MANDATORY)
    public void admitFirstLaunch(long id,long releaseId){
        requireLaunchPermission();access.requireProduct(id);
        var issues=identityIssues(productIdentity(id));require(issues.isEmpty(),String.join("；",issues));
        catalog.admitResearchProduct(id,releaseId,null);
    }
    private Map<String,Object> productIdentity(long id){
        access.requireProduct(id);
        var rows=jdbc.queryForList("""
            select p.product_id as "productId",p.product_code as "productCode",
              p.product_specific_code as "productSpecificCode",p.sales_status as "salesStatus",p.status,p.lock_status as "lockStatus",
              (select count(*) from plm_product same where same.product_specific_code=p.product_specific_code
                and same.product_type='product_line' and same.deleted_flag=0) as "codeCount"
            from plm_product p where p.product_id=? and p.product_type='product_line' and p.deleted_flag=0
            """,id);
        require(rows.size()==1,"新品产品档案已不存在");return rows.get(0);
    }
    private List<String> identityIssues(Map<String,Object> product){
        var issues=new ArrayList<String>();
        if(text(product.get("productCode")).isBlank())issues.add("缺少正式产品档案编码，请在产品资料补齐");
        if(!text(product.get("productSpecificCode")).matches("[A-Z]{2}"))issues.add("缺少有效的两位产品特定编码，请在产品资料补齐；系统不会猜码");
        else if(!(product.get("codeCount") instanceof Number count)||count.longValue()!=1)issues.add("产品特定编码存在冲突，请在产品资料核对");
        return List.copyOf(issues);
    }
}
