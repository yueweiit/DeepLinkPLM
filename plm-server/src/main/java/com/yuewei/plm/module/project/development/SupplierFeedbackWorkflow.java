package com.yuewei.plm.module.project.development;

import com.yuewei.plm.common.exception.BusinessException;
import com.yuewei.plm.module.partner.SupplierProfileService;
import com.yuewei.plm.module.pilot.PilotAccessService;
import java.time.LocalDate;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import static com.yuewei.plm.module.project.development.DevelopmentRules.*;
import static com.yuewei.plm.module.project.development.DevelopmentWorkflowStore.*;

/** Supplier feedback is an append-only project fact, never another editable supply plan. */
@Component
@RequiredArgsConstructor
public class SupplierFeedbackWorkflow {
    private final PilotAccessService access;
    private final SupplierProfileService suppliers;

    Map<String,Object> prepare(long productId,int stage,Map<String,Object> input,List<Map<String,Object>> events,ProductDevelopmentService host) {
        access.requireEngineeringMaintenance();access.requirePermission("supplier:view");access.requireProduct(productId);
        long feedbackId=number(input.get("feedbackId"));
        var previous=feedbackId>0?current(events).get(feedbackId):null;
        require(feedbackId==0||previous!=null,"供应商反馈不存在或不属于本项目，请刷新");
        boolean correction=previous!=null,voided=Boolean.TRUE.equals(input.get("voided"));
        require(!voided||correction,"请先保存反馈，再通过作废保留原记录");
        if(correction){
            require(!Boolean.TRUE.equals(previous.get("voided")),"该反馈已作废，请新增反馈记录");
            filled(input.get("changeReason"),"更正／作废原因");limited(input.get("changeReason"),2000,"更正／作废原因");
        }
        String code=correction?text(map(previous.get("supplierSnapshot")).get("supplierCode")):text(input.get("supplierCode"));
        filled(code,"供应商");
        require(!correction||text(input.get("supplierCode")).isBlank()||code.equals(text(input.get("supplierCode"))),"反馈归属供应商不能更改，请新增另一供应商的反馈");
        var supplier=correction?suppliers.requireSupplier(code):suppliers.requireSupplierForNewUse(code);
        require(!Boolean.TRUE.equals(input.get("releaseCurrent"))||!correction,"更正／作废反馈不会重新变更当前供应商，请另行维护供方");
        var data=voided?new LinkedHashMap<>(previous):new LinkedHashMap<String,Object>();
        if(!voided){
            String result=text(input.get("result"));require(Set.of("pending","can_accept","cannot_accept").contains(result),"请选择有效的供应商反馈结果");
            if("cannot_accept".equals(result))filled(input.get("reason"),"无法承接的技术原因");
            limited(input.get("scope"),2000,"反馈范围");limited(input.get("reason"),2000,"反馈说明");
            Object date=input.get("feedbackDate");
            if(!text(date).isBlank())try{LocalDate.parse(text(date));}catch(Exception invalid){throw invalid("请填写有效的反馈日期，未知可留空");}
            Object references=input.get("references");
            require(references==null||references instanceof List<?>,"反馈附件格式无效");
            var selected=host.refs(references);require(selected.stream().allMatch(r->"file".equals(r.type())),"反馈仅支持本项目的图片或文件附件");
            data.put("recordType","supplier_feedback");data.put("stage",stage);data.put("scope",text(input.get("scope")));data.put("result",result);
            data.put("reason",text(input.get("reason")));data.put("feedbackDate",text(date).isBlank()?null:text(date));
            var snapshot=new LinkedHashMap<String,Object>();
            for(String key:List.of("supplierId","supplierCode","supplierName","version"))snapshot.put(key,"supplierCode".equals(key)?code:supplier.get(key));
            data.put("supplierSnapshot",snapshot);
            data.put("references",correction&&!input.containsKey("references")?previous.get("references"):host.snapshotReferences(productId,selected));
        }
        // Projection metadata is derived from event rows and must never become a second persisted history.
        for(String key:List.of("eventId","recordedAt","recordedBy","history"))data.remove(key);
        if(correction){data.put("feedbackId",feedbackId);data.put("previousEventId",previous.get("eventId"));data.put("changeReason",text(input.get("changeReason")));}
        data.put("stage",stage);data.put("revision",correction?number(previous.get("revision"))+1:1L);data.put("voided",voided);
        boolean release=Boolean.TRUE.equals(input.get("releaseCurrent"));
        require(!release||"cannot_accept".equals(data.get("result")),"仅无法承接的反馈可解除当前承接分工");
        data.put("releaseCurrent",release);
        return data;
    }

    /** Latest revision plus its immutable revisions; feedbackId is always the first event's identity. */
    public Map<String,Object> projection(List<Map<String,Object>> events) {
        var result=new ArrayList<Map<String,Object>>();boolean restricted=false;
        for(var row:current(events).values()){
            if(canRead(row))result.add(map(redact(row)));else restricted=true;
        }
        return Map.of("items",result,"restricted",restricted);
    }

