package com.yuewei.plm.module.masterdata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.yuewei.plm.common.exception.BusinessException;
import com.yuewei.plm.common.vo.PageVO;
import com.yuewei.plm.module.operationlog.service.OperationLogCreateCommand;
import com.yuewei.plm.module.operationlog.service.OperationLogService;
import com.yuewei.plm.module.pilot.PilotAccessService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Maintenance of the original master identities; never registers a research project or stock. */
@Service @RequiredArgsConstructor
public class ProductMoldLifecycleService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final PilotAccessService access;
    private final OperationLogService audit;
    private final com.yuewei.plm.module.moldreference.PhoneModelReferenceService phones;
    private final Map<String,List<String>> referenceKeys=new java.util.concurrent.ConcurrentHashMap<>();
    public record Action(@Positive long version,@NotNull UUID requestId,@NotBlank @Size(max=2000) String reason) {}
    public record ProductInput(@NotBlank @Size(max=255) String productName,
        @Pattern(regexp="product_line|model_variant|sku") String productType, Long parentProductId,
        @Size(max=64) String productCode,@Size(max=20) String productSpecificCode,Long phoneModelId,
        @Size(max=128) String model,@Size(max=128) String material,@Size(max=2000) String remark,
        @Positive long version,@NotNull UUID requestId,@NotBlank @Size(max=2000) String reason) {}
    private static String table(String kind){return switch(kind){case "product"->"plm_product";case "item"->"plm_collaboration_item";case "mold_set"->"plm_collaboration_mold_set";default->throw new IllegalArgumentException("kind");};}
    private static String pk(String kind){return switch(kind){case "product"->"product_id";case "item"->"item_id";case "mold_set"->"mold_set_id";default->throw new IllegalArgumentException("kind");};}
    private static String scope(String kind){return kind.equals("mold_set")?"mold":kind;}
    /** Caller must own a write transaction, so FOR SHARE serializes with lifecycle FOR UPDATE. */
    public static void requireActive(JdbcTemplate jdbc,String kind,long id){requireActive(jdbc,kind,id,new HashSet<>());}
    private static void requireActive(JdbcTemplate jdbc,String kind,long id,Set<String> seen){
        if(!seen.add(kind+":"+id))throw invalid("资料所属关系存在循环，请工程核对");
        // Parent first: a retired platform/archive also blocks adoption of otherwise-active children.
        String parentColumn=kind.equals("product")?"parent_product_id":kind.equals("mold_set")?"item_id":null;
        if(parentColumn!=null){var owners=jdbc.queryForList("select "+parentColumn+" from "+table(kind)+" where "+pk(kind)+"=?",id);if(owners.size()==1&&owners.get(0).get(parentColumn) instanceof Number parent)requireActive(jdbc,kind.equals("product")?"product":"item",parent.longValue(),seen);}
        var rows=jdbc.queryForList("select master_data_status,deleted_flag from "+table(kind)+" where "+pk(kind)+"=? for share",id);
        if(rows.size()!=1||((Number)rows.get(0).get("deleted_flag")).intValue()!=0||!"active".equals(rows.get(0).get("master_data_status")))
            throw invalid("所选资料或所属主档已停用或删除，请选择在用资料；历史记录仍可查看和更正");
    }
    private void read(String kind){access.requireDomesticView();access.requirePermission(kind.equals("product")?"product:view":"collaboration:view");}
    private Map<String,Object> row(String kind,long id,boolean deleted,boolean lock){
        String visible=access.visibleSqlClause(scope(kind),"m."+pk(kind));
        var rows=jdbc.queryForList("select m.* from "+table(kind)+" m where m."+pk(kind)+"=? and ("+visible+")"+(deleted?"":" and m.deleted_flag=0")+(lock?" for update":""),id);
        if(rows.size()!=1)throw new BusinessException(40401,"资料不存在或未获授权");return rows.get(0);
    }
    public PageVO<Map<String,Object>> products(String keyword,int page,int size){
        read("product");if(page<1||size<1||size>100)throw invalid("分页无效");
        var args=new ArrayList<Object>();String where=" where p.deleted_flag=0 and ("+access.visibleSqlClause("product","p.product_id")+")";
        if(keyword!=null&&!keyword.isBlank()){if(keyword.length()>128)throw invalid("关键词过长");where+=" and concat_ws(' ',p.product_name,p.product_code,p.model,p.product_specific_code) ilike ?";args.add("%"+keyword.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%");}
        long total=Objects.requireNonNull(jdbc.queryForObject("select count(*) from plm_product p"+where,Long.class,args.toArray()));
        args.add(size);args.add((long)(page-1)*size);
        var rows=jdbc.queryForList("select p.* from plm_product p"+where+" order by p.updated_at desc nulls last,p.product_id desc limit ? offset ?",args.toArray());
        return new PageVO<>(rows.stream().map(this::productView).toList(),page,size,total,(total+size-1)/size);
    }
    public Map<String,Object> product(long id){read("product");return productView(row("product",id,false,false));}
    private Map<String,Object> productView(Map<String,Object> r){
        var out=new LinkedHashMap<String,Object>();
        for(var pair:Map.ofEntries(Map.entry("product_id","id"),Map.entry("product_name","name"),Map.entry("product_code","code"),Map.entry("product_type","productType"),Map.entry("parent_product_id","parentProductId"),Map.entry("product_specific_code","productSpecificCode"),Map.entry("phone_model_member_id","phoneModelId"),Map.entry("model","model"),Map.entry("material","material"),Map.entry("remark","remark"),Map.entry("source_system","sourceSystem"),Map.entry("project_kind","projectKind"),Map.entry("master_data_status","status"),Map.entry("master_data_version","version")).entrySet())out.put(pair.getValue(),r.get(pair.getKey()));
        out.put("sourceOwned",Set.of("mes","crm").contains(Objects.toString(r.get("source_system"),"")));
        out.put("canMaintain",access.canMaintainEngineeringData());if(r.get("project_kind")!=null)out.put("editingPath","launch_v1".equals(r.get("development_mode"))?"/product-research?productId="+r.get("product_id"):"/projects?productId="+r.get("product_id"));return out;
    }
    @Transactional public Map<String,Object> createProduct(ProductInput dto,HttpServletRequest request){
        read("product");var actor=access.requireEngineeringMaintenance();validateInput(dto,true,null);
        // The same UUID produces the same immutable local identity. Deleted tombstones cannot be recreated.
        String code=dto.productCode()==null||dto.productCode().isBlank()?"BASE-"+dto.requestId().toString().toUpperCase(Locale.ROOT):dto.productCode().trim();
        jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(?,0))",Object.class,"product-master:"+code);
        var existing=jdbc.queryForList("select product_id,deleted_flag from plm_product where product_code=?",code);
        if(!existing.isEmpty()){
            long id=((Number)existing.get(0).get("product_id")).longValue();
            if(((Number)existing.get(0).get("deleted_flag")).intValue()!=0)throw invalid("编码已被删除的历史资料占用，不能重新创建");
            var replay=replay("product",id,dto.requestId(),"create",dto);if(replay!=null)return replay;throw invalid("编码已存在或已被历史档案占用，请查看原资料");
        }
        String specificCode=empty(dto.productSpecificCode());
        if(dto.parentProductId()!=null){access.requireProduct(dto.parentProductId());requireActive(jdbc,"product",dto.parentProductId());var parent=jdbc.queryForMap("select product_type,product_specific_code from plm_product where product_id=?",dto.parentProductId());if(!"product_line".equals(parent.get("product_type")))throw invalid("所属产品必须是产品平台");specificCode=Objects.toString(parent.get("product_specific_code"),null);}
        var phone=phone(dto.phoneModelId());
        Long id=jdbc.queryForObject("insert into plm_product(product_code,product_name,product_type,parent_product_id,product_specific_code,phone_model_member_id,phone_model_code,model,material,remark,version_no,status,lock_status,source_system,created_by,updated_by) values(?,?,?,?,?,?,?,?,?,?,'V1','draft','unlocked','plm_master',?,?) returning product_id",Long.class,code,dto.productName().trim(),dto.productType(),dto.parentProductId(),specificCode,dto.phoneModelId(),phone.get("phone_model_code"),dto.phoneModelId()==null?empty(dto.model()):phone.get("model"),empty(dto.material()),empty(dto.remark()),actor.username(),actor.username());
        if(access.isRestricted())jdbc.update("insert into plm_pilot_scope(user_id,entity_type,entity_id) values(?,'product',?) on conflict do nothing",actor.userId(),id);
        var result=productView(row("product",id,false,false));log("product",id,"create",dto.requestId(),dto.reason(),dto,result,request);return result;
    }
    @Transactional public Map<String,Object> updateProduct(long id,ProductInput dto,HttpServletRequest request){
        read("product");access.requireEngineeringMaintenance();var current=row("product",id,false,true);
        var replay=replay("product",id,dto.requestId(),"update",dto);if(replay!=null)return replay;
        if(current.get("project_kind")!=null)throw invalid("已登记研发／型号项目，请从原项目办理入口编辑，沿用原阶段、冻结及权限校验");
        version(current,dto.version());validateInput(dto,false,current);
        boolean source=Set.of("mes","crm").contains(Objects.toString(current.get("source_system"),""));
        if(source&&(!Objects.equals(current.get("product_name"),dto.productName().trim())||!Objects.equals(empty(Objects.toString(current.get("model"),null)),empty(dto.model()))))throw invalid("来源名称与型号由原系统维护，此处仅维护本地材料及备注");
        if(current.get("phone_model_member_id")!=null&&!Objects.equals(empty(Objects.toString(current.get("model"),null)),empty(dto.model())))throw invalid("标准手机型号快照不可在基础资料中改写，请通过标准手机关联核对");
        if(dto.productCode()!=null&&!dto.productCode().isBlank()&&!Objects.equals(current.get("product_code"),dto.productCode().trim()))throw invalid("已有资料编码不可改写，请保留原身份");
        String specific=empty(dto.productSpecificCode()),priorSpecific=empty(Objects.toString(current.get("product_specific_code"),null));
        boolean firstCode=priorSpecific==null&&specific!=null&&!source&&"product_line".equals(current.get("product_type"));
        if(firstCode)requireSpecificCode(specific,id);
        else if(!Objects.equals(priorSpecific,specific))throw invalid("已有产品特定编码不可改写，请保留原身份");
        // Parent, standard phone and already-assigned business codes never change through maintenance.
        if(!Objects.equals(current.get("product_type"),dto.productType())||!Objects.equals(number(current.get("parent_product_id")),dto.parentProductId())||!Objects.equals(number(current.get("phone_model_member_id")),dto.phoneModelId()))throw invalid("已有编码、所属产品与标准手机身份不可改绑，请保留原身份");
        jdbc.update("update plm_product set product_name=?,model=?,product_specific_code=?,material=?,remark=?,master_data_version=master_data_version+1,master_data_reason=?,master_data_updated_at=now(),master_data_updated_by=?,updated_at=now(),updated_by=? where product_id=?",source?current.get("product_name"):dto.productName().trim(),source?current.get("model"):empty(dto.model()),specific,empty(dto.material()),empty(dto.remark()),dto.reason().trim(),access.actor().username(),access.actor().username(),id);
        var result=productView(row("product",id,false,false));log("product",id,"update",dto.requestId(),dto.reason(),dto,result,request);return result;
    }
    private void validateInput(ProductInput dto,boolean create,Map<String,Object> existing){
        if(dto.productName()==null||dto.productName().isBlank()||dto.reason()==null||dto.reason().isBlank()||dto.requestId()==null)throw invalid("请填写名称及维护原因");
        if(!(create?Set.of("product_line","model_variant"):Set.of("product_line","model_variant","sku")).contains(Objects.toString(dto.productType(),"")))throw invalid("请选择产品平台或型号资料");
        if(create&&dto.productType().equals("model_variant")&&(dto.parentProductId()==null||dto.phoneModelId()==null))throw invalid("型号资料须明确选择所属产品及标准手机，编码待分配时保留为空");
        if(create&&dto.productType().equals("product_line")&&(dto.parentProductId()!=null||dto.phoneModelId()!=null))throw invalid("产品平台不绑定单个手机型号");
        if(create&&empty(dto.productSpecificCode())!=null)requireSpecificCode(dto.productSpecificCode(),null);
    }
    private void requireSpecificCode(String code,Long self){
        if(!code.matches("[A-Z]{2}"))throw invalid("产品特定编码须为两位大写字母");
        jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(?,0))",Object.class,"product-specific:"+code);
        if(jdbc.queryForObject("select count(*) from plm_product where product_type='product_line' and product_specific_code=? and (?::bigint is null or product_id<>?)",Long.class,code,self,self)>0)throw invalid("产品特定编码已被现有或历史资料占用");
    }
    private Map<String,Object> phone(Long id){
        if(id==null)return Map.of();
        var member=phones.requireEnabledModels(List.of(id)).get(id);
        var out=new LinkedHashMap<String,Object>();out.put("model",member.get("modelName"));out.put("phone_model_code",member.get("phoneModelCode"));return out;
    }
    public Map<String,Object> inspect(String kind,long id){read(kind);return inspection(kind,id,row(kind,id,false,false));}
    private Map<String,Object> inspection(String kind,long id,Map<String,Object> record){
        var refs=references(kind,id);boolean used=!refs.groups.isEmpty()||refs.restricted;
        boolean active="active".equals(record.get("master_data_status"));boolean maintain=access.canMaintainEngineeringData();var out=new LinkedHashMap<String,Object>();
        out.put("id",id);out.put("name",name(kind,record));out.put("code",code(kind,record));out.put("status",active?"active":"inactive");out.put("version",record.get("master_data_version"));out.put("canDelete",maintain&&!used);out.put("canDeactivate",maintain&&active);out.put("canActivate",maintain&&!active);out.put("references",refs.groups);out.put("hasRestrictedReferences",refs.restricted);if(used)out.put("blockedReason","已有业务或历史引用，请停用并保留原记录");return out;
    }
    @Transactional public Map<String,Object> perform(String kind,long id,String action,Action dto,HttpServletRequest request){
        read(kind);access.requireEngineeringMaintenance();if(!Set.of("delete","deactivate","activate").contains(action))throw invalid("操作无效");
        var current=row(kind,id,true,true);var replay=replay(kind,id,dto.requestId(),action,dto);if(replay!=null)return replay;
        if(((Number)current.get("deleted_flag")).intValue()!=0)throw invalid("资料已经删除，请刷新");version(current,dto.version());
        if(dto.reason()==null||dto.reason().isBlank())throw invalid("请填写原因");
        var check=inspection(kind,id,current);
        if(!Boolean.TRUE.equals(check.get("can"+Character.toUpperCase(action.charAt(0))+action.substring(1))))throw invalid(action.equals("delete")?"已有业务或历史引用，不能删除；可停用保留历史":"资料状态已变化，请刷新核对");
        jdbc.update("update "+table(kind)+" set master_data_status=?,deleted_flag=?,master_data_version=master_data_version+1,master_data_reason=?,master_data_updated_at=now(),master_data_updated_by=?,updated_at=now(),updated_by=? where "+pk(kind)+"=?",action.equals("activate")?"active":"inactive",action.equals("delete")?1:0,dto.reason().trim(),access.actor().username(),access.actor().username(),id);
        var result=new LinkedHashMap<String,Object>();result.put("id",id);result.put("status",action.equals("delete")?"deleted":action.equals("activate")?"active":"inactive");result.put("version",dto.version()+1);log(kind,id,action,dto.requestId(),dto.reason(),dto,result,request);return result;
    }
    private record Refs(List<Map<String,Object>> groups,boolean restricted) {}
    private Refs references(String kind,long id){
        var groups=new LinkedHashMap<String,List<Map<String,Object>>>();boolean restricted=false;
        // Discover only trusted typed ID columns. Historical/deleted owners deliberately remain references.
        Set<String> columns=switch(kind){case "product"->Set.of("product_id","project_id","product_line_id","parent_product_id","source_product_id","project_product_id","source_project_id");case "item"->Set.of("item_id","collaboration_item_id","target_item_id");case "mold_set"->Set.of("mold_set_id","source_mold_set_id","target_mold_set_id");default->throw new IllegalArgumentException("kind");};
        var foreignKeys=new HashSet<String>();
        jdbc.queryForList("select rel.relname as table_name,a.attname as column_name from pg_constraint fk join pg_class rel on rel.oid=fk.conrelid join pg_namespace n on n.oid=rel.relnamespace cross join lateral unnest(fk.conkey,fk.confkey) as cols(local_num,target_num) join pg_attribute a on a.attrelid=fk.conrelid and a.attnum=cols.local_num join pg_attribute target on target.attrelid=fk.confrelid and target.attnum=cols.target_num where fk.contype='f' and n.nspname=current_schema() and fk.confrelid=to_regclass(?) and target.attname=?",table(kind),pk(kind)).forEach(fk->foreignKeys.add(fk.get("table_name")+":"+fk.get("column_name")));
        var metadata=jdbc.queryForList("select table_name,column_name from information_schema.columns where table_schema=current_schema() and data_type in ('bigint','integer') and table_name like 'plm_%' order by table_name,column_name");
        for(var m:metadata){String t=m.get("table_name").toString(),c=m.get("column_name").toString();if((!columns.contains(c)&&!foreignKeys.contains(t+":"+c))||!t.matches("plm_[a-z0-9_]+"))continue;
            if(t.equals(table(kind))&&c.equals(pk(kind)))continue;
            if(t.equals("plm_collaboration_history"))continue; // handled below, creation/lifecycle entries are not adoption.
            String extra="";
            if(kind.equals("mold_set")&&t.equals("plm_collaboration_mold_part"))extra=" and (inventory_id is not null or product_mold_code_id is not null or original_mold_code is not null or status<>'pending' or coalesce(physical_status,'unknown')<>'unknown' or coalesce(current_location,'unknown')<>'unknown' or factory_arrival_date is not null or acceptance_status<>'pending')";
            for(var row:jdbc.queryForList("select to_jsonb(r)::text as data from "+t+" r where "+c+"=?"+extra,id)){
                var data=parse(row.get("data"));var ref=reference(t,data,kind,id);if(ref==null){restricted=true;continue;}
                var list=groups.computeIfAbsent(t,k->new ArrayList<>());if(list.stream().noneMatch(a->a.get("id").equals(ref.get("id"))))list.add(ref);
            }
        }
        // Polymorphic document/source identities are not database foreign keys.
        var typed=List.of(new String[]{"plm_document_reference","object_type","object_id"},new String[]{"plm_master_data_source","object_type","object_id"},new String[]{"plm_attachment","owner_object_type","owner_object_id"});
        Set<String> types=kind.equals("product")?Set.of("product","project","sku"):kind.equals("item")?Set.of("item","collaboration_item"):Set.of("mold_set","mold");
        for(var t:typed){if(!existsTable(t[0]))continue;for(String type:types)for(var raw:jdbc.queryForList("select to_jsonb(r)::text as data from "+t[0]+" r where lower("+t[1]+")=? and "+t[2]+"=?",type,id)){var data=parse(raw.get("data"));var ref=reference(t[0],data,kind,id);if(ref==null)restricted=true;else {var list=groups.computeIfAbsent(t[0],k->new ArrayList<>());if(list.stream().noneMatch(a->a.get("id").equals(ref.get("id"))))list.add(ref);}}}
        for(String type:types){
            String needle=encode(List.of(Map.of("type",type,"id",id)));
            for(var raw:jdbc.queryForList("select to_jsonb(h)::text as data from plm_document_reference_history h where before_references @> ?::jsonb or after_references @> ?::jsonb",needle,needle)){
                var ref=reference("plm_document_reference_history",parse(raw.get("data")),kind,id);if(ref==null)restricted=true;else {var list=groups.computeIfAbsent("plm_document_reference_history",k->new ArrayList<>());if(list.stream().noneMatch(a->a.get("id").equals(ref.get("id"))))list.add(ref);}
            }
            for(var raw:jdbc.queryForList("select to_jsonb(a)::text as data from plm_attachment a where publication_references_json is not null and publication_references_json::jsonb @> ?::jsonb",needle)){
                var ref=reference("plm_attachment",parse(raw.get("data")),kind,id);if(ref==null)restricted=true;else {var list=groups.computeIfAbsent("plm_attachment",k->new ArrayList<>());if(list.stream().noneMatch(a->a.get("id").equals(ref.get("id"))))list.add(ref);}
            }
        }
        if(!kind.equals("product")){
            String owner=kind.equals("item")?"item_id":"entity_id";
            var history=jdbc.queryForList("select to_jsonb(h)::text as data from plm_collaboration_history h where "+owner+"=? and field_name in ('compatiblePhones','projects','pcPhysicalStatus','tpuPhysicalStatus','pcInventoryId','tpuInventoryId','pcStatus','tpuStatus','openedDate','actualShipDate','actualMexicoArrivalDate','pcAcceptanceStatus','tpuAcceptanceStatus')"+(kind.equals("mold_set")?" and entity_type='mold_set'":""),id);
            for(var raw:history){var ref=reference("plm_collaboration_history",parse(raw.get("data")),kind,id);if(ref==null)restricted=true;else groups.computeIfAbsent("plm_collaboration_history",k->new ArrayList<>()).add(ref);}
        }
        if(!kind.equals("product")) {
            var own=row(kind,id,false,false);
            var evidence=kind.equals("item")?List.of("pc_total","tpu_total","finished_total"):List.of("opened_date","actual_ship_date","actual_mexico_arrival_date","actual_factory_arrival_date","quote_amount");
            if(evidence.stream().anyMatch(key->own.get(key) instanceof Number n?n.doubleValue()>0:own.get(key)!=null))groups.computeIfAbsent("physical_facts",k->new ArrayList<>()).add(Map.of("id",id,"label","已有制作、物流、费用或生产事实，请停用保留原记录","history",true));
        }
        if(kind.equals("product")){
            var p=row(kind,id,false,false);if(p.get("project_kind")!=null||p.get("source_instance_id")!=null){groups.computeIfAbsent("project",k->new ArrayList<>()).add(Map.of("id",id,"label","已登记项目／审批身份 · "+name(kind,p),"targetPath","/projects?productId="+id));}
        }
        var out=new ArrayList<Map<String,Object>>();groups.forEach((t,items)->out.add(Map.of("kind",t,"label",label(t),"count",items.size(),"items",items)));return new Refs(out,restricted);
    }
    private Map<String,Object> reference(String t,Map<String,Object> r,String targetKind,long targetId){
        if(!access.actor().allPermissions()&&(t.contains("cost")||t.contains("expense")||t.contains("invoice")||t.contains("finance"))&&!access.hasPermission("finance:read"))return null;
        if(t.equals("plm_mold_application")){
            if(!access.hasPermission("collaboration:view"))return null;
            boolean visible=access.access().allObjects()||Objects.equals(number(r.get("applicant_id")),access.actorId());
            if(!visible&&r.get("item_id")!=null)visible=jdbc.queryForObject("select count(*) from plm_collaboration_item a where item_id=? and ("+access.visibleSqlClause("item","a.item_id")+")",Long.class,r.get("item_id"))>0;
            if(!visible)return null;
            var ref=new LinkedHashMap<String,Object>();ref.put("id",t+":"+r.get("application_id"));ref.put("label","开模申请 · "+Objects.toString(first(r,"approval_no","title","model"),"#"+r.get("application_id")));boolean history=number(r.get("deleted_flag"))!=null&&number(r.get("deleted_flag"))!=0;ref.put("history",history);if(!history)ref.put("targetPath","/mold-applications?applicationId="+r.get("application_id"));return ref;
        }
        String ownerKind=null;Long owner=null;boolean ownerDeleted=false;
        if(t.equals("plm_attachment")||r.get("family_id")!=null){ownerKind="file";owner=firstNumber(r,"family_id","attachment_id");}
        else if(r.get("product_id")!=null||r.get("project_id")!=null||r.get("project_product_id")!=null){ownerKind="product";owner=firstNumber(r,"project_id","project_product_id","product_id");}
        else if(r.get("item_id")!=null){ownerKind="item";owner=number(r.get("item_id"));}
        else if(r.get("mold_set_id")!=null){ownerKind="mold";owner=number(r.get("mold_set_id"));}
        if(ownerKind==null&&r.get("object_type")!=null&&r.get("object_id")!=null){String type=r.get("object_type").toString();if(Set.of("product","sku","project","item","mold_set").contains(type)){ownerKind=type.equals("mold_set")?"mold":type.equals("sku")||type.equals("project")?"product":type;owner=number(r.get("object_id"));}}
        if(ownerKind!=null&&owner!=null){
            if(ownerKind.equals("file")&&!access.hasPermission("file:read"))return null;
            if(Set.of("item","mold").contains(ownerKind)&&!access.hasPermission("collaboration:view"))return null;
            if(ownerKind.equals("product")&&!access.hasPermission("product:view")&&!access.hasPermission("project:view"))return null;
            if(t.equals("plm_process")&&!access.hasPermission("process:view"))return null;
            String expr="r."+pk(ownerKind.equals("mold")?"mold_set":ownerKind.equals("file")?"product":ownerKind);if(ownerKind.equals("file"))expr="r.attachment_id";String ownerTable=ownerKind.equals("file")?"plm_attachment":table(ownerKind.equals("mold")?"mold_set":ownerKind);String ownerPk=ownerKind.equals("file")?"attachment_id":pk(ownerKind.equals("mold")?"mold_set":ownerKind);
            if(!access.actor().allPermissions()&&jdbc.queryForObject("select count(*) from "+ownerTable+" r where "+ownerPk+"=? and ("+access.visibleSqlClause(ownerKind,expr)+")",Long.class,owner)==0)return null;
            ownerDeleted=jdbc.queryForObject("select count(*) from "+ownerTable+" where "+ownerPk+"=? and deleted_flag=0",Long.class,owner)==0;
        }else if(!access.actor().allPermissions())return null;
        var keys=referenceKeys.computeIfAbsent(t,key->jdbc.queryForList("select a.attname from pg_index i cross join lateral unnest(i.indkey) with ordinality k(attnum,position) join pg_attribute a on a.attrelid=i.indrelid and a.attnum=k.attnum where i.indrelid=to_regclass(?) and i.indisprimary order by k.position",String.class,key));
        String identifier=keys.isEmpty()?inputHash(r):String.join("/",keys.stream().map(key->Objects.toString(r.get(key),"null")).toList());
        String text=first(r,"product_name","file_name","set_name","mold_name","style_name","bom_name","process_name","source_code","field_name");String code=first(r,"product_code","mold_code","bom_code","process_code","tracking_no");
        var ref=new LinkedHashMap<String,Object>();ref.put("id",t+":"+identifier);ref.put("label",label(t)+" · "+Objects.toString(text,"记录 #"+identifier)+(code==null?"":" · "+code));boolean deleted=number(r.get("deleted_flag"))!=null&&number(r.get("deleted_flag"))!=0;ref.put("history",deleted||t.contains("history"));
        if(!deleted&&!ownerDeleted&&ownerKind!=null&&owner!=null){String path=switch(ownerKind){case "product"->"/projects?productId="+owner;case "item"->"/molds?itemId="+owner;case "mold"->"/molds?moldSetId="+owner;case "file"->"/files?attachmentId="+owner;default->null;};if(path!=null)ref.put("targetPath",path);}return ref;
    }
    private boolean existsTable(String table){return jdbc.queryForObject("select to_regclass(?) is not null",Boolean.class,table);}
    private Map<String,Object> replay(String kind,long id,UUID requestId,String action,Object input){
        if(requestId==null)throw invalid("缺少请求标识");var rows=jdbc.queryForList("select detail_json from plm_operation_log where business_type='MASTER_DATA' and business_id=? and action='MASTER_DATA_MAINTENANCE' and detail_json::jsonb->>'requestId'=?",kind+":"+id,requestId.toString());
        if(rows.isEmpty())return null;var detail=parse(rows.get(0).get("detail_json"));if(!action.equals(detail.get("action"))||!inputHash(input).equals(detail.get("inputHash")))throw invalid("请求标识已用于不同操作，请刷新后重试");var result=json.convertValue(detail.get("result"),new TypeReference<Map<String,Object>>(){});for(String key:List.of("id","version","parentProductId","phoneModelId"))if(result.get(key) instanceof Number value)result.put(key,value.longValue());return result;
    }
    private void log(String kind,long id,String action,UUID requestId,String reason,Object input,Map<String,Object> result,HttpServletRequest request){
        var detail=new LinkedHashMap<String,Object>();detail.put("kind",kind);detail.put("action",action);detail.put("requestId",requestId);detail.put("reason",reason.trim());detail.put("input",input);detail.put("inputHash",inputHash(input));detail.put("result",result);
        audit.logRequired(OperationLogCreateCommand.builder().action("MASTER_DATA_MAINTENANCE").businessType("MASTER_DATA").businessId(kind+":"+id).businessCode(Objects.toString(result.get("code"),null)).businessName(Objects.toString(result.get("name"),null)).detailJson(encode(detail)).request(request).build(),access.actor());
        if(!kind.equals("product")){long item=kind.equals("item")?id:jdbc.queryForObject("select item_id from plm_collaboration_mold_set where mold_set_id=?",Long.class,id);jdbc.update("insert into plm_collaboration_history(item_id,entity_type,entity_id,field_name,before_value,after_value,reason,actor_id,actor_name) values(?,?,?,'__master_data_lifecycle',null,?::jsonb,?,?,?)",item,kind,id,encode(result),reason.trim(),access.actorId(),access.actor().username());}
    }
    private static void version(Map<String,Object> r,long expected){if(((Number)r.get("master_data_version")).longValue()!=expected)throw new BusinessException(40901,"资料已被其他人修改，请刷新后重新核对");}
    private static String name(String kind,Map<String,Object> r){return Objects.toString(r.get(kind.equals("product")?"product_name":kind.equals("item")?"mold_name":"set_name"),Objects.toString(r.get("style_name"),"未命名资料"));}
    private static String code(String kind,Map<String,Object> r){return Objects.toString(r.get(kind.equals("product")?"product_code":kind.equals("item")?"engraved_code":"mold_set_id"),"");}
    private static String empty(String s){return s==null||s.isBlank()?null:s.trim();}
    private static Long number(Object n){return n==null?null:n instanceof Number v?v.longValue():Long.parseLong(n.toString());}
    private static Long firstNumber(Map<String,Object> r,String... keys){for(String key:keys)if(r.get(key)!=null)return number(r.get(key));return null;}
    private static String first(Map<String,Object> r,String... keys){for(String k:keys)if(r.get(k)!=null&&!r.get(k).toString().isBlank())return r.get(k).toString();return null;}
    private static String label(String t){return switch(t){case "plm_collaboration_item_project","project"->"关联项目";case "plm_collaboration_item"->"模具主档";case "plm_collaboration_mold_set"->"模具实物套";case "plm_collaboration_mold_part"->"模具部件与实物事实";case "plm_collaboration_history"->"历史修改记录";case "plm_collaboration_phone_set_compatibility","plm_collaboration_phone_member"->"手机适配关系";case "plm_document_reference","plm_attachment"->"图纸与附件";case "plm_document_reference_history"->"历史图纸关联";case "plm_master_data_source"->"来源系统关联";case "plm_product"->"产品／型号资料";case "plm_process"->"工艺路线";case "plm_product_bom"->"BOM";case "plm_mold_application"->"开模申请";default->t.replace("plm_","");};}
    private String inputHash(Object value){try{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(encode(value).getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private Map<String,Object> parse(Object raw){try{return json.readValue(raw.toString(),new TypeReference<Map<String,Object>>(){});}catch(Exception e){throw new IllegalStateException("资料引用解析失败",e);}}
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("资料审计编码失败",e);}}
    private static BusinessException invalid(String text){return new BusinessException(40001,text);}
}
