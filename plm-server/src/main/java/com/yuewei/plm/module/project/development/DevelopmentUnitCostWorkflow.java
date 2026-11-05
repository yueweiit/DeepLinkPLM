package com.yuewei.plm.module.project.development;

import com.yuewei.plm.module.bom.service.ProductBomWorkflowService;
import com.yuewei.plm.module.pilot.PilotAccessService;
import com.yuewei.plm.module.development.DevelopmentFinanceService;
import com.yuewei.plm.module.development.DevelopmentTypes;
import java.math.BigDecimal;
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
    private final DevelopmentFinanceService finance;

    public boolean handles(String action) {return Set.of("unit_cost_save","unit_cost_confirm","unit_cost_delete","unit_cost_void","unit_cost_from_bom","unit_cost_group_save","unit_cost_group_delete","unit_cost_group_status").contains(action);}
    public Map<String,Object> preview(long id,Map<String,Object> input) {access.requirePermission("finance:read");String key=text(input.get("costScopeId"));if(!key.isBlank())DevelopmentCostScopes.find(store.load(id),key,false);return calculator.calculate(projectInvestment(id,clean(input)));}
    public void action(long id,Map<String,Object> state,String action,Map<String,Object> data,ProductDevelopmentService service) {
        access.requireFinanceWrite();
        if(action.startsWith("unit_cost_group_")){groupAction(id,state,action,data,service);return;}
        String scopeId=text(data.get("costScopeId"));var projectState=state;state=DevelopmentCostScopes.costState(projectState,scopeId,true);
        if(!scopeId.isBlank()){data.put("costScopeName",state.get("name"));data.put("costScopePhoneModelIds",state.get("phoneModelIds"));}
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
            if(!scopeId.isBlank())input.putAll(Map.of("costScopeId",scopeId,"costScopeName",state.get("name"),"costScopePhoneModelIds",state.get("phoneModelIds")));
            var previous=map(state.get("unitCostDraft"));
            for(String key:List.of("extraColumns","visibleOptionalColumns"))if(previous.containsKey(key))input.put(key,previous.get(key));
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
        var sheet=calculator.calculate(projectInvestment(id,clean(data)));
        var references=service.snapshotReferences(id,service.refs(data.get("references")));sheet.put("references",references);
        if(sheet.get("sourceOutlay") instanceof Map<?,?>){
            require(scopeId.isBlank(),"前期整批投入保留在项目原估算，不复制为组内成本");var source=map(sheet.get("sourceOutlay"));var reference=map(source.get("sourceReference"));
            var file=references.stream().filter(r->"file".equals(r.get("type"))&&number(r.get("id"))==number(reference.get("id"))).findFirst().orElseThrow(()->invalid("请采用前期投入原文件的项目附件版本"));
            require(text(file.get("checksum")).equals(text(source.get("sourceHash")))&&!text(source.get("sourceHash")).isBlank(),"前期投入原文件校验值与采用的附件版本不一致");source.put("sourceVersion",file.get("versionNo"));source.put("sourceFileName",file.get("name"));
        }
        var previous=map(state.get("unitCostDraft"));
        if(number(previous.get("bomId"))>0&&number(previous.get("bomId"))==number(sheet.get("bomId")))service.sameTechnical(LaunchReviewWorkflow.records(previous.get("references")).stream().filter(r->"bom".equals(r.get("type"))).toList(),references.stream().filter(r->"bom".equals(r.get("type"))).toList());
        sheet.put("updatedBy",access.actor().displayName());sheet.put("updatedAt",java.time.LocalDateTime.now().toString());
        if("unit_cost_confirm".equals(action)) {
            calculator.requireConfirmable(sheet);filled(sheet.get("title"),"核算版本名称");
            if(!versions.isEmpty())filled(sheet.get("reason"),"成本版本修订原因");
            long bom=number(sheet.get("bomId")),route=number(sheet.get("routeId"));
            boolean quoteBasis="supplier_quote".equals(sheet.get("basisType"));
            if(quoteBasis){
                require("direct".equals(map(projectState.get("plan")).get("method"))&&Boolean.FALSE.equals(map(projectState.get("plan")).get("needsMold")),"供方报价成本依据仅适用于直接采购、无需开模的方案");
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
            long revision=DevelopmentCostScopes.nextVersionId(projectState);
            sheet.put("id",revision);sheet.put("status","confirmed");sheet.put("confirmedBy",access.actor().displayName());sheet.put("confirmedAt",java.time.LocalDateTime.now().toString());
            if(!quoteBasis){var snapshot=scopeId.isBlank()?boms.recordReviewedUnitCost(id,bom,route,text(sheet.get("currency")),result,store.encode(sheet)):boms.recordReviewedUnitCost(id,bom,route,text(sheet.get("currency")),result,store.encode(sheet),scopeId);sheet.put("bomCostSnapshotId",snapshot.getProductBomCostSnapshotId());}
            versions.add(sheet);state.put("unitCostVersions",versions);state.remove("unitCostDraft");data.put("savedVersionId",revision);
        } else {sheet.put("status","draft");state.put("unitCostDraft",sheet);}
    }
    private void groupAction(long id,Map<String,Object> state,String action,Map<String,Object> data,ProductDevelopmentService service){
        var scopes=DevelopmentCostScopes.scopes(state);String key=text(data.get("costScopeId"));var current=key.isBlank()?new LinkedHashMap<String,Object>():DevelopmentCostScopes.find(state,key,false);
        if(!action.equals("unit_cost_group_save")){
            require(!key.isBlank(),"请选择成本组");
            if(action.equals("unit_cost_group_delete")){
                var references=new ArrayList<String>();if(current.containsKey("unitCostDraft"))references.add("核算草稿");
                LaunchReviewWorkflow.records(current.get("unitCostVersions")).forEach(v->references.add("C"+v.get("id")));
                references.addAll(jdbc.queryForList("select '预算B'||budget_id from plm_development_budget where cost_scope_key=? union all select '费用E'||project_cost_item_id from plm_project_cost_item where cost_scope_key=?",String.class,key,key));
                require(references.isEmpty(),"成本组已有核算记录或费用引用："+String.join("、",references)+"；请停用保留历史");scopes.removeIf(s->key.equals(s.get("id")));
            }else current.put("inactive",Boolean.TRUE.equals(data.get("inactive")));
        }else{
            require(!Boolean.TRUE.equals(current.get("inactive")),"停用组请先恢复");String name=text(data.get("name"));require(!name.isBlank()&&name.length()<=128,"请填写128字以内的开发规格名称");
            var phones=DevelopmentRules.ids(data.get("phoneModelIds"));require(phones.size()<=200,"每组最多选择200个兼容手机");
            for(long phone:phones)require(Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from plm_phone_model_member where id=? and coalesce((to_jsonb(plm_phone_model_member)->>'deleted_flag')::int,0)=0)",Boolean.class,phone)),"兼容手机不存在，请从已有资料选择");
            if(key.isBlank()){
                require(scopes.size()<100,"每个项目最多维护100个成本组");current.put("id",UUID.randomUUID().toString());
                if(number(data.get("applicationId"))>0){
                    long applicationId=number(data.get("applicationId"));String specKey=text(data.get("specKey"));
                    var application=LaunchReviewWorkflow.records(service.detail(id).get("applications")).stream().filter(a->number(a.get("applicationId"))==applicationId).findFirst().orElseThrow(()->invalid("开发规格来源申请不属于本项目或不可读取"));
                    var specification=LaunchReviewWorkflow.records(map(application.get("engineeringPlan")).get("specifications")).stream().filter(s->specKey.equals(s.get("key"))).findFirst().orElseThrow(()->invalid("来源申请尚无明确开发规格，请手动建立估算组并保留待核范围"));
                    require(scopes.stream().noneMatch(s->number(s.get("applicationId"))==applicationId&&specKey.equals(s.get("specKey"))),"该申请规格已有成本组，请维护原组");require(DevelopmentRules.ids(specification.get("phoneModelIds")).containsAll(phones),"兼容手机不在所选开发规格范围内");current.put("applicationId",applicationId);current.put("specKey",specKey);
                }
                current.put("createdBy",access.actor().displayName());current.put("createdAt",java.time.LocalDateTime.now().toString());scopes.add(current);
            }
            current.put("name",name);current.put("phoneModelIds",phones);
        }
        current.put("updatedBy",access.actor().displayName());current.put("updatedAt",java.time.LocalDateTime.now().toString());state.put("costScopes",scopes);data.put("savedCostScopeId",current.get("id"));
    }
    private Map<String,Object> clean(Map<String,Object> data) {
        var copy=new LinkedHashMap<>(data);for(String key:List.of("id","status","confirmedBy","confirmedAt","voidReason","voidedBy","voidedAt","bomCostSnapshotId","adoptedQuote","bomVersion","groups","totals","bomResult","complete","missing","warnings"))copy.remove(key);return copy;
    }
    private Map<String,Object> projectInvestment(long id,Map<String,Object> input) {
        if(!Boolean.TRUE.equals(input.get("useProjectInvestment")))return input;
        var query=new DevelopmentTypes.Query();query.setProjectId(id);
        query.setCostScopeKey(text(input.get("costScopeId")).isBlank()?"unassigned":text(input.get("costScopeId")));
        var estimates=map(finance.summary("budget",query).get("investment"));
        var actuals=map(finance.summary("expense",query).get("investment"));
        String currency=text(input.get("currency"));
        var basis=new ArrayList<Map<String,Object>>();
        for(String category:List.of("mold","transport","customs","design","sample","other")) {
            var actual=investmentPart(actuals,currency,category);var estimate=investmentPart(estimates,currency,category);
            boolean useActual=actual.get("knownTotal")!=null&&number(actual.get("missingCount"))==0;
            var selected=useActual?actual:estimate;
            var entry=new LinkedHashMap<String,Object>();entry.put("category",category);entry.put("currency",currency);
            entry.put("amount",number(selected.get("missingCount"))==0?selected.get("knownTotal"):null);
            entry.put("basis",useActual?"actual":"estimated");
            entry.put("sources",LaunchReviewWorkflow.records((useActual?actuals:estimates).get("rows")).stream().filter(r->currency.equals(text(r.get("currency")))&&category.equals(r.get("category"))).toList());
            basis.add(entry);
        }
        var oldRows=LaunchReviewWorkflow.records(input.get("rows"));var rows=new ArrayList<Map<String,Object>>();
        oldRows.stream().filter(r->!Set.of("tooling","tool_transport","development").contains(text(r.get("category")))).forEach(rows::add);
        var originalRows=new ArrayList<>(LaunchReviewWorkflow.records(input.get("investmentOriginalRows")));
        for(var row:oldRows)if(Set.of("tooling","tool_transport","development").contains(text(row.get("category")))&&!text(row.get("id")).equals("project-investment-"+row.get("category"))&&originalRows.stream().noneMatch(original->Objects.equals(original.get("id"),row.get("id"))))originalRows.add(new LinkedHashMap<>(row));
        input.put("investmentOriginalRows",originalRows);
        for(String group:List.of("tooling","tool_transport","development")) {
            var categories=switch(group){case "tooling"->List.of("mold");case "tool_transport"->List.of("transport","customs");default->List.of("design","sample","other");};
            var previous=oldRows.stream().filter(r->group.equals(r.get("category"))).toList();
            var sourceRows=previous.stream().filter(r->!("project-investment-"+group).equals(r.get("id"))).toList();
            if(sourceRows.isEmpty()&&previous.stream().anyMatch(r->("project-investment-"+group).equals(r.get("id"))))sourceRows=originalRows.stream().filter(r->group.equals(r.get("category"))).toList();
            boolean complete=basis.stream().filter(entry->categories.contains(entry.get("category"))).allMatch(entry->entry.get("amount")!=null);
            var sourceQuantities=sourceRows.stream().filter(r->"divide".equals(r.get("mode"))&&r.get("allocationQty")!=null).map(r->new BigDecimal(r.get("allocationQty").toString()).stripTrailingZeros()).distinct().toList();
            // Shared frames and model-specific cores have different lifetimes. A project total cannot replace their individual divisors.
            boolean differentQuantities=sourceQuantities.size()>1;
            if(!sourceRows.isEmpty()&&(!complete||differentQuantities)){
                for(var source:sourceRows){
                    var retained=new LinkedHashMap<>(source);retained.remove("investmentCategories");retained.put("investmentFallback",true);
                    if(complete&&differentQuantities)retained.put("investmentAllocationPending",true);else retained.remove("investmentAllocationPending");
                    rows.add(retained);
                }
                continue;
            }
            var row=new LinkedHashMap<String,Object>();var managed=previous.stream().filter(r->("project-investment-"+group).equals(r.get("id"))).findFirst();if(managed.isPresent())row.putAll(managed.get());else if(previous.size()==1)row.putAll(previous.get(0));
            row.remove("investmentFallback");row.remove("investmentAllocationPending");
            row.put("id","project-investment-"+group);row.put("category",group);row.put("name",UnitCostSheetCalculator.label(group));row.put("mode","divide");
            var quantities=previous.stream().filter(r->"divide".equals(r.get("mode"))&&r.get("allocationQty")!=null).map(r->new BigDecimal(r.get("allocationQty").toString()).stripTrailingZeros()).distinct().toList();
            row.put("allocationQty",quantities.size()==1&&previous.stream().allMatch(r->"divide".equals(r.get("mode"))&&r.get("allocationQty")!=null)?quantities.get(0):null);
            BigDecimal total=BigDecimal.ZERO;
            for(var entry:basis)if(categories.contains(entry.get("category"))){if(entry.get("amount")==null)complete=false;else total=total.add(new BigDecimal(entry.get("amount").toString()));}
            row.put("unitPrice",complete?total:null);row.put("investmentCategories",categories);row.put("description","自动采用上方已保存费用：实际优先，无完整实际时沿用预计；同币种，不重复计入");
            row.remove("unitCost");row.remove("displayCost");rows.add(row);
        }
        input.put("rows",rows);input.put("investmentBasis",basis);return input;
    }
    private Map<String,Object> investmentPart(Map<String,Object> summary,String currency,String category) {
        return LaunchReviewWorkflow.records(summary.get("currencyGroups")).stream().filter(g->currency.equals(g.get("currency"))).flatMap(g->LaunchReviewWorkflow.records(g.get("categories")).stream()).filter(c->category.equals(c.get("category"))).findFirst().orElse(Map.of("missingCount",1));
    }
    private com.yuewei.plm.module.bom.service.BomCostCalculator.Result calculatorResult(Map<String,Object> sheet) {return (com.yuewei.plm.module.bom.service.BomCostCalculator.Result)sheet.get("bomResult");}
}
