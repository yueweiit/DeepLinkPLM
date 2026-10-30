package com.yuewei.plm.module.moldsales;

import com.fasterxml.jackson.databind.JsonNode;
import com.yuewei.plm.common.util.ProductBusinessCodeGenerator;
import java.math.*;
import java.util.*;
import java.util.regex.Pattern;

/** CRM finished-item facts and net valid-order quantity basis. */
public final class MoldSalesRules {
    private MoldSalesRules() {}
    private static final Pattern FINISHED=Pattern.compile("^"+ProductBusinessCodeGenerator.PRODUCT_STATE_CODE_REGEX+"$");
    private static final Pattern HISTORICAL_CANCELLATION=Pattern.compile("cancel|anulad|取消|作废|\\bvoid\\b",Pattern.CASE_INSENSITIVE);
    public static final String QUANTITY_POLICY="registered_non_cancelled_orders_v2";
    public record Identity(String style,String phone,String eligibility,BigDecimal quantity) {}
    /** 4030 is assembled stock; other final steps come from the existing product/SKU catalogue. */
    public static String finishedSql(Set<String> profiles){
        return finishedSql(profiles,"item_code");
    }
    private static String finishedSql(Set<String> profiles,String code){
        var values=profiles.stream().sorted().toList();
        if(values.stream().anyMatch(v->!v.matches("[A-Z]{2}[0-9]{4}")))throw new IllegalArgumentException("Invalid final-step profile");
        return "(substring("+code+",4,4)='4030'"+(values.isEmpty()?"":" or substring("+code+",2,6) in ('"+String.join("','",values)+"')")+")";
    }
    /** Re-evaluate cached pre-policy rows without rewriting the source snapshot. */
    public static String effectiveEligibilitySql(Set<String> profiles){
        return "case when eligibility in ('eligible','not_finished_stage','unconfirmed_order') then case when coalesce(source_data->>'deleted','0') not in ('0','') then 'deleted' when order_status='cancelled' then 'cancelled' when quantity<=0 or quantity<>trunc(quantity) then 'quantity_review' when (coalesce(source_data->>'seriesCode','')<>'' and left(source_data->>'seriesCode',3)<>'N'||style_code) or (coalesce(source_data->>'phoneCode','')<>'' and source_data->>'phoneCode'<>phone_code) then 'identity_conflict' when "+finishedSql(profiles)+" then 'eligible' else 'not_finished_stage' end else eligibility end";
    }
    public static String eligibleSql(Set<String> profiles){return "("+effectiveEligibilitySql(profiles)+")='eligible'";}
    /** Sealed archive validation is not repeated by decoding every large raw JSON on reads. */
    public static String factEligibilitySql(Set<String> profiles){return "case when source_kind='eccount' and eligibility in ('eligible','not_finished_stage') then case when "+finishedSql(profiles,"upper(btrim(item_code))")+" then 'eligible' else 'not_finished_stage' end else "+effectiveEligibilitySql(profiles)+" end";}
    public static String factEligibleSql(Set<String> profiles){return "("+factEligibilitySql(profiles)+")='eligible'";}
    /** Delivery is a subset of the same basis; partial-order quantities are not unfulfilled-unit counts. */
    public static String pendingDeliverySql(){return "order_status not in ('closed','fully_arrived','partial_shipped','partial_arrived')";}
    public static String partialDeliverySql(){return "order_status in ('partial_shipped','partial_arrived')";}
    public static BigDecimal maximum(BigDecimal a,BigDecimal b){return a==null?b:b==null?a:a.max(b);}
    /** Each valid ordered finished case establishes one PC and one TPU. Existing totals overlap this basis. */
    public static Map<String,Object> productionTotals(BigDecimal orderQuantity,BigDecimal pc,BigDecimal tpu,BigDecimal finished){
        var out=new LinkedHashMap<String,Object>();out.put("orderQuantity",orderQuantity);
        out.put("manualPcTotal",pc);out.put("manualTpuTotal",tpu);out.put("manualFinishedTotal",finished);
        out.put("pcTotal",maximum(orderQuantity,pc));out.put("tpuTotal",maximum(orderQuantity,tpu));out.put("finishedTotal",maximum(orderQuantity,finished));
        return out;
    }
    public static Identity identity(JsonNode row){return identity(row,Set.of());}
    public static Identity identity(JsonNode row,Set<String> profiles){
        BigDecimal qty=new BigDecimal(row.path("quantity").asText());var code=FINISHED.matcher(row.path("code").asText().strip().toUpperCase(Locale.ROOT));
        if(!code.matches())return new Identity(null,null,"not_finished_code",qty);
        String style=code.group(1),phone=code.group(2),state=row.path("status").asText();
        String series=row.path("seriesCode").asText("");String sourcePhone=row.path("phoneCode").asText("");
        String result=row.path("deleted").asInt()!=0?"deleted":state.equals("cancelled")?"cancelled":qty.signum()<=0||qty.stripTrailingZeros().scale()>0?"quantity_review":!series.isBlank()&&!series.startsWith("N"+style)||!sourcePhone.isBlank()&&!sourcePhone.equals(phone)?"identity_conflict":"eligible";
        if(result.equals("eligible")&&!row.path("code").asText().strip().substring(3,7).equals("4030")&&!profiles.contains(row.path("code").asText().strip().toUpperCase(Locale.ROOT).substring(1,7)))result="not_finished_stage";
        return new Identity(style,phone,result,qty);
    }
    /** Historical evidence is retained even when it cannot establish a case count. */
    public static Identity archiveIdentity(JsonNode row,Set<String> profiles){
        BigDecimal qty=row.path("quantity").isNull()||row.path("quantity").isMissingNode()?null:new BigDecimal(row.path("quantity").asText());
        var code=FINISHED.matcher(row.path("code").asText("").strip().toUpperCase(Locale.ROOT));
        if(!code.matches())return new Identity(null,null,"not_finished_code",qty);
        String style=code.group(1),phone=code.group(2),status=row.path("statuses").asText("").strip();
        String reason;
        if(qty==null)reason="missing_quantity";
        else if(qty.signum()<=0||qty.stripTrailingZeros().scale()>0)reason="quantity_review";
        else if(HISTORICAL_CANCELLATION.matcher(status).find())reason="cancelled";
        else if(row.path("cancellationNote").asBoolean())reason="cancellation_review";
        else if(!Set.of("完成","进行中").contains(status))reason="status_review";
        else if(!pieceUnit(row.path("unit").asText("")))reason="unit_review";
        else if(!row.path("code").asText().strip().substring(3,7).equals("4030")&&!profiles.contains(row.path("code").asText().strip().toUpperCase(Locale.ROOT).substring(1,7)))reason="not_finished_stage";
        else reason="eligible";
        return new Identity(style,phone,reason,qty);
    }
    static boolean pieceUnit(String unit){return Set.of("pieza","piezas","pcs","件","个","个 pieza","个pieza").contains(unit.strip().replaceAll("\\s+"," ").toLowerCase(Locale.ROOT));}
    public static Map<String,Object> costs(JsonNode basis,BigDecimal quantity,int sets){
        return costs(basis,quantity,sets,null);
    }
    /** Manual amounts are already the entire group's total, never a per-set or per-model amount. */
    public static Map<String,Object> costs(JsonNode basis,BigDecimal quantity,int sets,Map<String,Object> actual){
        if(basis==null&&actual==null)return null;
        var out=new LinkedHashMap<String,Object>();var unknown=new LinkedHashSet<String>();if(basis!=null)for(var field:basis.path("unknownFields"))unknown.add(field.asText());
        var displayed=basis==null?null:basis.deepCopy();if(displayed instanceof com.fasterxml.jackson.databind.node.ObjectNode object)for(String field:unknown)object.putNull(field);
        out.put("basis",displayed);out.put("costSets",sets);out.put("unknownFields",unknown);
        if(basis!=null){out.put("sourceKind",basis.path("sourceKind").asText("cost_sheet"));out.put("confirmation",basis.path("confirmation"));out.put("referenceOnly",basis.path("referenceOnly").asBoolean());}
        var budgetTotal=basis==null?null:new BigDecimal(basis.path("landedTotal").asText()).multiply(BigDecimal.valueOf(sets));
        var total=actual==null?budgetTotal:new BigDecimal(actual.get("totalCost").toString());
        var contribution=basis==null||unknown.contains("referenceSellingPrice")||unknown.contains("referenceUnitProductionCost")?null:new BigDecimal(basis.path("referenceSellingPrice").asText()).subtract(new BigDecimal(basis.path("referenceUnitProductionCost").asText()));
        out.put("source",actual==null?"budget":"actual");out.put("actual",actual);out.put("budgetTotalCost",budgetTotal);
        out.put("currency",actual==null?basis.path("currency").asText():"CNY");out.put("totalCost",total);
        out.put("perUnitCost",quantity!=null&&quantity.signum()>0?total.divide(quantity,4,RoundingMode.HALF_UP):null);
        boolean reference=contribution!=null&&contribution.signum()>0;
        out.put("breakEvenUnits",reference?total.divide(contribution,0,RoundingMode.CEILING):null);
        out.put("remainingUnits",reference&&quantity!=null?total.divide(contribution,0,RoundingMode.CEILING).subtract(quantity).max(BigDecimal.ZERO):null);
        out.put("referenceCoverage",reference&&quantity!=null&&total.signum()>0?quantity.multiply(contribution).divide(total,4,RoundingMode.HALF_UP):null);
        return out;
    }
}
