package com.yuewei.plm.module.moldsales;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuewei.plm.common.exception.BusinessException;
import com.yuewei.plm.common.security.CurrentUser;
import com.yuewei.plm.module.operationlog.service.OperationLogCreateCommand;
import com.yuewei.plm.module.operationlog.service.OperationLogService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** One write path for manually maintained analytical costs. Never creates financial ledger facts. */
final class MoldActualCostStore {
    record Amounts(BigDecimal manufacturingCost,BigDecimal freightCost,BigDecimal customsCost,BigDecimal otherCost) {
        Map<String,Object> values(){var out=new LinkedHashMap<String,Object>();out.put("manufacturingCost",manufacturingCost);out.put("freightCost",freightCost);out.put("customsCost",customsCost);out.put("otherCost",otherCost);out.put("totalCost",manufacturingCost.add(freightCost).add(customsCost).add(otherCost));out.put("currency","CNY");return out;}
    }
    record Update(Long version,String groupToken,Amounts amounts,String reason) {}
    record Match(Map<String,Object> row,boolean conflict) {}
    private final JdbcTemplate jdbc;private final ObjectMapper json;private final OperationLogService audit;
    MoldActualCostStore(JdbcTemplate jdbc,ObjectMapper json,OperationLogService audit){this.jdbc=jdbc;this.json=json;this.audit=audit;}
    private static BusinessException invalid(String message){return new BusinessException(40001,message);}
    static Amounts validate(Amounts amounts){
        if(amounts==null)throw invalid("请填写模具制作、运费、关税和其他费用，没有费用请填0");
        return new Amounts(money(amounts.manufacturingCost()),money(amounts.freightCost()),money(amounts.customsCost()),money(amounts.otherCost()));
    }
    private static BigDecimal money(BigDecimal value){
        if(value==null||value.signum()<0||value.stripTrailingZeros().scale()>2||value.compareTo(new BigDecimal("9999999999999999.99"))>0)throw invalid("四项费用均须明确填写非负金额，最多保留两位小数");
        return value.setScale(2);
    }
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}}
    private JsonNode node(Object value){try{return json.readTree(value.toString());}catch(Exception e){throw new IllegalStateException(e);}}
    @SuppressWarnings("unchecked") private static SortedSet<Long> members(Map<String,Object> group){return new TreeSet<>((Collection<Long>)group.get("itemIds"));}
    private static String digest(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    static String key(Map<String,Object> group){return digest(Objects.toString(group.get("styleCode"),"")+"|"+group.get("productLineId")+"|"+members(group));}
    static String token(Map<String,Object> group){return digest(key(group)+"|"+group.get("groupId")+"|"+new TreeSet<>((Collection<?>)group.get("phoneCodes"))+"|"+group.get("physicalSets"));}
    List<Map<String,Object>> rows(){return jdbc.queryForList("select cost_id as \"costId\",group_key as \"groupKey\",group_token as \"groupToken\",array_to_json(item_ids)::text as members,manufacturing_cost as \"manufacturingCost\",freight_cost as \"freightCost\",customs_cost as \"customsCost\",other_cost as \"otherCost\",total_cost as \"totalCost\",currency,version,updated_by as \"updatedBy\",updated_at as \"updatedAt\" from plm_mold_group_actual_cost order by cost_id");}
    Match find(Map<String,Object> group,List<Map<String,Object>> rows){
        var ids=members(group);var overlapping=new ArrayList<Map<String,Object>>();
        for(var row:rows){boolean overlaps=false;for(var member:node(row.get("members")))if(ids.contains(member.asLong())){overlaps=true;break;}if(overlaps)overlapping.add(row);}
        if(overlapping.isEmpty())return new Match(null,false);
        if(overlapping.size()!=1)return new Match(null,true);
        var row=overlapping.get(0);boolean exact=key(group).equals(row.get("groupKey"))&&token(group).equals(row.get("groupToken"));
        return new Match(exact?row:null,!exact);
    }
    Map<String,Object> actual(Map<String,Object> row){
        if(row==null)return null;var out=new LinkedHashMap<String,Object>();
        for(String field:List.of("manufacturingCost","freightCost","customsCost","otherCost","totalCost","currency","updatedBy","updatedAt"))out.put(field,row.get(field));return out;
    }
    List<Map<String,Object>> history(Map<String,Object> row){
        if(row==null)return List.of();return jdbc.queryForList("select version,amounts::text,reason,actor_name as \"actorName\",changed_at as \"changedAt\" from plm_mold_group_actual_cost_history where cost_id=? order by version desc",row.get("costId")).stream().peek(r->r.put("amounts",node(r.get("amounts")))).toList();
    }
    void save(Map<String,Object> group,Update update,CurrentUser actor){
        if(update==null||update.version()==null||update.version()<0)throw invalid("成本版本无效，请重新打开成本维护");
        if(!token(group).equals(update.groupToken()))throw invalid("共模组或型号范围已变化，请刷新后重新核对成本");
        var amounts=validate(update.amounts());String reason=update.reason()==null?"":update.reason().trim();if(reason.isEmpty()||reason.length()>2000)throw invalid("请填写本次成本维护原因（最多2000字）");
        // Serialises competing first inserts as well as updates. Caller holds grouping relation locks.
        jdbc.execute("select pg_advisory_xact_lock(202610261300)");
        var match=find(group,rows());if(match.conflict())throw invalid("共模归属已变化或存在多份实际成本，请先核对原成本记录，不能自动合并");
        var row=match.row();long current=row==null?0:((Number)row.get("version")).longValue();
        if(current!=update.version())throw invalid("成本已被其他人修改，请刷新后核对，原值未覆盖");
        var values=amounts.values();if(row!=null&&List.of("manufacturingCost","freightCost","customsCost","otherCost").stream().allMatch(k->((BigDecimal)row.get(k)).compareTo((BigDecimal)values.get(k))==0))return;
        long next=current+1;Long id;
        if(row==null)id=jdbc.queryForObject("insert into plm_mold_group_actual_cost(group_key,group_token,item_ids,manufacturing_cost,freight_cost,customs_cost,other_cost,created_by,updated_by) values(?,?,?::bigint[],?,?,?,?,?,?) returning cost_id",Long.class,key(group),token(group),"{"+String.join(",",members(group).stream().map(Object::toString).toList())+"}",amounts.manufacturingCost(),amounts.freightCost(),amounts.customsCost(),amounts.otherCost(),actor.username(),actor.username());
        else {id=((Number)row.get("costId")).longValue();jdbc.update("update plm_mold_group_actual_cost set manufacturing_cost=?,freight_cost=?,customs_cost=?,other_cost=?,version=?,updated_by=?,updated_at=current_timestamp where cost_id=?",amounts.manufacturingCost(),amounts.freightCost(),amounts.customsCost(),amounts.otherCost(),next,actor.username(),id);}
        var stored=find(group,rows()).row();var actual=actual(stored);
        jdbc.update("insert into plm_mold_group_actual_cost_history(cost_id,version,amounts,reason,actor_name) values(?,?,?::jsonb,?,?)",id,next,encode(actual),reason,actor.username());
        var detail=new LinkedHashMap<String,Object>();detail.put("itemIds",members(group));detail.put("groupToken",token(group));detail.put("before",row==null?null:actual(row));detail.put("after",actual);detail.put("version",next);detail.put("reason",reason);detail.put("financialLedgerFact",false);
        audit.logRequired(OperationLogCreateCommand.builder().action("MOLD_GROUP_ACTUAL_COST_UPDATE").businessType("MOLD_SALES").businessId(id.toString()).businessName(Objects.toString(group.get("styleName"),"")).detailJson(encode(detail)).build(),actor);
    }
}
