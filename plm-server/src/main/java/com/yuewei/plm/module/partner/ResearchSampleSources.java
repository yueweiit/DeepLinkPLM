package com.yuewei.plm.module.partner;

import com.yuewei.plm.module.moldreference.PhoneModelReferenceService;
import com.yuewei.plm.module.moldreference.PhoneSelectionMetadata;
import com.yuewei.plm.module.moldreference.PhoneSelectionRelations;
import com.yuewei.plm.module.project.ProjectBusinessLinks;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import static com.yuewei.plm.module.partner.PartnerValues.*;

/** Domain-specific receipt sources; actual quantities and movements still go through SampleLedger. */
@Service @RequiredArgsConstructor
public class ResearchSampleSources {
    private final PartnerStore store;
    private final PhoneModelReferenceService phones;

    private void read() {store.access.requireDomesticView();store.access.requirePermission("partner:view");}
    private Map<String,Object> project(long id) {
        store.access.requirePermission("project:view");store.access.requireProduct(id);
        var rows=store.jdbc.queryForList("select product_id as id,product_code as code,product_name as name,product_type as type,project_kind as \"projectKind\",development_mode as \"developmentMode\",parent_product_id as \"modelProductId\" from plm_product where product_id=? and deleted_flag=0",id);
        require(rows.size()==1,"来源项目不存在");PhoneSelectionMetadata.enrich(store.jdbc,rows,"id","product");return rows.get(0);
    }
    public void usage(Map<String,Object> facts) {
        var p=project(number(facts.get("projectId")));
        require(!SampleDomains.project(p).equals("unclassified"),"用途请选择已登记的型号研发或新品研发项目，不能把历史 SKU 当成项目");
        facts.put("projectName",p.get("name"));
    }
    private List<Map<String,Object>> rounds(long projectId,Long roundId,boolean lock) {
        String actual="r.record_date is not null and r.record_date<=?::date and nullif(btrim(r.details_json->>'makerName'),'') is not null " +
                "and (case when jsonb_typeof(r.details_json->'phoneModelIds')='array' then jsonb_array_length(r.details_json->'phoneModelIds') else 0 end)>0 " +
                "and not exists(select 1 from plm_product_development_state st where st.product_id=r.product_id and jsonb_exists(coalesce(st.data->'voidedRounds','{}'::jsonb),r.round_id::text))";
        List<Object> args=new ArrayList<>(List.of(projectId,java.time.LocalDate.now().toString()));if(roundId!=null)args.add(roundId);
        return store.jdbc.queryForList("select r.round_id as id,r.round_no as no,r.title,r.result,r.status,r.record_date,r.references_json::text as refs,r.details_json::text as details,r.updated_at from plm_product_development_round r where r.product_id=? and r.kind='sample' and r.status in ('draft','confirmed') and r.deleted_flag=0 and "+actual+(roundId==null?"":" and r.round_id=?")+" order by r.round_no desc"+(lock?" for share of r":""),args.toArray());
    }
    public Map<String,Object> actualRound(long projectId,long roundId) {
        require(SampleDomains.project(project(projectId)).equals("research"),"请选择新品研发项目的实际打样来源");
        var rows=rounds(projectId,roundId,true);
        require(rows.size()==1,"本轮尚无完整实收依据或已失效：请在原打样记录填写真实收样日期、制作方和机型范围；无需先确认验证通过");
        return rows.get(0);
    }
    public Map<String,Object> options(String scope,Long projectId) {
        return options(scope,projectId,null,null);
    }
    public Map<String,Object> options(String scope,Long projectId,Long modelProductId,Long phoneModelId) {
        read();String domain=SampleDomains.validate(scope);require(Set.of("model","research").contains(domain),"请选择型号研发或新品研发样品范围");
        if(domain.equals("model"))return modelOptions(projectId,modelProductId,phoneModelId);
        store.access.requirePermission("project:view");
        if(projectId==null) {
            String kind="(p.project_kind='product_research' or (p.product_type='product_line' and p.development_mode='launch_v1'))";
            var rows=store.jdbc.queryForList("select p.product_id as id,p.product_code as code,p.product_name as name,p.project_kind as \"projectKind\" from plm_product p where p.deleted_flag=0 and "+kind+" and "+store.access.visibleSqlClause("product","p.product_id")+" order by p.updated_at desc,p.product_id desc");
            PhoneSelectionMetadata.enrich(store.jdbc,rows,"id","product");return Map.of("projects",rows);
        }
        var p=project(projectId);require(domain.equals(SampleDomains.project(p)),"项目不属于当前样品业务范围");
        var result=new LinkedHashMap<String,Object>();result.put("project",p);
        result.put("rounds",rounds(projectId,null,false).stream().map(r->Map.of("id",r.get("id"),"no",r.get("no"),"title",r.get("title"),"result",r.get("result"),"status",r.get("status"),"date",r.get("record_date").toString())).toList());
        return result;
    }
    private Map<String,Object> modelProduct(long id) {
        store.access.requirePermission("product:view");store.access.requireProduct(id);
        var rows=store.jdbc.queryForList("select p.product_id as id,p.product_code as code,p.product_name as name,p.sales_status as \"salesStatus\" from plm_product p where p.product_id=? and p.product_type='product_line' and p.deleted_flag=0 for share of p",id);
        require(rows.size()==1,"请选择产品目录中的产品，不能选择颜色 SKU 或把型号项目当成产品");return rows.get(0);
    }
    private List<Map<String,Object>> modelProjects(Long modelProductId,Long phoneModelId) {
        if(!store.access.hasPermission("project:view"))return List.of();
        var args=new ArrayList<Object>();String filter="";
        if(modelProductId!=null){filter+=" and p.parent_product_id=?";args.add(modelProductId);}
        if(phoneModelId!=null){filter+=" and exists(select 1 from product_members pm join plm_phone_model_member phone on phone.id=pm.phone_id left join plm_code_item code on code.code_item_id=phone.code_item_id and code.deleted_flag=0 where pm.product_id=p.product_id and pm.phone_id=? and (nullif(p.phone_model_code,'') is null or p.phone_model_code=code.code_value))";args.add(phoneModelId);}
        var rows=store.jdbc.queryForList("with "+PhoneSelectionRelations.members()+" select p.product_id as id,p.product_code as code,p.product_name as name,p.project_kind as \"projectKind\",p.parent_product_id as \"modelProductId\" from plm_product p where p.deleted_flag=0 and p.project_kind='model_extension' and p.product_type='model_variant' and "+store.access.visibleSqlClause("product","p.product_id")+filter+" order by p.updated_at desc,p.product_id desc",args.toArray());
        PhoneSelectionMetadata.enrich(store.jdbc,rows,"id","product");return rows;
    }
    private Map<String,Object> modelOptions(Long projectId,Long modelProductId,Long phoneModelId) {
        var result=new LinkedHashMap<String,Object>();store.access.requirePermission("product:view");
        result.put("products",store.jdbc.queryForList("select p.product_id as id,p.product_code as code,p.product_name as name,p.sales_status as \"salesStatus\" from plm_product p where p.product_type='product_line' and p.deleted_flag=0 and "+store.access.visibleSqlClause("product","p.product_id")+" order by p.product_name,p.product_id"));
        Map<String,Object> selectedProject=null;
        if(projectId!=null){
            selectedProject=project(projectId);require(SampleDomains.project(selectedProject).equals("model"),"项目不属于型号研发");result.put("project",selectedProject);
            if(modelProductId==null&&number(selectedProject.get("modelProductId"))>0)modelProductId=number(selectedProject.get("modelProductId"));
            if(phoneModelId==null){var linkedPhones=maps(selectedProject.get("phoneModels")).stream().map(p->number(p.get("phoneModelId"))).filter(id->id>0).distinct().toList();if(linkedPhones.size()==1)phoneModelId=linkedPhones.get(0);}
        }
        if(modelProductId!=null){require(modelProductId>0,"产品无效");result.put("product",modelProduct(modelProductId));}
        if(phoneModelId!=null){require(phoneModelId>0,"手机型号无效");phones.requireEnabledModels(List.of(phoneModelId));}
        var projects=modelProductId!=null&&phoneModelId!=null?modelProjects(modelProductId,phoneModelId):List.<Map<String,Object>>of();result.put("projects",projects);
        boolean canReadSets=store.access.hasPermission("project:view")&&store.access.hasPermission("schedule:read")&&store.access.hasPermission("collaboration:view");result.put("canReadSets",canReadSets);result.put("canReadProjects",store.access.hasPermission("project:view"));
        if(selectedProject!=null){
            if(modelProductId!=null)require(number(selectedProject.get("modelProductId"))==modelProductId,"项目与所选产品不一致；请从当前产品重新选择");
            if(modelProductId!=null&&phoneModelId!=null)require(projects.stream().anyMatch(row->number(row.get("id"))==projectId),"项目与所选产品或实际手机型号不一致；请从当前产品和型号重新选择");
            result.put("sets",canReadSets&&modelProductId!=null&&phoneModelId!=null?sets(projectId,modelProductId,phoneModelId):List.of());
        } else result.put("sets",List.of());
        return result;
    }
    private List<Map<String,Object>> sets(long projectId) {
        return sets(projectId,null,null);
    }
    private List<Map<String,Object>> sets(long projectId,Long modelProductId,Long phoneModelId) {
        var args=new ArrayList<Object>(List.of(projectId));String filter="";
        if(modelProductId!=null){filter+=" and (i.product_line_id is null or i.product_line_id=?)";args.add(modelProductId);}
        if(phoneModelId!=null){filter+=" and exists(select 1 from set_members sm where sm.mold_set_id=s.mold_set_id and sm.phone_id=?)";args.add(phoneModelId);}
        var rows=store.jdbc.queryForList("with "+PhoneSelectionRelations.members()+" select s.mold_set_id as id,s.mold_set_id as \"moldSetId\",s.item_id as \"itemId\",s.set_no as \"setNo\",s.set_name as name,i.mold_name as \"moldName\",i.engraved_code as \"engravedCode\" from plm_collaboration_mold_set s join plm_collaboration_item i on i.item_id=s.item_id and i.deleted_flag=0 where s.deleted_flag=0 and "+ProjectBusinessLinks.setExists("s.mold_set_id")+" and "+store.access.visibleSqlClause("mold","s.mold_set_id")+" and "+store.access.visibleSqlClause("item","i.item_id")+filter+" order by s.set_no,s.mold_set_id",args.toArray());
        PhoneSelectionMetadata.enrich(store.jdbc,rows,"id","mold_set");return rows;
    }
    private Map<String,Object> buildModel(Map<String,Object> input) {
        long productId=number(input.get("modelProductId"));require(productId>0,"请选择产品");var product=modelProduct(productId);
        long phoneId=number(input.get("phoneModelId"));require(phoneId>0,"请选择实际手机型号");
        var phone=phones.requireEnabledModels(List.of(phoneId)).get(phoneId);require(phone!=null,"手机资料不存在或已停用");
        var snapshot=new LinkedHashMap<String,Object>();snapshot.put("product",product);snapshot.put("phone",phone);
        var d=new LinkedHashMap<String,Object>();d.put("sourceType","model");d.put("sourceDomain","model");d.put("modelProductId",productId);d.put("phoneModelId",phoneId);d.put("phoneModelIds",List.of(phoneId));
        d.put("pending",false);d.put("status","active");d.put("refs",List.of());d.put("ownerName",required(input.get("ownerName"),"实际保管人"));
        d.put("provenance",required(input.get("provenance"),"实际收样来源说明"));snapshot.put("provenance",d.get("provenance"));
        for(String key:List.of("purpose","sampleVersion","specName","assetNumber"))d.put(key,text(input.get(key)));
        d.put("name",product.get("name")+" · "+phone.get("modelName"));
        long projectId=number(input.get("projectId")),setId=number(input.get("moldSetId"));
        require(setId==0||projectId>0,"关联具体模具套前请选择真实来源型号项目");
        if(projectId>0){
            var p=project(projectId);require(SampleDomains.project(p).equals("model"),"请选择型号研发项目");
            require(modelProjects(productId,phoneId).stream().anyMatch(row->number(row.get("id"))==projectId),"项目与所选产品或实际手机型号不一致；请在项目资料补齐明确关联");
            d.put("projectId",projectId);snapshot.put("project",p);
        }
        if(setId>0){
            store.access.requirePermission("schedule:read");store.access.requirePermission("collaboration:view");store.access.requireMold(setId);
            var selected=sets(projectId,productId,phoneId).stream().filter(row->number(row.get("id"))==setId).toList();
            require(selected.size()==1,"具体模具套与本产品、手机和项目没有一致的明确关联；请在模具管理或申请历史补链中补齐");
            var set=selected.get(0);store.access.requireItem(number(set.get("itemId")));String batch=required(input.get("trialBatch"),"实际试模批次／轮次");
            d.put("moldSetId",setId);d.put("itemId",set.get("itemId"));d.put("trialBatch",batch);snapshot.put("moldSet",set);snapshot.put("trialBatch",batch);
            snapshot.put("parts",store.jdbc.queryForList("select part_type as \"partType\",original_mold_code as code,status,physical_status as \"physicalStatus\" from plm_collaboration_mold_part where mold_set_id=? order by part_type",setId));
        }
        d.put("sourceSnapshot",snapshot);return d;
    }
    public Map<String,Object> build(Map<String,Object> input) {
        read();store.access.requireEngineeringMaintenance();require(!Boolean.TRUE.equals(input.get("pending")),"请登记真实来源，历史期初资料仍从原期初登记入口办理");
        require(!java.time.LocalDate.parse(date(input.get("date"))).isAfter(java.time.LocalDate.now()),"实际收样日期不能晚于今天，计划拿样请回原流程登记");
        if(text(input.get("sourceType")).equals("model"))return buildModel(input);
        String type=required(input.get("sourceType"),"样品来源"),kind=required(input.get("sampleKind"),"样品类别");
        var d=new LinkedHashMap<String,Object>();var snapshot=new LinkedHashMap<String,Object>();
        d.put("sourceType",type);d.put("sourceDomain","model");d.put("sampleKind",kind);d.put("pending",false);d.put("status","active");d.put("refs",List.of());
        d.put("ownerName",required(input.get("ownerName"),"实际保管人"));
        d.put("purpose",text(input.get("purpose")));d.put("sampleVersion",text(input.get("sampleVersion")));d.put("specName",text(input.get("specName")));
        if(type.equals("device")) {
            require(Set.of("phone","dummy").contains(kind),"手机实物只能登记为真机或模型机");
            long phone=number(input.get("phoneModelId"));require(phone>0,"请选择实际手机型号");
            var members=phones.requireEnabledModels(List.of(phone));var member=members.get(phone);require(member!=null,"手机资料不存在或已停用");
            d.put("phoneModelId",phone);d.put("phoneModelIds",List.of(phone));snapshot.put("phone",member);
            d.put("name",text(member.get("modelName"))+ (kind.equals("phone")?" · 真机":" · 模型机"));
            d.put("assetNumber",text(input.get("assetNumber")));d.put("provenance",required(input.get("provenance"),"采购、借入或其他来源说明"));
            long projectId=number(input.get("projectId"));if(projectId>0){var p=project(projectId);require(SampleDomains.project(p).equals("model"),"真机／模型机用途请关联型号研发项目");d.put("projectId",projectId);snapshot.put("project",p);}
            snapshot.put("provenance",d.get("provenance"));
        } else {
            require(type.equals("trial")&&Set.of("pc","tpu","case").contains(kind),"试模样品请选择 PC、TPU 或组装手机壳");
            long projectId=number(input.get("projectId"));var p=project(projectId);require(SampleDomains.project(p).equals("model"),"试模样品必须关联型号研发项目");
            store.access.requirePermission("schedule:read");store.access.requirePermission("collaboration:view");
            long setId=number(input.get("moldSetId"));store.access.requireMold(setId);
            var matches=sets(projectId).stream().filter(s->number(s.get("id"))==setId).toList();require(matches.size()==1,"具体模具套未明确关联本项目；请在模具管理或申请历史补链中补齐，不能仅关联主档推定所有套");
            var set=matches.get(0);store.access.requireItem(number(set.get("itemId")));
            var parts=store.jdbc.queryForList("select part_type as \"partType\",original_mold_code as code,status,physical_status as \"physicalStatus\" from plm_collaboration_mold_part where mold_set_id=? order by part_type",setId);
            require(kind.equals("case")?parts.size()==2:parts.stream().anyMatch(part->kind.equalsIgnoreCase(text(part.get("partType")))),"该具体套没有所选部件资料，请先在模具管理补齐");
            String batch=required(input.get("trialBatch"),"试模批次／轮次");
            d.put("projectId",projectId);d.put("moldSetId",setId);d.put("itemId",set.get("itemId"));d.put("trialBatch",batch);
            d.put("name",p.get("name")+" · "+(kind.equals("case")?"组装壳":kind.toUpperCase(Locale.ROOT))+" · "+batch);
            snapshot.put("project",p);snapshot.put("moldSet",set);snapshot.put("parts",kind.equals("case")?parts:parts.stream().filter(part->kind.equalsIgnoreCase(text(part.get("partType")))).toList());snapshot.put("trialBatch",batch);
        }
        d.put("sourceSnapshot",snapshot);return d;
    }
}
