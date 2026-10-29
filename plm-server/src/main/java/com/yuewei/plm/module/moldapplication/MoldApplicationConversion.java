package com.yuewei.plm.module.moldapplication;

import com.yuewei.plm.module.project.ProjectRegistration;

import com.fasterxml.jackson.databind.JsonNode;
import com.yuewei.plm.common.exception.BusinessException;
import com.yuewei.plm.common.util.ProductCodeGenerator;
import com.yuewei.plm.module.workflow.service.WorkflowTemplateService;
import com.yuewei.plm.module.moldreference.MoldCompatibilityPolicy;
import com.yuewei.plm.repository.ProductRepository;
import com.yuewei.plm.repository.entity.Product;
import jakarta.servlet.http.HttpServletRequest;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static com.yuewei.plm.module.moldapplication.MoldApplicationForms.*;

@Service @RequiredArgsConstructor
public class MoldApplicationConversion {
    private final MoldApplicationRepository repo;private final MoldApplicationService service;
    private final MoldApplicationCodes codes;private final ProductRepository products;
    private final ProductCodeGenerator productCodes;private final WorkflowTemplateService templates;
    private final com.yuewei.plm.module.pilot.PilotAccessService access;private final PlatformTransactionManager transactions;
    public record Convert(long version,String mode,Long projectId,Long itemId,Long moldSetId,boolean confirmCompatibility,Long phoneModelId,boolean reuseExisting,String compatibilityDigest) {
        public Convert(long version,String mode,Long projectId,Long itemId,Long moldSetId,boolean confirmCompatibility) {this(version,mode,projectId,itemId,moldSetId,confirmCompatibility,null,false,null);}
    }
    public record AssociationPreview(Long phoneModelId,Long productLineId) {}
    public record Association(long version,long phoneModelId,long productLineId,Long projectId,Long itemId,Long moldSetId,String reason,boolean confirmPhoneCorrection) {
        public Association(long version,long phone,long line,long project,long item,long set,String reason,boolean correct){this(version,phone,line,Long.valueOf(project),Long.valueOf(item),Long.valueOf(set),reason,correct);}
    }
    private Map<String,Object> historical(long id,boolean lock){
        service.convertPermission();var row=service.require(id,lock);
        if(!"dingtalk".equals(row.get("sourceSystem")))throw invalid("此入口仅核对历史申请；PLM新执行继续使用原建档流程");return row;
    }
    public Map<String,Object> associationPreview(long id,AssociationPreview selection){
        var row=historical(id,false);var lines=repo.jdbc().queryForList("select p.product_id as \"productId\",p.product_name as \"productName\" from plm_product p where p.product_type='product_line' and p.deleted_flag=0 and "+access.visibleSqlClause("product","p.product_id")+" order by p.product_name,p.product_id");
        Long line=selection.productLineId()==null?(Long)row.get("productLineId"):selection.productLineId();
        if(line==null)try{line=((Number)codes.config(text(row.get("tipo"))).get("productLineId")).longValue();}catch(BusinessException ignored){}
        Long phoneId=selection.phoneModelId()==null?(Long)row.get("phoneModelId"):selection.phoneModelId();Map<String,Object> phone=phoneId==null?null:codes.references().requireEnabledModels(List.of(phoneId)).get(phoneId);
        var projects=new ArrayList<Map<String,Object>>();var items=new ArrayList<Map<String,Object>>();var sets=new ArrayList<Map<String,Object>>();
        if(line!=null){
            final long selectedLine=line;if(lines.stream().noneMatch(value->Objects.equals(value.get("productId"),selectedLine)))throw invalid("所选产品线不存在或未获授权");
            projects.addAll(repo.jdbc().queryForList("select p.product_id as \"projectId\",p.product_name as \"productName\",p.model,p.phone_model_code as \"phoneModelCode\",p.phone_model_member_id as \"phoneModelId\",ip.item_id as \"linkedItemId\" from plm_product p left join plm_collaboration_item_project ip on ip.project_id=p.product_id where "+ProjectRegistration.modelCandidateSql("p")+" and p.deleted_flag=0 and p.parent_product_id=? and "+access.visibleSqlClause("product","p.product_id")+" order by p.product_id desc",line));
            for(var project:projects){String problem=phone==null?"请先选择标准手机":associationProjectProblem(project,phone);project.put("blockedReason",problem);}
            items.addAll(repo.jdbc().queryForList("select i.item_id as \"itemId\",coalesce(i.mold_name,i.style_name) as \"moldName\",i.compatible_models as \"compatibleModels\",coalesce(nullif(i.engraved_code,''),i.phone_model_code) as \"engravedCode\" from plm_collaboration_item i where i.deleted_flag=0 and i.product_line_id=? and "+access.visibleSqlClause("item","i.item_id")+" order by i.item_id desc",line));
            for(var project:projects)if(project.get("linkedItemId")!=null&&items.stream().noneMatch(item->Objects.equals(item.get("itemId"),project.get("linkedItemId")))){project.put("blockedReason","该项目已关联未获授权的模具主档，请联系工程或管理员核对");project.remove("linkedItemId");}
            sets.addAll(repo.jdbc().queryForList(associationSetSql()+" where i.product_line_id=? and i.deleted_flag=0 and s.deleted_flag=0 and "+access.visibleSqlClause("item","i.item_id")+" and "+access.visibleSqlClause("mold","s.mold_set_id")+" order by s.item_id,s.set_no",line));
            for(var set:sets)set.put("blockedReason",hasPhysicalFacts(set)?null:"该套仍属拟制或缺少实物事实，请先在模具详情核对实物");
        }
        var result=new LinkedHashMap<String,Object>();result.put("application",service.detail(id));result.put("productLines",lines);result.put("selectedProductLineId",line);result.put("standardPhone",phone);result.put("projects",projects);result.put("items",items);result.put("sets",sets);return result;
    }
    private static String associationSetSql(){return """
        select s.mold_set_id as "moldSetId",s.item_id as "itemId",s.set_no as "setNo",s.set_name as "setName",s.opened_date as "openedDate",s.actual_ship_date as "actualShipDate",
          pc.physical_status as "pcPhysicalStatus",tpu.physical_status as "tpuPhysicalStatus",pc.status as "pcStatus",tpu.status as "tpuStatus",
          pc.factory_arrival_date as "pcArrival",tpu.factory_arrival_date as "tpuArrival",pc.acceptance_status as "pcAcceptanceStatus",tpu.acceptance_status as "tpuAcceptanceStatus",
          pc.acceptance_date as "pcAcceptanceDate",tpu.acceptance_date as "tpuAcceptanceDate",pc.original_mold_code as "pcCode",tpu.original_mold_code as "tpuCode"
        from plm_collaboration_mold_set s join plm_collaboration_item i on i.item_id=s.item_id
          left join plm_collaboration_mold_part pc on pc.mold_set_id=s.mold_set_id and pc.part_type='PC'
          left join plm_collaboration_mold_part tpu on tpu.mold_set_id=s.mold_set_id and tpu.part_type='TPU'
        """;}
    private static boolean hasPhysicalFacts(Map<String,Object> set){return !"unknown".equals(MoldCompatibilityPolicy.partState(set,"pc"))||!"unknown".equals(MoldCompatibilityPolicy.partState(set,"tpu"))||set.get("openedDate")!=null||set.get("actualShipDate")!=null;}
    private String associationProjectProblem(Map<String,Object> project,Map<String,Object> phone){
        Long identity=(Long)project.get("phoneModelId");long selected=((Number)phone.get("phoneModelId")).longValue();
        if(identity!=null)return identity==selected?null:"项目属于另一标准手机，请选择正确项目；不能按同码关联";
        if(phone.get("codeItemId")==null)return "该项目尚未关联标准手机且原编码不足，请先在历史手机关系核对确认项目身份";
        var original=new LinkedHashMap<String,Object>();original.put("phone_model_member_id",null);original.put("phone_model_code",project.get("phoneModelCode"));original.put("model",project.get("model"));
        try{requireProjectPhoneData(original,selected,((Number)phone.get("codeItemId")).longValue());return null;}catch(BusinessException failure){return failure.getMessage();}
    }
    /** Save every verified level independently. No physical stock, codes, costs or OA result is created. */
    public Map<String,Object> associateHistory(long id,Association input,HttpServletRequest request){
        var original=historical(id,false);
        if(input.version()<1||input.phoneModelId()<1||input.productLineId()<1)throw invalid("请选择标准手机和产品；项目、主档、套可分层补齐");
        if(text(input.reason())==null||input.reason().trim().length()>4000)throw invalid("请填写4000字以内的历史关联核对原因");
        if(input.moldSetId()!=null&&input.itemId()==null)throw invalid("关联具体套时须明确所属主档");
        var actor=access.actor();
        return new TransactionTemplate(transactions).execute(tx->{
            service.lockPhones((Long)original.get("phoneModelId"),input.phoneModelId());var row=historical(id,true);
            if(((Number)row.get("version")).longValue()!=input.version())throw new BusinessException(40901,"历史申请已更新，请刷新核对");
            if(row.get("phoneModelId")!=null&&!Objects.equals(row.get("phoneModelId"),input.phoneModelId())&&!input.confirmPhoneCorrection())throw invalid("历史申请已关联另一标准手机；更正须明确确认并保留原因");
            var phone=codes.references().requireEnabledModels(List.of(input.phoneModelId())).get(input.phoneModelId());access.requireProduct(input.productLineId());
            com.yuewei.plm.module.masterdata.ProductMoldLifecycleService.requireActive(repo.jdbc(),"product",input.productLineId());
            if(input.itemId()!=null)com.yuewei.plm.module.masterdata.ProductMoldLifecycleService.requireActive(repo.jdbc(),"item",input.itemId());
            if(input.moldSetId()!=null)com.yuewei.plm.module.masterdata.ProductMoldLifecycleService.requireActive(repo.jdbc(),"mold_set",input.moldSetId());
            if(input.itemId()!=null){access.requireItem(input.itemId());var items=repo.jdbc().queryForList("select product_line_id from plm_collaboration_item where item_id=? and deleted_flag=0 for update",input.itemId());if(items.size()!=1||!Objects.equals(items.get(0).get("product_line_id"),input.productLineId()))throw invalid("模具主档与所选产品不一致");}
            if(input.projectId()!=null){
                access.requireProduct(input.projectId());
                var projects=repo.jdbc().queryForList("select product_id as \"projectId\",parent_product_id as \"productLineId\",phone_model_member_id as \"phoneModelId\",phone_model_code as \"phoneModelCode\",model from plm_product p where product_id=? and "+ProjectRegistration.modelCandidateSql("p")+" and deleted_flag=0 for update",input.projectId());
                if(projects.size()!=1||!Objects.equals(projects.get(0).get("productLineId"),input.productLineId()))throw invalid("项目与所选产品不一致");
                String problem=associationProjectProblem(projects.get(0),phone);if(problem!=null)throw invalid(problem);
                var owners=repo.jdbc().queryForList("select item_id from plm_collaboration_item_project where project_id=?",Long.class,input.projectId());
                if(!owners.isEmpty()&&input.itemId()!=null&&!owners.get(0).equals(input.itemId()))throw invalid("项目已关联其他主档，不能自动转移");
            }
            if(input.moldSetId()!=null){access.requireMold(input.moldSetId());var sets=repo.jdbc().queryForList(associationSetSql()+" where s.mold_set_id=? and s.deleted_flag=0 and i.deleted_flag=0 for update of s",input.moldSetId());if(sets.size()!=1||!Objects.equals(sets.get(0).get("itemId"),input.itemId()))throw invalid("具体套不属于所选主档");if(!hasPhysicalFacts(sets.get(0)))throw invalid("该套仍属拟制或缺少开工／实物事实，请先在模具详情补录依据");}
            var after=new LinkedHashMap<String,Object>();after.put("phoneCodeItemId",phone.get("codeItemId"));after.put("phoneModelId",input.phoneModelId());after.put("productLineId",input.productLineId());after.put("projectId",input.projectId());after.put("itemId",input.itemId());after.put("moldSetId",input.moldSetId());
            if(after.equals(associationLinks(row)))return service.detail(id);
            var proof=new LinkedHashMap<String,Object>();proof.put("associationOnly",true);proof.put("reason",input.reason().trim());proof.put("actorId",actor.userId());proof.put("actorName",actor.displayName());proof.put("associatedAt",OffsetDateTime.now().toString());proof.put("before",associationLinks(row));proof.put("after",after);
            var resolution=row.get("executionResolution") instanceof JsonNode value&&value.isObject()?(com.fasterxml.jackson.databind.node.ObjectNode)value.deepCopy():(com.fasterxml.jackson.databind.node.ObjectNode)repo.node("{}");resolution.set("historicalAssociation",repo.node(repo.json(proof)));
            if(input.itemId()!=null&&input.projectId()!=null)repo.jdbc().update("insert into plm_collaboration_item_project(item_id,project_id,linked_by) values(?,?,?) on conflict(project_id) do nothing",input.itemId(),input.projectId(),actor.username());
            repo.jdbc().update("update plm_mold_application set phone_model_id=?,phone_code_item_id=?,product_line_id=?,project_id=?,item_id=?,mold_set_id=?,execution_resolution_json=?::jsonb,version_no=version_no+1,updated_at=now(),updated_by=? where application_id=?",input.phoneModelId(),phone.get("codeItemId"),input.productLineId(),input.projectId(),input.itemId(),input.moldSetId(),repo.json(resolution),actor.username(),id);
            service.audit("MOLD_APPLICATION_HISTORY_ASSOCIATE",id,proof,request,actor);return service.detail(id);
        });
    }
    private static Map<String,Object> associationLinks(Map<String,Object> row){var value=new LinkedHashMap<String,Object>();for(String key:List.of("phoneModelId","phoneCodeItemId","productLineId","projectId","itemId","moldSetId"))value.put(key,row.get(key));return value;}
    private Map<String,Object> eligible(long id,boolean lock){var row=service.require(id,lock);service.requireModelExecution(row);if(row.get("projectId")!=null)return row;
        if(!Boolean.TRUE.equals(row.get("approvalGranted"))||"TERMINATED".equals(row.get("remoteStatus")))throw invalid("申请尚未取得明确同意，或已终止");
        if(text(row.get("approvalNo"))==null)throw invalid("审批编号尚未同步完整，请先补拉再转换");
        if(!hasValidCodes(text(row.get("generatedCodes"))))throw invalid("历史申请缺少完整有效模具编码，须先在钉钉完善并同步；不能只转换其中部分编码");return row;}
    public Map<String,Object> preview(long id){return preview(id,false);}
    private Map<String,Object> preview(long id,boolean automatic){if(!automatic)service.convertPermission();var row=eligible(id,false);var config=codes.config(text(row.get("tipo")));long line=((Number)config.get("productLineId")).longValue();access.requireProduct(line);
        List<String> codeValues=MoldApplicationForms.codes(text(row.get("generatedCodes")));Set<String> prefixes=new HashSet<>(),phoneCodes=new HashSet<>(),materials=new HashSet<>();for(String c:codeValues){prefixes.add(c.substring(1,3));materials.add(c.substring(3,5));phoneCodes.add(c.substring(5));}
        var reservation=codes.reservedCompatibility(id,text(row.get("generatedCodes")));
        if(prefixes.size()!=1||!prefixes.contains(config.get("productSpecificCode"))||(phoneCodes.size()!=1&&reservation==null))throw invalid("审批模具编码与产品线配置不一致，须人工核对");
        var allowed=new HashSet<String>();for(var material:(JsonNode)config.get("materials"))allowed.add(material.path("code").asText());if(!allowed.containsAll(materials))throw invalid("审批材质编码尚未配置对应 PC/TPU 部件");
        String phoneCode=phoneCodes.iterator().next();Long codeItemId=row.get("phoneCodeItemId")==null?null:((Number)row.get("phoneCodeItemId")).longValue();
        var phone=repo.jdbc().queryForList("select code_item_id as \"codeItemId\",code_value as \"codeValue\",code_name as \"codeName\" from plm_code_item where code_type='phone_model' and "+(codeItemId==null?"code_value=?":"code_item_id=?")+" and deleted_flag=0",codeItemId==null?phoneCode:codeItemId);
        if(phone.size()!=1)throw invalid("四位型号编码尚未正确关联本地主数据，请管理员核对");
        phoneCode=phone.get(0).get("codeValue").toString();codeItemId=((Number)phone.get(0).get("codeItemId")).longValue();
        if(text(row.get("instanceId"))!=null&&repo.jdbc().queryForObject("select count(*) from plm_product where deleted_flag=0 and source_instance_id=? and (product_type<>'model_variant' or parent_product_id is distinct from ?)",Integer.class,row.get("instanceId"),line)>0)throw new BusinessException(40901,"同一审批实例已关联其他产品线或记录，请管理员人工核对");
        var matches=projectMatches(row,line,phoneCode,true);
        var items=repo.jdbc().queryForList("select i.item_id as \"itemId\",i.style_name as \"styleName\",i.compatible_models as \"compatibleModels\" from plm_collaboration_item i where i.deleted_flag=0 and i.product_line_id=? and (i.phone_model_code=? or lower(regexp_replace(btrim(i.compatible_models),'\\s+',' ','g'))=lower(regexp_replace(btrim(?),'\\s+',' ','g'))) and "+access.visibleSqlClause("item","i.item_id"),line,phoneCode,row.get("model"));
        if(row.get("targetItemId")!=null&&items.stream().noneMatch(item->Objects.equals(item.get("itemId"),row.get("targetItemId")))){access.requireItem(((Number)row.get("targetItemId")).longValue());items.addAll(repo.jdbc().queryForList("select item_id as \"itemId\",style_name as \"styleName\",compatible_models as \"compatibleModels\" from plm_collaboration_item where item_id=? and product_line_id=? and deleted_flag=0",row.get("targetItemId"),line));}
        var compatible=codes.references().compatibilityOptions(line,codeItemId,null);
        for(var match:compatible)if(items.stream().noneMatch(item->Objects.equals(item.get("itemId"),match.get("itemId"))))items.add(Map.of("itemId",match.get("itemId"),"styleName",match.get("styleName"),"compatibleModels",match.get("modelName")));
        List<Map<String,Object>> inventory=new ArrayList<>();for(String code:codeValues)inventory.addAll(repo.jdbc().queryForList("""
            select ? as "moldCode",i.inventory_id as "inventoryId",i.product_id as "inventoryProductId",
              mc.product_mold_code_id as "productMoldCodeId",mc.product_id as "moldProductId",
              part.mold_set_id as "moldSetId",s.item_id as "itemId"
            from (select 1) seed left join plm_inventory i on upper(i.inventory_code)=? and i.deleted_flag=0
              left join plm_product_mold_code mc on upper(mc.mold_code)=? and mc.deleted_flag=0
              left join plm_collaboration_mold_part part on part.product_mold_code_id=mc.product_mold_code_id
              left join plm_collaboration_mold_set s on s.mold_set_id=part.mold_set_id and s.deleted_flag=0
            """,code,code,code));
        var output=new LinkedHashMap<String,Object>();output.put("application",service.detail(id));output.put("config",config);output.put("phoneCode",phoneCode);output.put("phoneMatches",phone);output.put("projects",matches);output.put("items",items);output.put("moldMatches",inventory);output.put("compatibleMolds",compatible);output.put("reservedCompatibility",reservation);return output;
    }
    @SuppressWarnings("unchecked")
    public Map<String,Object> convertAutomatically(long id){
        var row=service.require(id,false);service.requireModelExecution(row);String intent=Objects.toString(row.get("executionIntent"),"new");
        if(!(row.get("authorization") instanceof JsonNode proof)||!"task".equals(proof.path("evidenceSource").asText())||text(proof.path("taskId"))==null||date(text(proof.path("approvedAt")))==null)throw invalid("周汉琴任务同意证据未完整核实，请补拉任务ID及同意时间");
        if(row.get("phoneModelId")==null)throw invalid("标准手机身份未确认，请工程核对");
        return convert(id,new Convert(((Number)row.get("version")).longValue(),row.get("targetProjectId")==null?"create":"link",(Long)row.get("targetProjectId"),(Long)row.get("targetItemId"),"reuse".equals(intent)?(Long)row.get("targetMoldSetId"):null,true,(Long)row.get("phoneModelId"),"reuse".equals(intent),"reuse".equals(intent)?Objects.toString(((Map<?,?>)codes.reservedCompatibility(id,text(row.get("generatedCodes")))).get("compatibilityDigest"),null):null),null,true);
    }
    public Map<String,Object> convert(long id,Convert input,HttpServletRequest request){var row=service.require(id,false);if("dingtalk".equals(row.get("sourceSystem")))throw invalid("历史申请请使用核对并关联已有资料，不创建新的项目或实物套");return convert(id,input,request,false);}
    private Map<String,Object> convert(long id,Convert input,HttpServletRequest request,boolean automatic){
        if(!automatic)service.convertPermission();var actor=access.actor();return new TransactionTemplate(transactions).execute(tx->{
            var row=eligible(id,true);if(row.get("projectId")!=null)return service.detail(id);
            if(((Number)row.get("version")).longValue()!=input.version())throw new BusinessException(40901,"申请已被更新，请刷新预览");
            if(!input.confirmCompatibility())throw invalid("请确认产品线、兼容型号和模具编码");
            if(input.moldSetId()!=null&&!input.reuseExisting())throw invalid("已有实物套须明确选择已确认共模复用；加开一套应创建新套，不能仅按同码关联");
            if(input.mode()==null||!Set.of("create","link").contains(input.mode()))throw invalid("请选择新建项目或关联已有项目");
            repo.jdbc().queryForObject("select pg_advisory_xact_lock(610131103)",(rs,n)->true);
            var p=preview(id,automatic);var config=(Map<String,Object>)p.get("config");long line=((Number)config.get("productLineId")).longValue();String phone=p.get("phoneCode").toString();
            var matches=(List<Map<String,Object>>)p.get("projects");var items=(List<Map<String,Object>>)p.get("items");var molds=(List<Map<String,Object>>)p.get("moldMatches");
            int allMatches=projectMatches(row,line,phone,false).size();
            if(allMatches>matches.size())throw new BusinessException(40103,"存在未获授权的同型号项目，请管理员核对关联，不能重复新建");
            List<String> moldCodes=MoldApplicationForms.codes(text(row.get("generatedCodes")));Map<String,Object> reused=null;var newSetSnapshot=codes.reservedNewSet(id,text(row.get("generatedCodes")));if(newSetSnapshot!=null){String actual=repo.jdbc().queryForObject("select coalesce(nullif(engraved_code,''),phone_model_code) from plm_collaboration_item where item_id=? and deleted_flag=0",String.class,newSetSnapshot.get("itemId"));if(!Objects.equals(((Number)newSetSnapshot.get("itemId")).longValue(),input.itemId())||!Objects.equals(newSetSnapshot.get("engravedCode"),actual))throw invalid("加开原主档或刻码已变化，请工程核对；不能自动替换正式审批目标");}
            Long standardPhoneId=(Long)row.get("phoneModelId");if(standardPhoneId==null)standardPhoneId=codes.reservedPhoneModelId(id,text(row.get("generatedCodes")));
            if(standardPhoneId!=null&&input.phoneModelId()!=null&&!standardPhoneId.equals(input.phoneModelId()))throw invalid("明确选择的标准手机与原预留记录不同，不能自动换型号");
            if(standardPhoneId==null)standardPhoneId=input.phoneModelId();
            if(standardPhoneId==null)throw invalid("历史申请的标准手机身份待核对，请明确选择后转入");
            if(standardPhoneId!=null)codes.references().requireSelectedPhone(standardPhoneId,((Number)((List<Map<String,Object>>)p.get("phoneMatches")).get(0).get("codeItemId")).longValue(),phone,text(row.get("model")));
            if(input.reuseExisting()) {
                if(input.phoneModelId()==null||input.itemId()==null||input.moldSetId()==null)throw invalid("复用须明确选择标准手机、共模档案及适用实物套");
                reused=codes.references().requireCompatibility(line,input.phoneModelId(),input.itemId(),input.moldSetId(),input.compatibilityDigest());
                var selectedPhones=(List<Map<String,Object>>)p.get("phoneMatches");if(((Number)reused.get("codeItemId")).longValue()!=((Number)selectedPhones.get(0).get("codeItemId")).longValue())throw invalid("申请手机原编码与选择的兼容成员不一致");
                var reserved=(Map<String,Object>)p.get("reservedCompatibility");if(reserved!=null&&(!Objects.equals(reserved.get("compatibilityDigest"),reused.get("compatibilityDigest"))||((Number)reserved.get("phoneModelId")).longValue()!=input.phoneModelId()||((Number)reserved.get("itemId")).longValue()!=input.itemId()||((Number)reserved.get("moldSetId")).longValue()!=input.moldSetId()))throw new BusinessException(40901,"已确认审批的复用关系与原预留快照不一致，不能自动换套");
                var parts=(JsonNode)reused.get("parts");moldCodes=new ArrayList<>();for(var part:parts)moldCodes.add(part.path("code").asText());
            } else if(p.get("reservedCompatibility")!=null)throw invalid("申请已明确预留原模具，转入时必须复用该套，不得重新建模具");
            if("create".equals(input.mode())&&(!matches.isEmpty()||(!input.reuseExisting()&&!"new_set".equals(row.get("executionIntent"))&&molds.stream().anyMatch(m->m.get("inventoryId")!=null||m.get("productMoldCodeId")!=null||m.get("moldSetId")!=null))))throw new BusinessException(40901,"相同型号或模具编码已存在，请关联已有记录");
            long project;
            if("link".equals(input.mode())) {
                if(input.projectId()==null||matches.stream().noneMatch(m->Objects.equals(((Number)m.get("projectId")).longValue(),input.projectId())))throw invalid("请选择预览中的已有同型号项目");
                access.requireProduct(input.projectId());
                if(standardPhoneId!=null) requireProjectPhone(input.projectId(),standardPhoneId,((Number)((List<Map<String,Object>>)p.get("phoneMatches")).get(0).get("codeItemId")).longValue());
                project=input.projectId();
            }
            else {
                Product parent=products.selectById(line);String name=row.get("tipo")+" "+row.get("model");
                String projectCode=productCodes.generate(name);
                Product value=new Product();value.setParentProductId(line);value.setProductCode(projectCode);value.setProductName(name);value.setProductType("model_variant");value.setSeriesName(parent.getSeriesName());value.setModel(text(row.get("model")));value.setProductSpecificCode(config.get("productSpecificCode").toString());value.setPhoneModelCode(phone);value.setPhoneModelMemberId(standardPhoneId);value.setVersionNo("A");value.setStatus("developing");value.setLockStatus("unlocked");value.setCurrentStepNo(1);value.setTimelineCurrentConfirmed(false);value.setSourceSystem("dingtalk");value.setSourceInstanceId(text(row.get("instanceId")));
                JsonNode form=(JsonNode)row.get("form");String expected=text(form.path("expectedDeliveryDate"));if(expected!=null)value.setExpectedDeliveryDate(LocalDate.parse(expected));
                var template=templates.findActiveTemplate("model_variant");if(template!=null){value.setWorkflowTemplateId(template.getWorkflowTemplateId());value.setWorkflowTemplateVersionNo(template.getVersionNo());}
                value.setCreatedBy(actor.username());value.setUpdatedBy(actor.username());value.setCreatedAt(LocalDateTime.now());value.setUpdatedAt(LocalDateTime.now());value.setDeletedFlag(0);products.insert(value);project=value.getProductId();
                repo.jdbc().update("""
                    insert into plm_project_requirement_form(project_id,ding_talk_approval_no,network_type,hole_type,mobile_function,tipo,priority,manufacturing_location,mold_marking,reference_url,remark,expected_delivery_date,product_specific_code,phone_model_code,mold_codes,status,created_by,updated_by)
                    values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'draft',?,?)
                    """,project,row.get("approvalNo"),text(form.path("networkType")),text(form.path("holeType")),text(form.path("mobileFunction")),row.get("tipo"),text(form.path("priority")),text(form.path("manufacturingLocation")),text(form.path("moldMarking")),text(form.path("website")),text(form.path("remark")),expected==null?null:LocalDate.parse(expected),config.get("productSpecificCode"),phone,String.join(" / ",moldCodes),actor.username(),actor.username());
            }
            com.yuewei.plm.module.project.ProjectRegistration.registerConverted(repo.jdbc(),project);
            Long item=input.itemId();if(item!=null){if(items.stream().noneMatch(i->Objects.equals(((Number)i.get("itemId")).longValue(),input.itemId())))throw invalid("请选择匹配的协作档案");access.requireItem(item);}
            else if(!items.isEmpty())throw new BusinessException(40901,"已有同型号协作档案，请明确选择关联");
            else {
                var form=(JsonNode)row.get("form");var phoneMatches=(List<Map<String,Object>>)p.get("phoneMatches");Long phoneId=phoneMatches.size()==1?((Number)phoneMatches.get(0).get("codeItemId")).longValue():null;
                item=repo.jdbc().queryForObject("insert into plm_collaboration_item(product_line_id,style_name,compatible_models,phone_model_code,phone_model_code_item_id,sales_platform,reference_url,notes,created_by,updated_by) values(?,?,?,?,?,?,?,?,?,?) returning item_id",Long.class,line,row.get("tipo"),row.get("model"),phone,phoneId,text(form.path("salesChannel")),text(form.path("website")),"来源开模申请："+row.get("approvalNo")+"；价格原值："+Objects.toString(text(form.path("phonePrice")),"未提供"),actor.username(),actor.username());
            }
            com.yuewei.plm.module.masterdata.ProductMoldLifecycleService.requireActive(repo.jdbc(),"product",line);
            com.yuewei.plm.module.masterdata.ProductMoldLifecycleService.requireActive(repo.jdbc(),"product",project);
            com.yuewei.plm.module.masterdata.ProductMoldLifecycleService.requireActive(repo.jdbc(),"item",item);
            var priorLinks=repo.jdbc().queryForList("select item_id from plm_collaboration_item_project where project_id=?",project);if(!priorLinks.isEmpty()&&((Number)priorLinks.get(0).get("item_id")).longValue()!=item)throw invalid("项目已经关联另一协作档案");
            repo.jdbc().update("insert into plm_collaboration_item_project(item_id,project_id,linked_by) values(?,?,?) on conflict(project_id) do nothing",item,project,actor.username());
            Long set=input.moldSetId();
            if(set!=null)com.yuewei.plm.module.masterdata.ProductMoldLifecycleService.requireActive(repo.jdbc(),"mold_set",set);
            if(set!=null){access.requireMold(set);if(repo.jdbc().queryForObject("select count(*) from plm_collaboration_mold_set where mold_set_id=? and item_id=? and deleted_flag=0",Integer.class,set,item)!=1)throw invalid("模具套不属于所选协作档案");}
            else {
                if(!"new_set".equals(row.get("executionIntent"))&&molds.stream().anyMatch(m->m.get("moldSetId")!=null))throw new BusinessException(40901,"模具编码已关联实物套，请选择已有模具套");
                set=repo.jdbc().queryForObject("insert into plm_collaboration_mold_set(item_id,set_no,set_name,created_by,updated_by) select ?,coalesce(max(set_no),0)+1,'开模申请 '||?, ?,? from plm_collaboration_mold_set where item_id=? returning mold_set_id",Long.class,item,row.get("approvalNo"),actor.username(),actor.username(),item);
            }
            if(!input.reuseExisting())for(String code:moldCodes) {
                JsonNode material=null;for(var m:(JsonNode)config.get("materials"))if(m.path("code").asText().equals(code.substring(3,5)))material=m;
                if(material==null)throw invalid("材质部件映射缺失");String part=material.path("partType").asText();
                // A planned set is not an in-stock asset. Create inventory only through physical registration.
                var relations=repo.jdbc().queryForList("select product_mold_code_id,product_id from plm_product_mold_code where mold_code=?",code);Long relation;
                if(relations.isEmpty())relation=repo.jdbc().queryForObject("insert into plm_product_mold_code(product_id,mold_code,mold_prefix,product_code_prefix,product_specific_code,mold_name,status,created_by,updated_by) values(?,?,?,?,?,?,'active',?,?) returning product_mold_code_id",Long.class,line,code,code.substring(0,3),"N"+code.substring(1,3),code.substring(1,3),row.get("tipo")+" "+row.get("model"),actor.username(),actor.username());
                else {if(((Number)relations.get(0).get("product_id")).longValue()!=line)throw invalid("模具编码已归属其他产品线");relation=((Number)relations.get(0).get("product_mold_code_id")).longValue();}
                var existingPart=repo.jdbc().queryForList("select product_mold_code_id,original_mold_code from plm_collaboration_mold_part where mold_set_id=? and part_type=?",set,part);
                if(!existingPart.isEmpty()&&((existingPart.get(0).get("product_mold_code_id")!=null&&!Objects.equals(existingPart.get(0).get("product_mold_code_id"),relation))||(text(existingPart.get(0).get("original_mold_code"))!=null&&!code.equalsIgnoreCase(text(existingPart.get(0).get("original_mold_code"))))))throw invalid("所选模具套部件已经关联另一编码");
                repo.jdbc().update("insert into plm_collaboration_mold_part(mold_set_id,part_type,product_mold_code_id,original_mold_code,physical_status) values(?,?,?,?,'unknown') on conflict(mold_set_id,part_type) do update set product_mold_code_id=excluded.product_mold_code_id,original_mold_code=excluded.original_mold_code",set,part,relation,code);
            }
            // This is an intended phone/set relation, retained on the application below.
            // Confirmed compatibility members require both PC and TPU evidence; never invent that evidence.
            if(!input.reuseExisting()&&"new_set".equals(row.get("executionIntent"))&&row.get("targetMoldSetId")!=null)repo.jdbc().update("update plm_collaboration_mold_set set source_mold_set_id=? where mold_set_id=?",row.get("targetMoldSetId"),set);
            repo.jdbc().update("update plm_mold_application set project_id=?,item_id=?,mold_set_id=?,phone_model_id=?,execution_intent=?,status='converted',provisioning_status='ready',provisioning_error=null,execution_status='active',version_no=version_no+1,updated_at=now(),updated_by=? where application_id=?",project,item,set,standardPhoneId,input.reuseExisting()?"reuse":row.get("executionIntent"),actor.username(),id);
            if(!access.access().allObjects())for(var scope:List.of(Map.entry("product",project),Map.entry("item",item),Map.entry("mold",set)))repo.jdbc().update("insert into plm_pilot_scope(user_id,entity_type,entity_id) values(?,?,?) on conflict do nothing",actor.userId(),scope.getKey(),scope.getValue());
            var audit=new LinkedHashMap<String,Object>();audit.put("projectId",project);audit.put("itemId",item);audit.put("moldSetId",set);audit.put("mode",input.mode());audit.put("reuseExisting",input.reuseExisting());audit.put("originalApprovalCodes",row.get("generatedCodes"));audit.put("actualMoldCodes",moldCodes);if(reused!=null)audit.put("compatibility",reused);
            service.audit("MOLD_APPLICATION_CONVERT",id,audit,request,actor);return service.detail(id);
        });
    }
    private List<Map<String,Object>> projectMatches(Map<String,Object> row,long line,String phone,boolean scoped){
        var args=new ArrayList<Object>();args.add(line);String identity;
        if(row.get("phoneModelId")!=null){identity="(p.phone_model_member_id=? or (p.phone_model_member_id is null and p.source_instance_id=?))";args.add(row.get("phoneModelId"));args.add(row.get("instanceId"));}
        else {identity="(p.phone_model_code=? or lower(regexp_replace(btrim(p.model),'\\s+',' ','g'))=lower(regexp_replace(btrim(?),'\\s+',' ','g')) or p.source_instance_id=?)";args.add(phone);args.add(row.get("model"));args.add(row.get("instanceId"));}
        return repo.jdbc().queryForList("select p.product_id as \"projectId\",p.product_name as \"productName\",p.model,p.phone_model_code as \"phoneModelCode\",p.source_instance_id as \"sourceInstanceId\" from plm_product p where p.deleted_flag=0 and "+ProjectRegistration.modelCandidateSql("p")+" and p.parent_product_id=? and "+identity+(scoped?" and "+access.visibleSqlClause("product","p.product_id"):""),args.toArray());
    }
    /** New explicit selections cannot use a bare shared code to relink another phone's project. */
    private void requireProjectPhone(long projectId,long phoneModelId,long codeItemId) {
        var project=repo.jdbc().queryForMap("select phone_model_member_id,phone_model_code,model from plm_product where product_id=? and deleted_flag=0 for update",projectId);
        requireProjectPhoneData(project,phoneModelId,codeItemId);
    }
    private void requireProjectPhoneData(Map<String,Object> project,long phoneModelId,long codeItemId) {
        Object stored=project.get("phone_model_member_id");
        if(stored!=null) {
            if(((Number)stored).longValue()!=phoneModelId) throw invalid("已有项目属于另一标准手机成员，不能仅按相同编码关联");
            var selected=codes.references().requireEnabledModels(List.of(phoneModelId)).get(phoneModelId);
            if(!Objects.equals(selected.get("codeItemId"),codeItemId)||!Objects.equals(project.get("phone_model_code"),selected.get("phoneModelCode"))) throw invalid("已有项目原编码与所选标准手机不一致，请人工核对");
            return; // Its original model snapshot is not rewritten after a reference name changes.
        }
        String originalCode=text(project.get("phone_model_code"));
        if(originalCode==null) throw invalid("历史项目原编码缺失，请人工核对后关联标准手机");
        if(repo.jdbc().queryForObject("select count(*) from plm_code_item where code_type='phone_model' and code_value=? and code_item_id<>? and deleted_flag<>0",Long.class,originalCode,codeItemId)>0)
            throw invalid("历史项目缺少稳定手机关联且原码曾被重用，请人工核对原编码记录，不能按字符串关联");
        codes.references().requireSelectedPhone(phoneModelId,codeItemId,originalCode,text(project.get("model")));
    }
}