    /** List and detail share one status rule. Visibility is resolved once per request, never per row. */
    public Map<String,Object> summary(List<Map<String,Object>> events,Map<String,Object> plan,
                                      Map<String,Long> readableSupplierIds,boolean canReadSuppliers) {
        if(!canReadSuppliers)return summary(0,false,false,true);
        var latest=current(events,false).values();
        var change=events.stream().filter(e->"supplier_change".equals(e.get("kind")))
            .filter(e->records(map(map(e.get("data")).get("before")).get("supplierAssignments")).stream()
                .anyMatch(a->"supplier".equals(a.get("mode"))&&"mold".equals(a.get("role"))))
            .max(Comparator.comparingLong(e->number(e.get("eventId")))).map(e->map(e.get("data"))).orElse(Map.of());
        var assignments=records(plan.get("supplierAssignments"));
        var identities=new ArrayList<Map<String,Object>>();
        latest.forEach(row->identities.add(map(row.get("supplierSnapshot"))));
        for(var row:assignments)if("supplier".equals(row.get("mode"))&&!text(row.get("supplierCode")).isBlank())identities.add(row);
        for(String side:List.of("before","after"))for(var row:records(map(change.get(side)).get("supplierAssignments")))
            if("supplier".equals(row.get("mode")))identities.add(row);
        if(identities.stream().anyMatch(row->!readable(row,readableSupplierIds)))return summary(0,false,false,true);
        var active=latest.stream().filter(row->!Boolean.TRUE.equals(row.get("voided"))).toList();
        long cannot=active.stream().filter(row->"cannot_accept".equals(row.get("result")))
            .map(row->readableSupplierIds.get(text(map(row.get("supplierSnapshot")).get("supplierCode")))).distinct().count();
        boolean assigned=assignments.stream().anyMatch(row->"mold".equals(row.get("role"))
            &&("supplier".equals(row.get("mode"))&&!text(row.get("supplierCode")).isBlank()
                ||"internal".equals(row.get("mode"))&&!text(row.get("internalName")).isBlank()));
        return summary(cannot,!assigned&&Boolean.TRUE.equals(change.get("replacementPending")),!active.isEmpty(),false);
    }

    private boolean readable(Map<String,Object> snapshot,Map<String,Long> ids) {
        String code=text(snapshot.get("supplierCode"));Long id=ids.get(code);
        return id!=null&&(number(snapshot.get("supplierId"))==0||number(snapshot.get("supplierId"))==id);
    }
    private Map<String,Object> summary(long cannot,boolean pending,boolean hasFeedback,boolean restricted) {
        return Map.of("cannotAcceptSupplierCount",cannot,"nextSupplierPending",pending,"hasFeedback",hasFeedback,"restricted",restricted);
    }

    private Map<Long,Map<String,Object>> current(List<Map<String,Object>> events) {
        return current(events,true);
    }
    private Map<Long,Map<String,Object>> current(List<Map<String,Object>> events,boolean includeHistory) {
        var result=new LinkedHashMap<Long,Map<String,Object>>();
        events.stream().filter(e->"supplier_feedback".equals(e.get("kind"))).sorted(Comparator.comparingLong(e->number(e.get("eventId")))).forEach(event->{
            var data=new LinkedHashMap<>(map(event.get("data")));long eventId=number(event.get("eventId")),id=number(data.get("feedbackId"));if(id==0)id=eventId;
            var before=result.get(id);var history=new ArrayList<Map<String,Object>>();
            if(includeHistory&&before!=null)history.addAll(records(before.get("history")));
            data.put("feedbackId",id);data.put("eventId",eventId);data.put("recordedAt",event.get("createdAt"));data.put("recordedBy",event.get("createdBy"));
            if(includeHistory){history.add(new LinkedHashMap<>(data));data.put("history",history);}result.put(id,data);
        });return result;
    }

    /** Also applied to raw project logs, so history cannot bypass supplier/file object permissions. */
    public Object redact(Object value) {
        if(value instanceof List<?> list)return list.stream().map(this::redact).toList();
        if(!(value instanceof Map<?,?> source))return value;
        var data=new LinkedHashMap<String,Object>();source.forEach((key,item)->data.put(key.toString(),item));
        if(map(data.get("before")).get("supplierAssignments") instanceof List<?> && map(data.get("after")).get("supplierAssignments") instanceof List<?>){
            var codes=new HashSet<String>();
            for(String side:List.of("before","after"))for(var assignment:records(map(data.get(side)).get("supplierAssignments"))){
                String code=text(assignment.get("supplierCode"));if(!"internal".equals(assignment.get("mode"))&&!code.isBlank())codes.add(code);
            }
            if(codes.stream().anyMatch(code->!canReadCode(code)))return Map.of("supplierFeedbackRestricted",true,"suppliersRestricted",true);
        }
        if("supplier_feedback".equals(data.get("recordType"))){
            if(!canRead(data))return Map.of("supplierFeedbackRestricted",true);
            var visible=new ArrayList<Map<String,Object>>();boolean hidden=false;
            for(var ref:records(data.get("references")))try{access.requireFile(number(ref.get("id")));visible.add(ref);}catch(BusinessException denied){hidden=true;}
            data.put("references",visible);if(hidden)data.put("referencesRestricted",true);
        }
        var result=new LinkedHashMap<String,Object>();data.forEach((key,item)->result.put(key,redact(item)));return result;
    }

    private boolean canRead(Map<String,Object> data){
        return canReadCode(text(map(data.get("supplierSnapshot")).get("supplierCode")));
    }
    private boolean canReadCode(String code){
        try{access.requirePermission("supplier:view");suppliers.requireSupplier(code);return true;}
        catch(BusinessException denied){return false;}
    }
    private List<Map<String,Object>> records(Object value){return value instanceof List<?> rows?rows.stream().map(DevelopmentWorkflowStore::map).toList():List.of();}
    private void limited(Object value,int max,String label){require(text(value).length()<=max,label+"最多"+max+"字");}
}
