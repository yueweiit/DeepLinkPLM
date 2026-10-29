package com.yuewei.plm.module.moldapplication;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuewei.plm.common.exception.BusinessException;
import com.yuewei.plm.common.util.ProductBusinessCodeGenerator;
import java.time.*;
import java.util.*;

/** Stable OA component IDs, with original snapshots retained for older template versions. */
public final class MoldApplicationForms {
    private MoldApplicationForms() {}
    public static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
    public static final Map<String,String> COMPONENTS;
    static {
        var ids=new LinkedHashMap<String,String>();
        ids.put("model","TextField-IH4TZP18");ids.put("networkType","DDSelectField_YLFSPZ54MMO0");
        ids.put("holeType","DDSelectField_3DGZZWYSEZ20");ids.put("mobileFunction","TextareaField_1WR63M7QNFSW0");
        ids.put("tipo","DDSelectField-JFX38WE3");ids.put("priority","DDSelectField-JXM5A55C");
        ids.put("manufacturingLocation","DDSelectField-JXM6BWXW");ids.put("newModel","DDSelectField_1X7JB5IV2VTS0");
        ids.put("moldMarking","TextField-JR8J9S3P");ids.put("salesChannel","TextField_1MELQ5RM265C0");
        ids.put("phonePrice","NumberField_VO6B7HSBOE80");ids.put("website","TextField_4RM8KO328XE0");
        ids.put("remark","TextareaField-IH4TZP1I");ids.put("expectedDeliveryDate","DDDateField-IH4TZP1H");
        ids.put("samplePhotos","DDPhotoField_1MXBBIMPHTFK0");ids.put("testResults","TextareaField_1P7S0CILENOG0");
        ids.put("keyPhotos","DDPhotoField-IH4TZP1K");ids.put("informationComplete","DDSelectField_BRB3EHMILSW0");
        ids.put("generatedCodes","TextField_1U6D0QQC4P340");ids.put("attachments","DDAttachment-JKI4M3HZ");
        COMPONENTS=Collections.unmodifiableMap(ids);
    }
    public static String text(Object value) {
        if(value==null)return null;
        String s=value instanceof JsonNode node?node.isNull()?"":node.asText():value.toString();
        s=s.trim();return s.isEmpty()||s.equalsIgnoreCase("null")?null:s;
    }
    public static Map<String,Object> normalize(JsonNode detail) {
        Map<String,Object> result=new LinkedHashMap<>();
        var byId=new HashMap<String,JsonNode>();detail.path("form_component_values").forEach(f->byId.put(f.path("id").asText(),f));
        COMPONENTS.forEach((key,id)->{var f=byId.get(id);if(f!=null)result.put(key,text(f.path("value")));});
        return result;
    }
    public static OffsetDateTime date(String value) {
        if(text(value)==null)return null;
        try{return OffsetDateTime.parse(value);}catch(Exception ignored){}
        try{return LocalDateTime.parse(value.replace(' ','T')).atZone(ZONE).toOffsetDateTime();}
        catch(Exception ignored){return null;}
    }
    public static List<String> codes(String raw) {
        return ProductBusinessCodeGenerator.splitCompleteMoldCodes(raw);
    }
    public static boolean hasValidCodes(String raw){return text(raw)!=null&&!codes(raw).isEmpty()&&raw.replaceAll("(?i)"+ProductBusinessCodeGenerator.COMPLETE_MOLD_CODE_REGEX,"").replaceAll("[\\s/，,、;；]+","").isEmpty();}
    public static final String EXECUTION_APPROVER_USER_ID="16693147192083157833";
    public record ApprovalEvidence(Map<String,Object> authorization,Map<String,Object> exception,boolean granted) {
        public boolean taskAuthorized(){return granted&&"task".equals(authorization.get("evidenceSource"));}
    }
    /** Pure parser shared by live OA ingestion and historical preflight/migration. */
    public static ApprovalEvidence approvalEvidence(JsonNode detail) {
        var proof=findAuthorization(detail);var exception=revocation(detail);
        return new ApprovalEvidence(proof,exception,proof!=null&&exception==null);
    }
    public static Map<String,Object> authorization(JsonNode detail) {
        return approvalEvidence(detail).authorization();
    }
    private static Map<String,Object> findAuthorization(JsonNode detail) {
        List<JsonNode> tasks=new ArrayList<>();detail.path("tasks").forEach(tasks::add);
        tasks.sort(Comparator.comparing((JsonNode n)->n.path("finish_time").asText()).thenComparing(n->n.path("taskid").asText()));
        for(var task:tasks) {
            if(EXECUTION_APPROVER_USER_ID.equals(task.path("userid").asText())&&"COMPLETED".equalsIgnoreCase(task.path("task_status").asText())&&Set.of("AGREE","APPROVE").contains(task.path("task_result").asText().toUpperCase(Locale.ROOT))&&text(task.path("taskid"))!=null&&date(text(task.path("finish_time")))!=null) {
                var proof=new LinkedHashMap<String,Object>();proof.put("approverUserId",EXECUTION_APPROVER_USER_ID);proof.put("approverName","周汉琴");proof.put("taskId",text(task.path("taskid")));proof.put("activityId",text(task.path("activity_id")));proof.put("approvedAt",text(task.path("finish_time")));proof.put("evidenceSource","task");return proof;
            }
        }
        List<JsonNode> operations=new ArrayList<>();detail.path("operation_records").forEach(operations::add);
        operations.sort(Comparator.comparing(n->n.path("date").asText()));
        for(var operation:operations) {
            if(EXECUTION_APPROVER_USER_ID.equals(operation.path("userid").asText())&&"EXECUTE_TASK_NORMAL".equalsIgnoreCase(operation.path("operation_type").asText())&&Set.of("AGREE","APPROVE").contains(operation.path("operation_result").asText().toUpperCase(Locale.ROOT))&&date(text(operation.path("date")))!=null) {
                var proof=new LinkedHashMap<String,Object>();proof.put("approverUserId",EXECUTION_APPROVER_USER_ID);proof.put("approverName","周汉琴");proof.put("approvedAt",text(operation.path("date")));proof.put("evidenceSource","operation");proof.put("operationKey",MoldApplicationMedia.hash(operation.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));return proof;
            }
        }
        return null;
    }
    public static Map<String,Object> revocation(JsonNode detail){
        var events=new TreeSet<String>();for(var task:detail.path("tasks"))if("COMPLETED".equals(task.path("task_status").asText())&&"REFUSE".equalsIgnoreCase(task.path("task_result").asText()))events.add("task:"+task.path("taskid").asText()+":"+task.path("userid").asText()+":"+task.path("finish_time").asText());
        for(var operation:detail.path("operation_records"))if("EXECUTE_TASK_NORMAL".equals(operation.path("operation_type").asText())&&"REFUSE".equalsIgnoreCase(operation.path("operation_result").asText()))events.add("operation:"+operation.path("userid").asText()+":"+operation.path("date").asText());
        boolean terminated="TERMINATED".equals(detail.path("status").asText()),refused="refuse".equalsIgnoreCase(detail.path("result").asText())||!events.isEmpty();if(!terminated&&!refused)return null;
        var result=new LinkedHashMap<String,Object>();result.put("state",terminated?"terminated":"rejected");result.put("reason",terminated?"钉钉申请已撤回或终止":"后续审批已拒绝");String seed=(terminated?"TERMINATED:"+detail.path("finish_time").asText():"REFUSE")+":"+String.join(";",events);result.put("key",MoldApplicationMedia.hash(seed.getBytes(java.nio.charset.StandardCharsets.UTF_8)));return result;
    }
    public static boolean granted(JsonNode detail) {
        return approvalEvidence(detail).granted();
    }
    public static List<Map<String,Object>> controls(JsonNode schema,ObjectMapper json) {
        List<Map<String,Object>> list=new ArrayList<>();
        for(var item:schema.path("schemaContent").path("items")) {
            var props=item.path("props");String id=props.path("id").asText(props.path("componentId").asText());
            String key=COMPONENTS.entrySet().stream().filter(e->e.getValue().equals(id)).map(Map.Entry::getKey).findFirst().orElse(null);
            if(key==null)continue;
            List<String> options=new ArrayList<>();for(var option:props.path("options")) {
                try{JsonNode n=option.isTextual()?json.readTree(option.asText()):option;options.add(n.path("value").asText());}
                catch(Exception ignored){}
            }
            var v=new LinkedHashMap<String,Object>();v.put("key",key);v.put("id",id);v.put("label",props.path("label").asText());
            v.put("type",item.path("componentName").asText());v.put("required",props.path("required").asBoolean());v.put("options",options);list.add(v);
        }
        return list;
    }
    public static BusinessException invalid(String message){return new BusinessException(40001,message);}
}
