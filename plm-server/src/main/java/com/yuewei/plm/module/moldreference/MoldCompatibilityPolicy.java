package com.yuewei.plm.module.moldreference;

import java.util.*;
import com.yuewei.plm.common.util.ProductBusinessCodeGenerator;

/** Shared physical facts and engineering fit gate for coverage, stock and code reuse. */
public final class MoldCompatibilityPolicy {
    private MoldCompatibilityPolicy() {}
    public static String confirmedSql(String prefix) {
        return "("+prefix+".physical_status='confirmed' or ("+prefix+".physical_status is null and ("+prefix+".factory_arrival_date is not null or ("+prefix+".acceptance_status='passed' and "+prefix+".acceptance_date is not null))))";
    }
    public static String partState(Map<String,Object> row,String prefix) {
        if("confirmed".equals(row.get(prefix+"PhysicalStatus"))||(row.get(prefix+"PhysicalStatus")==null&&((row.get(prefix+"Arrival")!=null||row.get(prefix+"FactoryArrivalDate")!=null)||("passed".equals(row.get(prefix+"AcceptanceStatus"))&&row.get(prefix+"AcceptanceDate")!=null))))return "confirmed";
        if("making".equals(row.get(prefix+"PhysicalStatus"))||(row.get(prefix+"PhysicalStatus")==null&&"making".equals(row.get(prefix+"Status"))))return "making";
        return "unknown";
    }
    public static boolean reusable(Map<String,Object> row) {
        boolean active="enabled".equals(row.get("phoneStatus"))&&"enabled".equals(row.get("brandStatus"))&&row.get("codeItemId")!=null&&"enabled".equals(row.get("codeStatus"))&&Objects.equals(row.get("codeDeletedFlag"),0);
        return row.get("moldSetId")!=null&&active&&"confirmed".equals(partState(row,"pc"))&&"confirmed".equals(partState(row,"tpu"))
            &&!"failed".equals(row.get("pcAcceptanceStatus"))&&!"failed".equals(row.get("tpuAcceptanceStatus"))
            &&Boolean.TRUE.equals(row.get("compatibilityConfirmed"))&&Boolean.TRUE.equals(row.get("pcConfirmed"))&&Boolean.TRUE.equals(row.get("tpuConfirmed"))
            &&!Objects.toString(row.get("pcEvidence"),"").isBlank()&&!Objects.toString(row.get("tpuEvidence"),"").isBlank()
            &&ProductBusinessCodeGenerator.isMoldCodeForPart(Objects.toString(row.get("pcCode"),""),"PC")&&ProductBusinessCodeGenerator.isMoldCodeForPart(Objects.toString(row.get("tpuCode"),""),"TPU");
    }
    /** Explains the same gate as reusable(); a historical code warning never asks users to recode real tooling. */
    public static List<Map<String,Object>> reviewReasons(Map<String,Object> row) {
        var reasons=new ArrayList<Map<String,Object>>();
        if(row.get("moldSetId")==null) {
            long count=row.get("registeredSets") instanceof Number n?n.longValue():0;
            reasons.add(reason("applicable_set_missing",count>0?"已登记"+count+"套，尚未选择本手机适用的具体套":"本档案尚未登记实物套；已有实物可登记，新制作须发起申请",count>0?"select_set":"register_existing","compatiblePhones"));
            return reasons;
        }
        for(String part:List.of("pc","tpu")) {
            String label=part.toUpperCase(Locale.ROOT),state=partState(row,part),code=Objects.toString(row.get(part+"Code"),"");
            if(!"confirmed".equals(state))reasons.add(reason(part+"_physical_unconfirmed",state.equals("making")?label+"仍在制作，完成后需清点实物":label+"实物状态尚未核对",state.equals("making")?"view_schedule":"confirm_physical",part+"PhysicalStatus"));
            if(!Boolean.TRUE.equals(row.get(part+"Confirmed")))reasons.add(reason(part+"_fit_unconfirmed",label+"与本手机的本套适配尚未确认","confirm_fit","compatiblePhones"));
            else if(Objects.toString(row.get(part+"Evidence"),"").isBlank())reasons.add(reason(part+"_fit_evidence_missing",label+"适配已勾选，但缺少本套核对依据","complete_fit_evidence","compatiblePhones"));
            if(code.isBlank())reasons.add(reason(part+"_code_missing",label+"完整原刻码未登记","complete_code",part+"OriginalMoldCode"));
            else if(!ProductBusinessCodeGenerator.isCompleteMoldCode(code))reasons.add(reason(part+"_historical_code",label+"保留历史非标准刻码；不能据此自动复用，请核对来源","review_historical_code",part+"OriginalMoldCode"));
            else if(!ProductBusinessCodeGenerator.isMoldCodeForPart(code,label))reasons.add(reason(part+"_material_mismatch",label+"原刻码材质段不符；TPU=10、PC=20，原码保留待核对","review_historical_code",part+"OriginalMoldCode"));
            if("failed".equals(row.get(part+"AcceptanceStatus")))reasons.add(reason(part+"_acceptance_failed",label+"验收明确不通过，实物存在但当前不可复用","review_acceptance",part+"AcceptanceStatus"));
        }
        if(!"enabled".equals(row.get("phoneStatus"))||!"enabled".equals(row.get("brandStatus")))reasons.add(reason("phone_reference_disabled","标准手机或品牌已停用；历史关系保留","maintain_reference","phoneModelId"));
        if(row.get("codeItemId")==null)reasons.add(reason("phone_code_unassigned","标准手机编码待分配；不能自动按原模具码猜码","maintain_reference","codeItemId"));
        else if(!"enabled".equals(row.get("codeStatus"))||!Objects.equals(row.get("codeDeletedFlag"),0))reasons.add(reason("phone_code_disabled","原手机编码已停用或删除；需要工程核对原编码引用","maintain_reference","codeItemId"));
        return List.copyOf(reasons);
    }
    public static Map<String,Object> reason(String code,String message,String action,String field) {
        return Map.of("code",code,"message",message,"action",action,"field",field);
    }
}
