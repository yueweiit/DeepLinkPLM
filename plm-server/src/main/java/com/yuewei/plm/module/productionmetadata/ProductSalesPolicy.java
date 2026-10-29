package com.yuewei.plm.module.productionmetadata;

import com.yuewei.plm.common.exception.BusinessException;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** One catalog decision governs all new model-development entry points. */
@Service @RequiredArgsConstructor
public class ProductSalesPolicy {
    private final JdbcTemplate jdbc;
    /** Draft eligibility only. Reservation/submission still verify real identities and code configuration. */
    public record MoldApplicationEligibility(boolean eligible,String kind,String reason) {}
    public static MoldApplicationEligibility moldApplicationEligibility(Map<String,Object> product) {
        if("launch_v1".equals(product.get("development_mode"))&&!Set.of("released","archived").contains(Objects.toString(product.get("status"),""))){
            if(Set.of("frozen","abandoned").contains(Objects.toString(product.get("lock_status"),"")))
                return new MoldApplicationEligibility(false,"research","冻结或停止的新品项目只读，请通过原项目继续开发");
            return new MoldApplicationEligibility(true,"research",null);
        }
        return catalogEligibility(product);
    }
    private static MoldApplicationEligibility catalogEligibility(Map<String,Object> product) {
        if("on_sale".equals(product.get("sales_status")))return new MoldApplicationEligibility(true,"catalog",null);
        return new MoldApplicationEligibility(false,"blocked",product.get("product_name")+("off_sale".equals(product.get("sales_status"))?"已停售":"在售状态尚未确认")+"，不能发起型号开发、开模、预留编码或提交；请改选在售产品，历史记录保留");
    }
    /** New-product mold preparation is available throughout active research, before sales release. */
    public void requireMoldDevelopmentEligible(Collection<Long> productLines) {
        var ids=new TreeSet<Long>();productLines.stream().filter(Objects::nonNull).forEach(ids::add);if(ids.isEmpty())return;
        var rows=jdbc.queryForList("select p.product_id,p.product_name,p.sales_status,p.development_mode,p.status,p.lock_status,p.current_step_no from plm_product p where p.product_id in ("+String.join(",",Collections.nCopies(ids.size(),"?"))+") and p.product_type='product_line' and p.deleted_flag=0 order by p.product_id for share of p",ids.toArray());
        if(rows.size()!=ids.size())throw new BusinessException(40001,"开模产品不存在或已停用");
        for(var row:rows){
            var eligibility=moldApplicationEligibility(row);
            if(!eligibility.eligible())throw new BusinessException(40001,eligibility.reason());
        }
    }
    public void requireOnSale(Collection<Long> productLines) {
        var ids=new TreeSet<Long>();productLines.stream().filter(Objects::nonNull).forEach(ids::add);if(ids.isEmpty())return;
        var rows=jdbc.queryForList("select product_id,product_name,sales_status from plm_product where product_id in ("+String.join(",",Collections.nCopies(ids.size(),"?"))+") and product_type='product_line' and deleted_flag=0 order by product_id for share",ids.toArray());
        if(rows.size()!=ids.size())throw new BusinessException(40001,"开模产品不存在或已停用，请重新选择在售产品");
        for(var row:rows){var eligibility=catalogEligibility(row);if(!eligibility.eligible())throw new BusinessException(40001,eligibility.reason());}
    }
}
