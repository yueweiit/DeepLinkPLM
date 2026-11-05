package com.yuewei.plm.module.project.development;

import com.yuewei.plm.module.bom.entity.ProductBomItem;
import com.yuewei.plm.module.bom.service.BomCostCalculator;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import static com.yuewei.plm.module.project.development.DevelopmentWorkflowStore.*;
import static com.yuewei.plm.module.project.development.DevelopmentRules.*;

/** Spreadsheet-shaped inputs feed the existing BOM calculator; no executable formula strings. */
@Component @RequiredArgsConstructor
public class UnitCostSheetCalculator {
    private final BomCostCalculator bomCosts;
    public static final List<String> BASE_GROUPS=List.of("material","process","tooling","tool_transport","development");
    public static final Set<String> GROUPS=Set.of("material","process","packaging","tooling","tool_transport","development","freight_sea","freight_air");
    public Map<String,Object> calculate(Map<String,Object> input) {
        var sheet=new LinkedHashMap<>(input);
        String currency=text(input.get("currency"));
        require(currency.isBlank()||currency.matches("[A-Z]{3}"),"核算币种无效");sheet.put("currency",currency);
        String taxBasis=text(input.get("taxBasis"));if(taxBasis.isBlank())taxBasis="source";
        require(Set.of("source","net","gross").contains(taxBasis),"含税口径无效");sheet.put("taxBasis",taxBasis);
        var fx=optional(input.get("exchangeRate"),"换算汇率");if(fx!=null)require(fx.signum()>0,"换算汇率必须大于0");
        var rows=LaunchReviewWorkflow.records(input.get("rows"));require(rows.size()<=200,"成本明细最多200项");
        var result=new ArrayList<Map<String,Object>>();var groupAmounts=new LinkedHashMap<String,BigDecimal>();
        var missing=new ArrayList<String>();var warnings=new ArrayList<String>();var rowIds=new HashSet<String>();
        if(currency.isBlank())missing.add("核算币种待确认");
        for(int i=0;i<rows.size();i++) {
            var row=new LinkedHashMap<>(rows.get(i));String group=text(row.get("category"));require(GROUPS.contains(group),"费用类型无效");
            String rowId=text(row.get("id"));require(!rowId.isBlank()&&rowIds.add(rowId),"成本明细编号为空或重复");
            String name=text(row.get("name"));if(name.isBlank())name="第"+(i+1)+"项";
            String mode=text(row.get("mode"));if(mode.isBlank())mode="unit";
            require(Set.of("unit","usage","divide").contains(mode),"成本计算方式无效");row.put("mode",mode);
            var price=optional(row.get("unitPrice"),name+"单价／总额");
            BigDecimal value=price;
            if("divide".equals(mode)) {
                var divisor=optional(row.get("allocationQty"),name+"分摊数量");
                if(divisor!=null)require(divisor.signum()>0,"分摊数量必须大于0");
                value=price==null||divisor==null?null:price.divide(divisor,12,RoundingMode.HALF_UP);
            }else if("usage".equals(mode)) {
                var quantity=optional(row.get("quantity"),name+"用量");var loss=optional(row.get("lossPct"),name+"损耗率");
                if(loss!=null)require(loss.compareTo(BigDecimal.valueOf(100))<=0,"损耗率不能超过100%");
                value=price==null||quantity==null||loss==null?null:price.multiply(quantity).multiply(BigDecimal.ONE.add(loss.divide(BigDecimal.valueOf(100))));
            }
            if(!"source".equals(taxBasis)&&value!=null) {
                if(!(row.get("taxIncluded") instanceof Boolean))value=null;
                else {
                    var rate=optional(row.get("taxRate"),name+"税率");
                    if(rate==null)value=null;
                    else {
                        require(rate.compareTo(BigDecimal.valueOf(100))<=0,"税率不能超过100%");var factor=BigDecimal.ONE.add(rate.divide(BigDecimal.valueOf(100)));
                        if("net".equals(taxBasis)&&Boolean.TRUE.equals(row.get("taxIncluded")))value=value.divide(factor,12,RoundingMode.HALF_UP);
                        if("gross".equals(taxBasis)&&Boolean.FALSE.equals(row.get("taxIncluded")))value=value.multiply(factor);
                    }
                }
            }
            row.put("unitCost",value);row.put("displayCost",text(input.get("displayCurrency")).isBlank()||value==null||fx==null?null:value.multiply(fx));
            if(value==null)missing.add(name+"：单价、用量、分摊数量或税率待补");
            if(!text(row.get("warning")).isBlank())warnings.add(name+"："+row.get("warning"));
            if(Boolean.TRUE.equals(row.get("investmentAllocationPending")))warnings.add(name+"：项目总额与原分项分摊范围待核对，当前沿用原表估算");
            if(!groupAmounts.containsKey(group))groupAmounts.put(group,value);
            else if(groupAmounts.get(group)==null||value==null)groupAmounts.put(group,null);
            else groupAmounts.put(group,groupAmounts.get(group).add(value));
            result.add(row);
        }
        for(String group:BASE_GROUPS)if(!groupAmounts.containsKey(group))missing.add(label(group)+"未填写；不发生的费用请明确填0并说明");
        var totals=new LinkedHashMap<String,Object>();
        BigDecimal material=groupAmounts.get("material"),process=groupAmounts.get("process");
        var packaging=groupAmounts.getOrDefault("packaging",BigDecimal.ZERO);
        totals.put("manufacturing",material==null||process==null||packaging==null?null:material.add(process).add(packaging));
        BomCostCalculator.Result canonical=null;
        if(!currency.isBlank()&&packaging!=null&&BASE_GROUPS.stream().allMatch(g->groupAmounts.containsKey(g)&&groupAmounts.get(g)!=null)) {
            var items=new ArrayList<ProductBomItem>();int index=0;
            for(var row:result)if("material".equals(row.get("category"))) {
                var item=new ProductBomItem();item.setLineNo(++index);item.setUnitCostSnapshot((BigDecimal)row.get("unitCost"));item.setQuantity(BigDecimal.ONE);item.setLossRate(BigDecimal.ZERO);item.setCurrencyCode(currency);items.add(item);
            }
            canonical=bomCosts.calculate(items,currency,process,packaging,BigDecimal.ZERO,groupAmounts.get("tooling"),groupAmounts.get("tool_transport").add(groupAmounts.get("development")));
        }
        BigDecimal base=canonical==null?null:canonical.totalCost();totals.put("base",base);
        for(String freight:List.of("freight_sea","freight_air"))totals.put(freight,base==null||groupAmounts.get(freight)==null?null:base.add(groupAmounts.get(freight)));
        sheet.put("rows",result);sheet.put("groups",groupAmounts);sheet.put("totals",totals);sheet.put("missing",missing);sheet.put("warnings",warnings);
        if(input.get("sourceOutlay") instanceof Map<?,?>)sheet.put("sourceOutlay",sourceOutlay(map(input.get("sourceOutlay"))));
        sheet.put("complete",missing.isEmpty());sheet.put("bomResult",canonical);return sheet;
    }
    private Map<String,Object> sourceOutlay(Map<String,Object> input) {
        var result=new LinkedHashMap<>(input);String currency=text(input.get("currency"));require(currency.matches("[A-Z]{3}"),"前期投入估算币种无效");
        var sourceRows=LaunchReviewWorkflow.records(input.get("rows"));require(!sourceRows.isEmpty()&&sourceRows.size()<=200,"前期投入估算需填写1至200项");
        var rows=new ArrayList<Map<String,Object>>();BigDecimal purchaseTotal=null,transportTotal=null;int missing=0;
        for(var source:sourceRows){
            var row=new LinkedHashMap<>(source);var price=optional(row.get("unitPrice"),"前期采购单价");var quantity=optional(row.get("quantity"),"前期采购数量");var transport=optional(row.get("transportAmount"),"前期运输及清关估算");
            var purchase=price==null||quantity==null?null:price.multiply(quantity);row.put("purchaseAmount",purchase);row.put("combinedAmount",purchase==null||transport==null?null:purchase.add(transport));
            if(purchase==null||transport==null)missing++;if(purchase!=null)purchaseTotal=purchaseTotal==null?purchase:purchaseTotal.add(purchase);if(transport!=null)transportTotal=transportTotal==null?transport:transportTotal.add(transport);rows.add(row);
        }
        result.put("rows",rows);result.put("purchaseTotal",purchaseTotal);result.put("transportTotal",transportTotal);result.put("knownTotal",purchaseTotal==null&&transportTotal==null?null:(purchaseTotal==null?BigDecimal.ZERO:purchaseTotal).add(transportTotal==null?BigDecimal.ZERO:transportTotal));result.put("missingCount",missing);return result;
    }
    public void requireConfirmable(Map<String,Object> sheet) {
        require(Boolean.TRUE.equals(sheet.get("complete")),"成本明细尚有待补项目，请先保存草稿");
        require(!"source".equals(sheet.get("taxBasis")),"请统一为未税或含税口径，并补齐各行税率后确认");
        require(LaunchReviewWorkflow.records(sheet.get("rows")).stream().noneMatch(r->!text(r.get("warning")).isBlank()||Boolean.TRUE.equals(r.get("investmentAllocationPending"))),"请先处理成本明细中的待核对问题");
        if(!text(sheet.get("displayCurrency")).isBlank()&&!text(sheet.get("displayCurrency")).equals(sheet.get("currency"))) {
            require(optional(sheet.get("exchangeRate"),"换算汇率")!=null,"请填写换算汇率");
            LaunchReviewWorkflow.actualDate(sheet.get("exchangeRateDate"),"汇率依据日期");
        }
    }
    private static BigDecimal optional(Object value,String label) {
        if(value==null||text(value).isBlank())return null;
        try {var n=new BigDecimal(value.toString());require(n.signum()>=0&&n.compareTo(new BigDecimal("1000000000000"))<=0,label+"必须为合理的非负数");return n;}
        catch(NumberFormatException ex){throw invalid(label+"必须是有效数值");}
    }
    static String label(String category) {return switch(category){case "material"->"材料成本";case "process"->"加工成本";case "packaging"->"包装成本";case "tooling"->"模具摊销";case "tool_transport"->"模具运输摊销";case "development"->"开发费用";default->category;};}
}
