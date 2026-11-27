package com.yuewei.plm.module.product.identity;

import com.yuewei.plm.common.exception.BusinessException;
import com.yuewei.plm.module.pilot.PilotAccessService;
import com.yuewei.plm.module.project.development.DevelopmentWorkflowStore;
import com.yuewei.plm.repository.entity.Product;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Project-scoped technical identity; never fills the root's formal fields before shipment. */
@Service @RequiredArgsConstructor
public class ProductCodeReservationService {
    private final JdbcTemplate jdbc;
    private final PilotAccessService access;
    private final DevelopmentWorkflowStore store;
    @Value("${plm.research.product-code-reservation.enabled:false}") private boolean enabled;

    public boolean enabledForNewProjects(){return enabled;}
    public boolean canEnroll(long id){
        return enabled&&Boolean.TRUE.equals(jdbc.queryForObject("select plm_can_enroll_product_identity(?)",Boolean.class,id));
    }
    /** Preview only; the legacy project is enrolled atomically with its first successful proposal. */
    public Map<String,Object> proposalView(long id){
        var managed=view(id);if(!managed.isEmpty()||!canEnroll(id))return managed;
        return Map.of("mode","legacy","state","unassigned","productCode","","specificCode","","moldPrefix","","pinned",false);
    }
    private void enrollForProposal(long id){
        jdbc.queryForList("select product_id from plm_product where product_id=? for update",id);
        if(isManaged(id))return;
        require(canEnroll(id),"该项目已有正式编码、业务编码引用或不在可维护阶段，请沿用原编码流程");
        store.event(id,"product_code_enrolled",Map.of("previousMode","legacy","mode","reserved_v1"),null,access.actor().username());
        jdbc.update("update plm_product set product_identity_mode='reserved_v1' where product_id=?",id);
    }
    public boolean isManaged(long id){return Boolean.TRUE.equals(jdbc.queryForObject("select product_identity_mode='reserved_v1' from plm_product where product_id=?",Boolean.class,id));}
    public static boolean isManaged(Product product){return product!=null&&"reserved_v1".equals(product.getProductIdentityMode());}
    public static String normalize(String value){
        String code=Objects.toString(value,"").strip().toUpperCase(Locale.ROOT);
        require(code.matches("N[A-Z]{2}"),"产品编码须为 N 加两位英文字母，例如 NAB");return code.substring(1);
    }
    private void lockCode(String code){jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(?,0))",Object.class,"product-specific:"+code);}
    private Map<String,Object> lockProject(long id){return lockProject(id,false);}
    private Map<String,Object> lockProject(long id,boolean technical){
        var p=jdbc.queryForMap("select * from plm_product where product_id=? for update",id);
        require("reserved_v1".equals(p.get("product_identity_mode")),"该项目沿用原产品编码维护方式");
        require(Objects.equals(p.get("deleted_flag"),0)&&!"archived".equals(p.get("status"))&&(technical||!"released".equals(p.get("status")))
            &&!Set.of("frozen","abandoned").contains(Objects.toString(p.get("lock_status"),"")),"已发布、停止或冻结的项目不能拟定或使用编码");
        return p;
    }
    private Map<String,Object> current(long id){
        var rows=jdbc.queryForList("select * from plm_product_code_reservation where product_id=? and state in ('proposed','confirmed','invalid_held') order by reservation_id desc",id);
        return rows.isEmpty()?Map.of():rows.get(0);
    }
    public boolean occupied(String code,Long owner){
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            select exists(select 1 from plm_product_code_reservation where specific_code=?
                and state in ('proposed','confirmed','invalid_held') and product_id is distinct from ?::bigint)
              or exists(select 1 from plm_product_identity_evidence where code=? and owner_id is distinct from ?::bigint)
            """,Boolean.class,code,owner,code,owner));
    }
    /** Legacy candidate checks must run after taking the same code lock. */
    public void requireFormalCodeAvailable(String code,Long owner){
        lockCode(code);
        require(!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from plm_product_code_reservation where specific_code=? and state in ('proposed','confirmed','invalid_held') and product_id is distinct from ?::bigint)",Boolean.class,code,owner)),"产品特定编码已被拟定或正式编码占用");
    }
    public Map<String,Object> availability(long id,String input){
        access.requireDomesticView();access.requirePermission("project:view");access.requireProduct(id);
        require(isManaged(id)||canEnroll(id),"该项目沿用原编码流程");String code=normalize(input);
        return Map.of("productCode","N"+code,"moldPrefix","M"+code,"available",!occupied(code,id));
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public Map<String,Object> propose(long id,String input,boolean reconfirm,String reason){
        access.requirePermission("product:write");access.requireEngineeringMaintenance();
        access.requireProduct(id);
        normalize(input);
        if(!isManaged(id)){
            require(!reconfirm,"该项目尚未拟定编码，请先保存拟定编码");
            enrollForProposal(id);
        }
        var p=lockProject(id);require(((Number)p.get("current_step_no")).intValue()>=2,"请在画3D产品图步骤拟定产品编码");
        String code=normalize(input);var old=current(id);
        var codes=new TreeSet<String>();codes.add(code);if(!old.isEmpty())codes.add(old.get("specific_code").toString());codes.forEach(this::lockCode);
        require(!"confirmed".equals(old.get("state")),"正式产品编码已确认，不能重新拟定");
        if("invalid_held".equals(old.get("state"))){
            require(reconfirm&&code.equals(old.get("specific_code")),"已有业务引用，请明确重新确认原拟定码，不能换码");
            require(!reason.isBlank(),"重新确认原码请填写原因");
        }else if(reconfirm)throw new BusinessException(40001,"当前编码无需重新确认，请刷新项目");
        if(!old.isEmpty()&&!code.equals(old.get("specific_code")))require(old.get("pinned_at")==null,"编码已有开模或SKU引用，不能换码");
        require(!occupied(code,id),"产品编码已被占用，请重新拟定");String actor=access.actor().username();
        if(code.equals(old.get("specific_code"))){
            jdbc.update("update plm_product_code_reservation set state='proposed',invalidated_at=null,reason=?,updated_by=?,updated_at=now() where reservation_id=?",reason,actor,old.get("reservation_id"));
        }else{
            if(!old.isEmpty())jdbc.update("update plm_product_code_reservation set state='released',invalidated_at=now(),reason='重新拟定',updated_by=?,updated_at=now() where reservation_id=?",actor,old.get("reservation_id"));
            jdbc.update("insert into plm_product_code_reservation(product_id,specific_code,state,created_by,updated_by,reason) values(?,?,'proposed',?,?,?)",id,code,actor,actor,reason);
        }
        var result=new LinkedHashMap<>(view(id));result.put("previousProductCode",old.isEmpty()?"":"N"+old.get("specific_code"));return result;
    }
    public void requireReady(long id){
        if(!isManaged(id))return;
        var r=current(id);require(Set.of("proposed","confirmed").contains(Objects.toString(r.get("state"),"")),"请先保存有效的拟定产品编码；失效且保留占号的编码须重新确认");
    }
    /** Read-only preview, called only by integrated research consumers. */
    public String researchCode(long id){requireReady(id);return Objects.toString(current(id).get("specific_code"),"");}
    @Transactional(propagation=Propagation.MANDATORY)
    public String pinForResearch(long id,String purpose){
        access.requireProduct(id);lockProject(id,true);requireReady(id);var r=current(id);String code=r.get("specific_code").toString();lockCode(code);
        if(r.get("pinned_at")!=null)return code;
        jdbc.update("update plm_product_code_reservation set pinned_at=coalesce(pinned_at,now()),updated_at=now() where reservation_id=?",r.get("reservation_id"));
        store.event(id,"product_code_reference",Map.of("reservationId",r.get("reservation_id"),"purpose",purpose),null,access.actor().username());return code;
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void invalidate(long id,String reason){
        if(!isManaged(id))return;
        jdbc.queryForList("select product_id from plm_product where product_id=? for update",id);
        var r=current(id);if(r.isEmpty()||!"proposed".equals(r.get("state")))return;lockCode(r.get("specific_code").toString());
        boolean held=r.get("pinned_at")!=null||Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from plm_product_identity_evidence where code=?)",Boolean.class,r.get("specific_code")));
        String state=held?"invalid_held":"released";
        jdbc.update("update plm_product_code_reservation set state=?,invalidated_at=now(),reason=?,updated_by=?,updated_at=now() where reservation_id=?",state,reason,access.actor().username(),r.get("reservation_id"));
        store.event(id,"product_code_invalidated",Map.of("reservationId",r.get("reservation_id"),"state",state,"reason",reason),null,access.actor().username());
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void confirmForFinalShipment(Product p,String actor){
        if(!isManaged(p))return;
        require(Boolean.TRUE.equals(p.getTimelineCurrentConfirmed())&&"LAUNCH_SHIP".equals(p.getTimelineConfirmedNodeKey()),"编码只能在最终发模节点确认时转正");
        requireReady(p.getProductId());var r=current(p.getProductId());String code=r.get("specific_code").toString();lockCode(code);
        require(!occupied(code,p.getProductId()),"编码身份存在冲突，请核对后再完成发模");
        if(!"confirmed".equals(r.get("state"))){
            require("proposed".equals(r.get("state")),"拟定编码已失效，请重新确认");
            jdbc.update("update plm_product_code_reservation set state='confirmed',confirmed_at=now(),updated_at=now(),updated_by=? where reservation_id=?",actor,r.get("reservation_id"));
            store.event(p.getProductId(),"product_code_confirmed",Map.of("reservationId",r.get("reservation_id"),"productCode","N"+code,"nodeKey","LAUNCH_SHIP"),null,actor);
        }
        p.setProductSpecificCode(code);p.setProductCodePrefix(code);p.setMoldCodePrefix("M"+code);
    }
    public void requireConfirmedFinalShipment(long id){
        if(!isManaged(id))return;
        require("confirmed".equals(current(id).get("state")),"请先完成本项目发模并确认产品编码，再形成正式包或首次上市");
    }
    public Map<String,Object> view(long id){return views(List.of(id)).getOrDefault(id,Map.of());}
    public Map<Long,Map<String,Object>> views(Collection<Long> ids){
        if(ids.isEmpty())return Map.of();String placeholders=String.join(",",Collections.nCopies(ids.size(),"?"));
        var rows=jdbc.queryForList("select p.product_id as identity_product_id,r.* from plm_product p left join lateral (select * from plm_product_code_reservation where product_id=p.product_id order by reservation_id desc limit 1) r on true where p.product_identity_mode='reserved_v1' and p.product_id in ("+placeholders+")",ids.toArray());
        var result=new LinkedHashMap<Long,Map<String,Object>>();
        for(var row:rows){var value=new LinkedHashMap<String,Object>();String code=Objects.toString(row.get("specific_code"),"");
            value.put("mode","reserved_v1");value.put("state",Objects.toString(row.get("state"),"unassigned"));
            value.put("productCode",code.isBlank()?"":"N"+code);value.put("specificCode",code);value.put("moldPrefix",code.isBlank()?"":"M"+code);
            value.put("pinned",row.get("pinned_at")!=null);value.put("reservationId",row.get("reservation_id"));value.put("confirmedAt",row.get("confirmed_at"));
            value.put("reason",row.get("reason"));result.put(((Number)row.get("identity_product_id")).longValue(),value);
        }return result;
    }
    private static void require(boolean condition,String message){if(!condition)throw new BusinessException(40001,message);}
}
