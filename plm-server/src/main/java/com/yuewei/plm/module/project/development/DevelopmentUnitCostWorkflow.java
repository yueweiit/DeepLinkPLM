package com.yuewei.plm.module.project.development;

import com.yuewei.plm.module.bom.service.ProductBomWorkflowService;
import com.yuewei.plm.module.pilot.PilotAccessService;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import static com.yuewei.plm.module.project.development.DevelopmentWorkflowStore.*;
import static com.yuewei.plm.module.project.development.DevelopmentRules.*;

/** One project-owned sheet draft; confirmation writes the original BOM cost snapshot store. */
@Component @RequiredArgsConstructor
public class DevelopmentUnitCostWorkflow {
    private final PilotAccessService access;
    private final UnitCostSheetCalculator calculator;
    private final ProductBomWorkflowService boms;
    private final JdbcTemplate jdbc;
    private final DevelopmentWorkflowStore store;

    public boolean handles(String action) {return Set.of("unit_cost_save","unit_cost_confirm","unit_cost_delete","unit_cost_void","unit_cost_from_bom").contains(action);}
    public Map<String,Object> preview(Map<String,Object> input) {access.requirePermission("finance:read");return calculator.calculate(clean(input));}
    public void action(long id,Map<String,Object> state,String action,Map<String,Object> data,ProductDevelopmentService service) {
        access.requireFinanceWrite();
        var versions=LaunchReviewWorkflow.records(state.get("unitCostVersions"));
        if("unit_cost_delete".equals(action)){require(state.containsKey("unitCostDraft"),"没有可删除的成本草稿");state.remove("unitCostDraft");return;}
        if("unit_cost_void".equals(action)){
            long selected=number(data.get("id"));var old=versions.stream().filter(r->number(r.get("id"))==selected).findFirst().orElseThrow(()->invalid("成本版本不存在"));
            require(!old.containsKey("voidReason"),"该成本版本已作废");filled(data.get("reason"),"成本作废原因");old.put("voidReason",data.get("reason"));old.put("voidedBy",access.actor().displayName());old.put("voidedAt",java.time.LocalDateTime.now().toString());state.put("unitCostVersions",versions);return;
        }
        if("unit_cost_from_bom".equals(action)) {
            require(!state.containsKey("unitCostDraft")||Boolean.TRUE.equals(data.get("replaceDraft")),"已有核算草稿，请先保存或确认替换");
            long bom=number(data.get("bomId")),route=number(data.get("routeId"));
            var reference=service.snapshotReferences(id,List.of(new DevelopmentModels.MaterialReference("bom",bom))).get(0);
            var routes=jdbc.queryForList("select product_bom_route_id,process_id from plm_product_bom_route where product_bom_id=? and product_bom_route_id=? and deleted_flag=0",bom,route);
            require(routes.size()==1,"请选择该BOM的具体工艺路线");
            var header=jdbc.queryForMap("select quantity,currency_code from plm_product_bom where product_bom_id=?",bom);
            var input=new LinkedHashMap<String,Object>();input.put("title","BOM单件核算");input.put("currency",text(header.get("currency_code")));input.put("taxBasis","source");input.put("bomId",bom);input.put("routeId",route);input.put("references",List.of(reference));
            var output=(java.math.BigDecimal)header.get("quantity");
            input.put("sourceOutputQty",output);
            var rows=new ArrayList<Map<String,Object>>();
            for(var item:jdbc.queryForList("select product_bom_item_id,line_no,item_name,quantity,unit_cost_snapshot,loss_rate,remark from plm_product_bom_item where product_bom_route_id=? and deleted_flag=0 order by line_no",route)){
                var row=new LinkedHashMap<String,Object>();row.put("id","bom-"+item.get("product_bom_item_id"));row.put("category","material");row.put("name",item.get("item_name"));row.put("description",item.get("remark"));row.put("mode","usage");row.put("unitPrice",item.get("unit_cost_snapshot"));row.put("sourceQuantity",item.get("quantity"));row.put("sourceOutputQty",output);
                boolean single=output!=null&&output.compareTo(java.math.BigDecimal.ONE)==0;
                row.put("quantity",single?item.get("quantity"):null);
                row.put("description",text(item.get("remark"))+"\n原BOM用量："+item.get("quantity")+"／基准产量："+(output==null?"待确认":output.toPlainString()));
                if(!single)row.put("warning","原BOM基准产量为"+(output==null?"待确认":output.toPlainString())+"；请按原单位核对单件用量，不将批量用量直接计为单件成本");
                row.put("lossPct",item.get("loss_rate")==null?null:new java.math.BigDecimal(item.get("loss_rate").toString()).multiply(java.math.BigDecimal.valueOf(100)));row.put("bomItemId",item.get("product_bom_item_id"));rows.add(row);
            }
            for(String group:List.of("process","tooling","tool_transport","development")){var row=new LinkedHashMap<String,Object>();row.put("id",UUID.randomUUID().toString());row.put("category",group);row.put("name",UnitCostSheetCalculator.label(group));row.put("mode","unit");rows.add(row);}
            input.put("rows",rows);state.put("unitCostDraft",calculator.calculate(input));return;
        }
        var sheet=calculator.calculate(clean(data));
        var references=service.snapshotReferences(id,service.refs(data.get("references")));sheet.put("references",references);
        var previous=map(state.get("unitCostDraft"));
        if(number(previous.get("bomId"))>0&&number(previous.get("bomId"))==number(sheet.get("bomId")))service.sameTechnical(LaunchReviewWorkflow.records(previous.get("references")).stream().filter(r->"bom".equals(r.get("type"))).toList(),references.stream().filter(r->"bom".equals(r.get("type"))).toList());
        sheet.put("updatedBy",access.actor().displayName());sheet.put("updatedAt",java.time.LocalDateTime.now().toString());
        if("unit_cost_confirm".equals(action)) {
            calculator.requireConfirmable(sheet);filled(sheet.get("title"),"核算版本名称");
            if(!versions.isEmpty())filled(sheet.get("reason"),"成本版本修订原因");
            long bom=number(sheet.get("bomId")),route=number(sheet.get("routeId"));
            boolean quoteBasis="supplier_quote".equals(sheet.get("basisType"));
            if(quoteBasis){
                require("direct".equals(map(state.get("plan")).get("method"))&&Boolean.FALSE.equals(map(state.get("plan")).get("needsMold")),"供方报价成本依据仅适用于直接采购、无需开模的方案");
                var quote=service.eventData(id,number(sheet.get("quoteEventId")),"quote");
                filled(sheet.get("quoteUnitNote"),"供方报价计价单位、税额及单件换算核对说明");
                require(text(quote.get("currency")).equals(text(sheet.get("currency"))),"供方报价与核算币种不同，请先保存同币种的实际报价版本");
                sheet.put("adoptedQuote",quote);sheet.put("bomVersion","供方报价 Q"+number(sheet.get("quoteEventId")));
                sheet.remove("bomId");sheet.remove("routeId");
            }else{
                sheet.put("basisType","bom");require(bom>0&&route>0,"正式确认前请关联具体BOM及工艺路线");
                require(references.stream().anyMatch(r->"bom".equals(r.get("type"))&&number(r.get("id"))==bom),"请采用所关联BOM的具体版本");
                var ref=references.stream().filter(r->"bom".equals(r.get("type"))&&number(r.get("id"))==bom).findFirst().orElseThrow();
                sheet.put("bomVersion",ref.get("versionNo"));
            }
            var result=calculatorResult(sheet);
            long revision=versions.stream().mapToLong(r->number(r.get("id"))).max().orElse(0)+1;
            sheet.put("id",revision);sheet.put("status","confirmed");sheet.put("confirmedBy",access.actor().displayName());sheet.put("confirmedAt",java.time.LocalDateTime.now().toString());
            if(!quoteBasis){var snapshot=boms.recordReviewedUnitCost(id,bom,route,text(sheet.get("currency")),result,store.encode(sheet));sheet.put("bomCostSnapshotId",snapshot.getProductBomCostSnapshotId());}
            versions.add(sheet);state.put("unitCostVersions",versions);state.remove("unitCostDraft");data.put("savedVersionId",revision);
        } else {sheet.put("status","draft");state.put("unitCostDraft",sheet);}
    }
    private Map<String,Object> clean(Map<String,Object> data) {
        var copy=new LinkedHashMap<>(data);for(String key:List.of("id","status","confirmedBy","confirmedAt","voidReason","voidedBy","voidedAt","bomCostSnapshotId","adoptedQuote","bomVersion","groups","totals","bomResult","complete","missing","warnings"))copy.remove(key);return copy;
    }
    private com.yuewei.plm.module.bom.service.BomCostCalculator.Result calculatorResult(Map<String,Object> sheet) {return (com.yuewei.plm.module.bom.service.BomCostCalculator.Result)sheet.get("bomResult");}
}
