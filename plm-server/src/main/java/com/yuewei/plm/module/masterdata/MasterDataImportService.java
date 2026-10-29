package com.yuewei.plm.module.masterdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuewei.plm.common.security.CurrentUser;
import com.yuewei.plm.module.moldreference.PhoneIdentityMatcher;
import com.yuewei.plm.module.moldreference.PhoneModelReferenceService;
import com.yuewei.plm.module.moldreference.PhoneModelReferenceTypes.*;
import com.yuewei.plm.module.operationlog.service.*;
import com.yuewei.plm.module.partner.PartnerProductService;
import com.yuewei.plm.module.pilot.PilotAccessService;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.yuewei.plm.module.masterdata.MasterDataSnapshot.*;

/** Local, explicit migration adapter into the existing objects, never a remote synchronizer. */
@Service @RequiredArgsConstructor
public class MasterDataImportService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final MasterDataSnapshotStore store;
    private final PilotAccessService access;
    private final PhoneModelReferenceService phones;
    private final PartnerProductService partners;
    private final OperationLogService audit;
    private static final List<String> LOCKED_TABLES=List.of("plm_product","plm_inventory","plm_process","plm_product_bom","plm_product_bom_route","plm_product_bom_item","plm_code_item","plm_phone_brand","plm_phone_model_member","plm_partner_record","plm_partner_spec","plm_master_data_source","plm_product_phone_model","plm_process_edge","plm_bom_item_target");

    public record PreviewRequest(String snapshotName,List<String> seriesCodes) {}
    public record CommitRequest(UUID importId,String sourceHash,String originalHash,String reason) {}

    @Transactional public Map<String,Object> preview(PreviewRequest input) {
        access.requireAdministrator();var loaded=store.load(input.snapshotName());var snapshot=loaded.snapshot();
        require(jdbc.queryForObject("select count(*) from plm_master_data_source where organization_id<>?",Long.class,snapshot.organization())==0,"本地已存在其他组织来源；首期不支持跨组织合并");
        var scope=scope(snapshot,input.seriesCodes());String original=fingerprint();var report=report(loaded,scope);
        UUID id=UUID.randomUUID();
        // The source hash and scope both belong to the optimistic token.
        String token=MasterDataSnapshotStore.sha256((original+"\n"+String.join(",",scope)).getBytes(StandardCharsets.UTF_8));
        var existing=jdbc.queryForList("select import_id,preview::text from plm_master_data_import where source_hash=? and actor_id=? and original_hash=?",loaded.hash(),access.actorId(),token);
        if(!existing.isEmpty())id=(UUID)existing.get(0).get("import_id");
        else jdbc.update("insert into plm_master_data_import(import_id,actor_id,snapshot_name,source_hash,original_hash,preview) values(?,?,?,?,?,?::jsonb)",id,access.actorId(),loaded.name(),loaded.hash(),token,encode(report));
        var result=new LinkedHashMap<String,Object>(report);result.put("importId",id);result.put("sourceHash",loaded.hash());result.put("originalHash",token);return result;
    }

    @Transactional public Map<String,Object> commit(CommitRequest input,HttpServletRequest request) {
        access.requireAdministrator();store.requireEnabled();require(input!=null&&input.importId()!=null&&input.reason()!=null&&!input.reason().isBlank()&&input.reason().length()<=1000,"请填写本地导入原因");
        jdbc.execute("select pg_advisory_xact_lock(66817416)");
        jdbc.execute("select pg_advisory_xact_lock(66817399)");
        var rows=jdbc.queryForList("select * from plm_master_data_import where import_id=? and actor_id=? for update",input.importId(),access.actorId());require(rows.size()==1,"导入预览不存在或不属于当前用户");var batch=rows.get(0);
        require(Objects.equals(batch.get("source_hash"),input.sourceHash())&&Objects.equals(batch.get("original_hash"),input.originalHash()),"预览校验值不一致，请重新预览");
        if("committed".equals(batch.get("status")))return decode(batch.get("result"));
        var loaded=store.load(batch.get("snapshot_name").toString());require(loaded.hash().equals(input.sourceHash()),"来源快照已变化，请重新导出并预览");
        var preview=decode(batch.get("preview"));List<String> scope=((List<?>)preview.get("seriesCodes")).stream().map(Object::toString).toList();
        jdbc.execute("lock table "+String.join(",",LOCKED_TABLES)+" in share row exclusive mode");
        String current=MasterDataSnapshotStore.sha256((fingerprint()+"\n"+String.join(",",scope)).getBytes(StandardCharsets.UTF_8));
        require(current.equals(input.originalHash()),"本地主资料在预览后已变化；本批未写入，请重新预览");
        CurrentUser actor=access.actor();var writer=new Writer(loaded,actor,request,scope);writer.apply();
        var result=new LinkedHashMap<String,Object>(preview);result.put("status","committed");result.put("importId",input.importId());result.put("sourceHash",loaded.hash());result.put("written",writer.counts);result.put("remoteBusinessWrites",0);
        audit.logRequired(OperationLogCreateCommand.builder().action("MASTER_DATA_IMPORT").businessType("MASTER_DATA").businessId(input.importId().toString()).detailJson(encode(Map.of("sourceHash",loaded.hash(),"originalHash",input.originalHash(),"reason",input.reason(),"result",result))).request(request).build(),actor);
        jdbc.update("update plm_master_data_import set status='committed',committed_at=now(),result=?::jsonb where import_id=?",encode(result),input.importId());return result;
    }

    private List<String> scope(MasterDataSnapshot source,List<String> requested){
        var available=source.rows("crm","series").stream().map(s->required(s,"seriesCode")).sorted().toList();
        var chosen=requested==null||requested.isEmpty()?available:requested.stream().distinct().sorted().toList();require(!chosen.isEmpty()&&available.containsAll(chosen),"产品范围不在本期来源清单中");return chosen;
    }
    private Map<String,Object> report(MasterDataSnapshotStore.Loaded loaded,List<String> scope){
        var s=loaded.snapshot();var skus=s.rows("crm","skus").stream().filter(v->scope.contains(text(s.series(v),"seriesCode"))).toList();
        var duplicates=new TreeMap<String,List<JsonNode>>();skus.forEach(v->duplicates.computeIfAbsent(text(v,"itemCode"),k->new ArrayList<>()).add(v));
        var warnings=new ArrayList<Map<String,Object>>();duplicates.forEach((code,values)->{if(values.size()>1)warnings.add(Map.of("kind","duplicateItemCode","itemCode",code,"sourceIds",values.stream().map(v->text(v,"id")).toList(),"phoneIds",values.stream().map(v->text(v,"phoneModelId")).distinct().toList(),"resolution","保留每条 CRM 身份；同制造品目关联原对象，不覆盖唯一手机关系"));});
        var gaps=new ArrayList<Map<String,Object>>();for(var sku:skus)if(!s.purchased(sku)){
            var routes=s.routesFor(sku);if(routes.isEmpty()||routes.stream().allMatch(r->s.bomFor(sku,text(r,"route_code")).isEmpty()))gaps.add(Map.of("sourceId",required(sku,"id"),"itemCode",required(sku,"itemCode"),"reason","MES 缺少适用 BOM／路线；保留缺项，不生成假配方"));
        }
        var result=new LinkedHashMap<String,Object>(Map.of("seriesCodes",scope,"counts",loaded.manifest().path("counts"),"skuCount",skus.size(),"listedCount",skus.stream().filter(s::listed).count(),"unlistedCount",skus.stream().filter(v->!s.listed(v)).count(),"purchasedCount",skus.stream().filter(s::purchased).count(),"warnings",warnings,"gaps",gaps,"remoteBusinessWrites",0,"preserved",List.of("原对象ID与历史关系","人工生产／销售状态","财务库存执行事实","旧发布BOM及样品采用快照")));
        result.put("existingSourceIdentities",jdbc.queryForObject("select count(*) from plm_master_data_source where organization_id=?",Long.class,s.organization()));
        result.put("unitDifferences",s.rows("mes","materials").stream().map(m->{String code=required(m,"material_code");var local=jdbc.queryForList("select stock_uom from plm_inventory where inventory_code=?",String.class,code);return Map.of("materialCode",code,"mesRawUnit",required(m,"unit"),"mesEffectiveUnit",required(s.data().path("mes").path("effective_units"),code),"existingStockUnit",local.isEmpty()?"未建物料":local.get(0),"action","保留原单位及来源投影，不隐式换算库存数量");}).filter(v->!v.get("mesEffectiveUnit").equals(v.get("existingStockUnit"))||!v.get("mesRawUnit").equals(v.get("mesEffectiveUnit"))).toList());
        return result;
    }
    private String fingerprint(){
        var values=new ArrayList<String>();for(String table:LOCKED_TABLES)values.add(table+":"+jdbc.queryForObject("select md5(coalesce(string_agg(row_value,E'\\n' order by row_value),'')) from (select row_to_json(t)::text row_value from "+table+" t) q",String.class));
        return MasterDataSnapshotStore.sha256(String.join("\n",values).getBytes(StandardCharsets.UTF_8));
    }
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("主资料证据编码失败",e);}}
    @SuppressWarnings("unchecked") private Map<String,Object> decode(Object value){try{return json.readValue(value.toString(),LinkedHashMap.class);}catch(Exception e){throw new IllegalStateException("主资料证据解析失败",e);}}

    /** An unchanged erroneous upstream name cannot undo a verified engineering correction. */
    boolean verifiedPhoneOverride(long id,long sourceBrand,String sourceName,Map<String,Object> canonical) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from plm_operation_log where business_type='PHONE_IDENTITY_REVIEW' and result='success' and deleted_flag=0 and detail_json::jsonb->>'key'=? and detail_json::jsonb->>'state'='resolved' and detail_json::jsonb#>>'{issue,sourceModelName}'=? and detail_json::jsonb#>>'{issue,sourceBrandId}'=? and detail_json::jsonb#>>'{issue,after,modelName}'=? and detail_json::jsonb#>>'{issue,after,brandId}'=?)",Boolean.class,"member:"+id,sourceName,String.valueOf(sourceBrand),canonical.get("modelName"),String.valueOf(canonical.get("brandId"))));
    }
    private final class Writer {
        final MasterDataSnapshotStore.Loaded loaded;final MasterDataSnapshot s;final CurrentUser actor;final HttpServletRequest request;final List<String> scope;
        final Map<String,Long> brandIds=new HashMap<>(),phoneIds=new HashMap<>(),lineIds=new HashMap<>(),operationIds=new HashMap<>(),routeIds=new HashMap<>(),nodeIds=new HashMap<>(),materialIds=new HashMap<>();
        final Map<String,Integer> counts=new TreeMap<>();
        Writer(MasterDataSnapshotStore.Loaded loaded,CurrentUser actor,HttpServletRequest request,List<String> scope){this.loaded=loaded;s=loaded.snapshot();this.actor=actor;this.request=request;this.scope=scope;}
        void apply(){referenceData();products();manufacturing();skus();}
        void referenceData(){
            for(var b:s.rows("crm","brands")){
                Long id=mapped("crm","phone_brand",b);String name=required(b,"brandName");
                if(id==null)id=uniqueId("select id from plm_phone_brand where lower(btrim(name))=lower(?) or exists(select 1 from unnest(aliases) a where lower(a)=lower(?))",name,name);
                if(id==null)id=number(phones.createBrand(new CreateBrand(name),request).get("id"));
                else {
                    MasterDataAdoption.requireSourceObject(jdbc,"plm_phone_brand","id",id);
                    var existing=phones.brand(id);
                    if(!name.equals(existing.get("name"))){
                        var aliases=new ArrayList<String>((List<String>)existing.getOrDefault("aliases",List.of()));aliases.add(existing.get("name").toString());
                        var patch=new Patch(Map.of("name",json.valueToTree(name),"aliases",json.valueToTree(aliases)),Map.of("name",json.valueToTree(existing.get("name")),"aliases",json.valueToTree(existing.get("aliases"))),"CRM 权威品牌名称对齐；原名称留作别名，保留人工前缀规则");
                        var result=phones.patchBrand(id,patch,request);require("saved".equals(result.get("status")),"品牌对齐发生原值冲突");
                    }
                }
                brandIds.put(required(b,"id"),id);save("crm","phone_brand",b,"phone_brand",id,name,null);
            }
            for(var p:s.rows("crm","phones")){
                Long id=mapped("crm","phone_model",p),code=null;String raw=text(p,"modelCode");
                if(raw!=null){code=uniqueId("select code_item_id from plm_code_item where code_type='phone_model' and code_value=?",raw);
                    if(code==null)code=insert("insert into plm_code_item(code_type,code_value,code_name,created_by,updated_by) values('phone_model',?,?,?,?) returning code_item_id",raw,required(p,"modelName"),actor.username(),actor.username());
                    else MasterDataAdoption.requireSourceObject(jdbc,"plm_code_item","code_item_id",code);}
                long brand=brandIds.get(required(p,"phoneBrandId"));String name=required(p,"modelName");
                if(id==null){var exact=jdbc.queryForList("select id,model_name,code_item_id from plm_phone_model_member where brand_id=? and (code_item_id=? or lower(btrim(model_name))=lower(?)) order by id",brand,code,name);
                    var b=phones.brand(brand);var key=PhoneIdentityMatcher.modelKey(name,b);var matches=exact.stream().filter(v->PhoneIdentityMatcher.modelKey(v.get("model_name").toString(),b).equals(key)).toList();
                    require(matches.size()<=1,"CRM 手机与多个原标准成员冲突，请先人工核对："+name);
                    if(!matches.isEmpty()){var row=matches.get(0);id=number(row.get("id"));require(row.get("code_item_id")==null||Objects.equals(number(row.get("code_item_id")),code),"CRM 手机编码与人工原编码不一致："+name);}
                }
                if(id!=null)MasterDataAdoption.requireSourceObject(jdbc,"plm_phone_model_member","id",id);
                if(id==null)id=number(phones.createPhone(new CreatePhone(brand,name,List.of(),code),request).get("id"));
                else if(code!=null){var existing=phones.phone(id);if(existing.get("codeItemId")==null){var update=phones.patchPhone(id,new Patch(Map.of("codeItemId",json.valueToTree(code)),Map.of("codeItemId",json.nullNode()),"CRM 原有编码对齐；不是新编号分配"),request);require("saved".equals(update.get("status")),"手机原编码关联发生冲突");}}
                var canonical=phones.phone(id);
                if(!name.equals(canonical.get("modelName"))&&!verifiedPhoneOverride(id,brand,name,canonical)){
                    var aliases=new ArrayList<String>((List<String>)canonical.getOrDefault("aliases",List.of()));aliases.add(canonical.get("modelName").toString());
                    var patch=new Patch(Map.of("modelName",json.valueToTree(name),"aliases",json.valueToTree(aliases)),Map.of("modelName",json.valueToTree(canonical.get("modelName")),"aliases",json.valueToTree(canonical.get("aliases"))),"CRM 权威机型名称对齐；保留原名称别名、原编码与生产状态");
                    var renamed=phones.patchPhone(id,patch,request);require("saved".equals(renamed.get("status")),"手机名称对齐发生原值冲突");
                }
                phoneIds.put(required(p,"id"),id);save("crm","phone_model",p,"phone_model",id,raw,null);
            }
            for(var c:s.rows("crm","colors")){
                String code=required(c,"colorShortCode");Long id=uniqueId("select code_item_id from plm_code_item where code_type='color' and code_value=?",code);
                if(id==null)id=insert("insert into plm_code_item(code_type,code_value,code_name,created_by,updated_by) values('color',?,?,?,?) returning code_item_id",code,required(c,"colorNameZh"),actor.username(),actor.username());
                MasterDataAdoption.requireSourceObject(jdbc,"plm_code_item","code_item_id",id);
                save("crm","product_color_encoding",c,"code_item",id,code,null);
            }
        }
        void products(){
            for(var p:s.rows("mes","products")){String code=required(p,"product_code");if(!scope.contains(code))continue;
                Long id=mapped("mes","mes_product",p);if(id==null)id=uniqueId("select product_id from plm_product where product_code=? and product_type='product_line'",code);
                if(id==null)id=insert("insert into plm_product(product_code,product_name,product_type,series_name,version_no,created_by,updated_by,source_system) values(?,?,'product_line',?,'V1',?,?,'mes') returning product_id",code,required(p,"product_name_cn"),text(p,"product_name_cn"),actor.username(),actor.username());
                else {MasterDataAdoption.requireSourceObject(jdbc,"plm_product","product_id",id);
                    jdbc.update("update plm_product set product_name=?,series_name=?,master_data_version=master_data_version+1,updated_by=?,updated_at=now() where product_id=?",required(p,"product_name_cn"),text(p,"product_name_cn"),actor.username(),id);}
                lineIds.put(code,id);save("mes","mes_product",p,"product",id,code,null);
            }
            for(var row:s.rows("crm","series")){String code=required(row,"seriesCode");if(!scope.contains(code)||!lineIds.containsKey(code))continue;save("crm","product_series",row,"product",lineIds.get(code),code,null);}
            var baseProducts=new HashMap<String,Long>();
            for(var row:s.rows("mes","item_codes")){String code=text(row,"product_code");if(lineIds.containsKey(code)){save("mes","mes_product_item_code",row,"product",lineIds.get(code),text(row,"base_code"),null);baseProducts.put(required(row,"base_code"),lineIds.get(code));}}
            for(var row:s.rows("mes","item_colors"))if(baseProducts.containsKey(text(row,"base_code")))save("mes","mes_product_item_color",row,"product",baseProducts.get(text(row,"base_code")),text(row,"color_code"),null);
        }
        void manufacturing(){
            if(lineIds.isEmpty())return;
            for(var m:s.rows("mes","materials")){
                String code=required(m,"material_code");Long id=mapped("mes","material_items",m);if(id==null)id=uniqueId("select inventory_id from plm_inventory where inventory_code=?",code);
                // 'material' is the PLM generic BOM-component category, not an inferred MES material_type.
                String unit=required(s.data().path("mes").path("effective_units"),code);
                if(id==null)id=insert("insert into plm_inventory(inventory_code,inventory_name,inventory_type,stock_uom,status,created_by,updated_by) values(?,?,'material',?,'draft',?,?) returning inventory_id",code,required(m,"material_name"),unit,actor.username(),actor.username());
                else {MasterDataAdoption.requireSourceObject(jdbc,"plm_inventory","inventory_id",id);
                    jdbc.update("update plm_inventory set inventory_name=?,version=version+1,updated_by=?,updated_at=now() where inventory_id=?",required(m,"material_name"),actor.username(),id);}
                materialIds.put(code,id);save("mes","material_items",m,"inventory",id,code,null);
            }
            for(var op:s.rows("mes","operations")){
                String code=required(op,"operation_code");Long id=mapped("mes","operation_templates",op);if(id==null)id=uniqueId("select process_id from plm_process where process_code=? and process_type='operation_master' and deleted_flag=0",code);
                if(id==null)id=insert("insert into plm_process(process_code,process_name,process_type,version_no,status,process_param_json,created_by,updated_by) values(?,?,'operation_master',?,'draft',?::jsonb,?,?) returning process_id",code,required(op,"operation_name"),required(op,"version"),encode(op),actor.username(),actor.username());
                operationIds.put(code,id);save("mes","operation_templates",op,"process",id,code,null);
            }
            var neededRoutes=new HashSet<String>();s.rows("mes","route_bindings").stream().filter(v->lineIds.containsKey(text(v,"product_code"))).forEach(v->neededRoutes.add(required(v,"route_code")));
            for(var r:s.rows("mes","routes"))if(neededRoutes.contains(text(r,"route_code"))){String code=required(r,"route_code");Long id=unchanged("mes","process_flows",r);
                if(id==null)id=insert("insert into plm_process(process_code,process_name,process_type,product_id,version_no,status,lock_status,frozen_at,frozen_by,process_param_json,created_by,updated_by) values(?,?,'routing',?,?,'locked','frozen',now(),?,?::jsonb,?,?) returning process_id",internalCode(code,r),required(r,"route_name"),lineIds.get(text(r,"product_code")),required(r,"version"),actor.username(),encode(Map.of("sourceSystem","mes","sourceRouteCode",code,"sourceStatus",required(r,"status"))),actor.username(),actor.username());
                routeIds.put(code,id);save("mes","process_flows",r,"process",id,code,null);
            }
            for(var n:s.rows("mes","nodes"))if(routeIds.containsKey(text(n,"route_code"))){String code=required(n,"node_code");Long id=unchanged("mes","process_flow_steps",n);long route=routeIds.get(text(n,"route_code"));
                if(id!=null&& !Objects.equals(route,jdbc.queryForObject("select parent_process_id from plm_process where process_id=?",Long.class,id)))id=null;
                if(id==null)id=insert("insert into plm_process(process_code,process_name,process_type,parent_process_id,operation_master_process_id,sequence_no,node_code,local_node_code,position_x,position_y,version_no,status,process_param_json,created_by,updated_by) values(?,?,'operation',?,?,?,?,?,?,?,'V1','locked',?::jsonb,?,?) returning process_id",internalCode("NODE-"+required(n,"id"),n),required(n,"step_name"),route,operationIds.get(required(n,"operation_code")),n.path("step_no").asInt(),internalCode(code,n),text(n,"local_node_code"),decimal(n,"position_x"),decimal(n,"position_y"),encode(n.path("config_json")),actor.username(),actor.username());
                nodeIds.put(code,id);save("mes","process_flow_steps",n,"process",id,code,null);
            }
            for(var edge:s.rows("mes","edges"))if(routeIds.containsKey(text(edge,"route_code"))){long route=routeIds.get(text(edge,"route_code")),from=nodeIds.get(required(edge,"from_node_code")),to=nodeIds.get(required(edge,"to_node_code"));
                jdbc.update("insert into plm_process_edge(route_id,from_process_id,to_process_id,edge_no,relation_type,label,condition_json) values(?,?,?,?,?,?,?::jsonb) on conflict(route_id,from_process_id,to_process_id) do nothing",route,from,to,edge.path("edge_no").asInt(),required(edge,"relation_type"),text(edge,"label"),encode(edge.path("condition_json")));
                long id=jdbc.queryForObject("select edge_id from plm_process_edge where route_id=? and from_process_id=? and to_process_id=?",Long.class,route,from,to);save("mes","process_flow_step_edges",edge,"process_edge",id,null,null);
            }
            for(var binding:s.rows("mes","route_bindings"))if(lineIds.containsKey(text(binding,"product_code")))save("mes","product_route_binding",binding,"process",routeIds.get(required(binding,"route_code")),text(binding,"route_code"),null);
            for(var bom:s.rows("mes","boms"))if(lineIds.containsKey(text(bom,"parent_code")))bom(bom);
        }
        void bom(JsonNode bom){
            String code=required(bom,"bom_code");long product=lineIds.get(required(bom,"parent_code"));Long id=unchanged("mes","bom_main",bom);
            if(id!=null){ensureColors(id);save("mes","bom_main",bom,"bom",id,code,null);return;}
            id=insert("insert into plm_product_bom(product_id,bom_code,bom_type,bom_name,quantity,output_inventory_code,output_inventory_name,version_no,status,bom_scope,source_type,frozen_flag,frozen_at,frozen_by,currency_code,conversion_rate,custom_bom_specification,is_active,created_by,updated_by) values(?,?,'mbom',?,?,?,?,?,'frozen','formal','mes_baseline',1,now(),?,?,?,?,1,?,?) returning product_bom_id",product,internalCode(code,bom),text(bom,"parent_name"),decimal(bom,"output_qty"),text(bom,"parent_code"),text(bom,"parent_name"),"MES-"+required(bom,"version")+"-"+required(bom,"id")+"-"+rowHash(bom).substring(0,8),actor.username(),text(bom,"currency"),nullableDecimal(bom,"exchange_rate"),text(bom,"custom_bom_specification"),actor.username(),actor.username());
            var links=new HashMap<String,Long>();
            for(var binding:s.bindings(code))if(lineIds.containsKey(text(binding,"product_code"))){var condition=binding.path("condition_json");var scope=condition.path("workbook_scope");
                for(String route:strings(scope.path("route_codes"))){require(!links.containsKey(route),"同一配方的路线存在多个绑定，需先核实优先级");long routeId=insert("insert into plm_product_bom_route(product_bom_id,product_id,process_id,route_code,route_name,shared_bom_group_code,route_variant_no,variant_source_type,article_codes,binding_priority,binding_condition,source_binding_id,created_by,updated_by) values(?,?,?,?,?,?,?,'mes',?::text[],?,?::jsonb,?,?,?) returning product_bom_route_id",id,product,routeIds.get(route),route,required(s.route(route),"route_name"),code,"MES-"+required(bom,"id"),array(strings(scope.path("article_codes"))),binding.path("priority").asInt(),encode(condition),required(binding,"id"),actor.username(),actor.username());links.put(route,routeId);save("mes","mes_product_bom_binding",binding,"bom_route",routeId,code,null);}
            }
            ensureColors(id);
            for(var item:s.items(code)){
                String material=required(item,"child_code_normalized");long itemId=insert("insert into plm_product_bom_item(product_bom_id,product_id,inventory_id,line_no,item_code,item_name,quantity,uom_code,unit,unit_raw,unit_cn,unit_es,component_row_id,child_code_normalized,unit_cost_snapshot,currency_code,version_no,status,material_source,created_by,updated_by) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'draft','mes',?,?) returning product_bom_item_id",id,product,materialIds.get(material),item.path("line_no").asInt(),required(item,"child_code"),required(item,"child_name"),decimal(item,"quantity"),required(item,"unit"),required(item,"unit"),text(item,"unit_raw"),text(item,"unit_cn"),text(item,"unit_es"),text(item,"component_row_id"),material,nullableDecimal(item,"unit_price"),text(bom,"currency"),required(bom,"version"),actor.username(),actor.username());
                if(links.size()==1)jdbc.update("update plm_product_bom_item set product_bom_route_id=?,shared_bom_group_code=? where product_bom_item_id=?",links.values().iterator().next(),code,itemId);
                var targets=item.path("route_targets").fields();while(targets.hasNext()){var target=targets.next();String route=target.getKey();require(links.containsKey(route),"投料节点缺少对应 BOM 路线绑定");var spec=target.getValue();jdbc.update("insert into plm_bom_item_target(product_bom_item_id,product_bom_id,product_bom_route_id,route_process_id,process_id,article_codes) values(?,?,?,?,?,?::text[])",itemId,id,links.get(route),routeIds.get(route),nodeIds.get(required(spec,"node_code")),spec.has("article_codes")?array(strings(spec.path("article_codes"))):null);}
                save("mes","bom_child",item,"bom_item",itemId,text(item,"child_code"),null);
            }
            save("mes","bom_main",bom,"bom",id,code,null);
        }
        void skus(){
            for(var series:s.rows("crm","series")){String code=required(series,"seriesCode");if(!scope.contains(code))continue;
                var values=s.rows("crm","skus").stream().filter(v->code.equals(text(s.series(v),"seriesCode"))).toList();if(values.isEmpty())continue;
                if(s.purchased(values.get(0))){purchased(series,values);continue;}
                require(lineIds.containsKey(code),"自制系列缺少 MES 主档，不能制造假的产品主档："+code);
                for(var sku:values){String item=required(sku,"itemCode");Long id=mapped("crm","product_sku",sku);
                    if(id==null)id=uniqueId("select product_id from plm_product where (finished_product_code=? or product_code=?)",item,item);
                    if(id==null)id=insert("insert into plm_product(parent_product_id,product_code,product_name,product_type,series_name,model,color,version_no,finished_product_code,created_by,updated_by,source_system) values(?,?,?,'model_variant',?,?,?,'V1',?,?,?,'crm') returning product_id",lineIds.get(code),item,required(sku,"productName"),text(series,"seriesNameZh"),text(s.phone(sku),"modelName"),text(sku,"colorNameZh"),item,actor.username(),actor.username());
                    MasterDataAdoption.requireSourceObject(jdbc,"plm_product","product_id",id);
                    long phone=phoneIds.get(required(sku,"phoneModelId"));phones.requireEnabledModels(List.of(phone));jdbc.update("insert into plm_product_phone_model(product_id,phone_model_id,created_by) values(?,?,?) on conflict do nothing",id,phone,actor.username());
                    save("crm","product_sku",sku,"product",id,item,phone);
                }
            }
        }
        void purchased(JsonNode series,List<JsonNode> values){
            Long id=mapped("crm","product_series",series);var input=new LinkedHashMap<String,Object>();
            if(id!=null)input.putAll(partners.detail(id));input.put("name",required(series,"seriesNameZh"));input.put("requestId","crm-v2-"+required(series,"id")+"-"+loaded.hash().substring(0,8));input.put("site","crm");input.put("externalId",required(series,"id"));input.putIfAbsent("status","draft");input.put("listing",values.stream().anyMatch(s::listed)?"listed":"unlisted");input.putIfAbsent("notes","CRM 采购成品；供方、价格、币种未知，不要求自制 BOM。");
            var specs=new ArrayList<Map<String,Object>>();if(input.get("specs") instanceof List<?> previous)for(Object value:previous)specs.add(new LinkedHashMap<>((Map<String,Object>)value));
            for(var sku:values){String sourceId=required(sku,"id");var spec=specs.stream().filter(v->sourceId.equals(v.get("externalId"))||sourceId.equals(v.get("websiteSku"))).findFirst().orElseGet(()->{var v=new LinkedHashMap<String,Object>();specs.add(v);return v;});spec.put("websiteSku",required(sku,"itemCode"));spec.put("externalId",sourceId);spec.put("specification",required(sku,"itemCode")+" · "+Objects.toString(text(sku,"colorNameZh"),""));spec.put("phoneModelIds",List.of(phoneIds.get(required(sku,"phoneModelId"))));spec.putIfAbsent("sources",List.of());}
            input.put("specs",specs);var result=partners.save(id,input);id=number(result.get("id"));save("crm","product_series",series,"partner",id,text(series,"seriesCode"),null);
            var savedSpecs=(List<Map<String,Object>>)result.get("specs");
            for(var sku:values){String sourceId=required(sku,"id");var matching=savedSpecs.stream().filter(v->sourceId.equals(v.get("externalId"))).toList();require(matching.size()==1,"采购规格来源身份冲突，不能按品目码猜测");save("crm","product_sku",sku,"partner",id,text(sku,"itemCode"),phoneIds.get(required(sku,"phoneModelId")),number(matching.get(0).get("id")));}
        }
        Long mapped(String system,String kind,JsonNode row){return mapped(system,kind,row,false);}
        Long unchanged(String system,String kind,JsonNode row){return mapped(system,kind,row,true);}
        Long mapped(String system,String kind,JsonNode row,boolean sameHash){
            var records=jdbc.queryForList("select object_id,object_type,source_hash from plm_master_data_source where source_system=? and organization_id=? and source_kind=? and source_id=?",system,s.organization(),kind,required(row,"id"));
            require(records.size()<=1,"来源映射不唯一");if(records.isEmpty())return null;var record=records.get(0);long id=number(record.get("object_id"));
            String[] target=switch(record.get("object_type").toString()){
                case "product"->new String[]{"plm_product","product_id"};case "partner"->new String[]{"plm_partner_record","record_id"};
                case "phone_brand"->new String[]{"plm_phone_brand","id"};case "phone_model"->new String[]{"plm_phone_model_member","id"};
                case "inventory"->new String[]{"plm_inventory","inventory_id"};case "process"->new String[]{"plm_process","process_id"};
                case "bom"->new String[]{"plm_product_bom","product_bom_id"};default->throw invalid("未知主对象映射类型");};
            require(jdbc.queryForObject("select count(*) from "+target[0]+" v where "+target[1]+"=? and coalesce((to_jsonb(v)->>'deleted_flag')::int,0)=0",Long.class,id)==1,"原来源关联对象已删除或不存在，请人工核对，不自动重建："+kind+"/"+required(row,"id"));
            if(Set.of("plm_product","plm_inventory","plm_phone_brand","plm_phone_model_member").contains(target[0]))MasterDataAdoption.requireSourceObject(jdbc,target[0],target[1],id);
            return sameHash&&!rowHash(row).equals(record.get("source_hash"))?null:id;
        }
        void save(String system,String kind,JsonNode row,String type,long id,String code,Long phone){
            save(system,kind,row,type,id,code,phone,null);
        }
        void save(String system,String kind,JsonNode row,String type,long id,String code,Long phone,Long partnerSpec){
            ObjectNode data=row.deepCopy();if(kind.equals("product_sku")){data.put("_listingStatus",s.listed(row)?"listed":"unlisted");data.put("_purchased",s.purchased(row));data.put("_plmLineId",lineIds.getOrDefault(text(s.series(row),"seriesCode"),0L));}
            if(kind.equals("material_items")){String material=required(row,"material_code");data.put("_mesEffectiveUnit",required(s.data().path("mes").path("effective_units"),material));s.rows("mes","unit_projections").stream().filter(v->material.equals(text(v,"record_key"))).findFirst().ifPresent(v->data.set("_unitProjection",v));}
            jdbc.update("insert into plm_master_data_source(source_system,organization_id,source_kind,source_id,object_type,object_id,phone_model_id,source_code,source_status,source_hash,source_data,observed_at,batch_hash,partner_spec_id) values(?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?,?) on conflict(source_system,organization_id,source_kind,source_id) do update set object_type=excluded.object_type,object_id=excluded.object_id,phone_model_id=excluded.phone_model_id,source_code=excluded.source_code,source_status=excluded.source_status,source_hash=excluded.source_hash,source_data=excluded.source_data,observed_at=excluded.observed_at,batch_hash=excluded.batch_hash,partner_spec_id=excluded.partner_spec_id",system,s.organization(),kind,required(row,"id"),type,id,phone,code,text(row,"status"),rowHash(row),encode(data),OffsetDateTime.parse(required(s.data(),"capturedAt")),loaded.hash(),partnerSpec);counts.merge(system+":"+kind,1,Integer::sum);
        }
        String rowHash(JsonNode row){
            var parts=new ArrayList<Object>();parts.add(row);
            if(row.has("output_qty")){
                String code=required(row,"bom_code");parts.add(s.items(code));parts.add(s.bindings(code));
                var routes=new TreeSet<String>();s.items(code).forEach(i->i.path("route_targets").fieldNames().forEachRemaining(routes::add));
                for(String route:routes)parts.add(routeState(route));
            }else if(row.has("route_code")&&row.has("scenario")&&!row.has("priority"))parts.add(routeState(required(row,"route_code")));
            return MasterDataSnapshotStore.sha256(encode(parts).getBytes(StandardCharsets.UTF_8));
        }
        Object routeState(String code){return List.of(s.route(code),s.rows("mes","nodes").stream().filter(n->code.equals(text(n,"route_code"))).toList(),s.rows("mes","edges").stream().filter(n->code.equals(text(n,"route_code"))).toList());}
        String internalCode(String code,JsonNode row){return code+"-"+rowHash(row).substring(0,8)+(row.has("node_code")?"-R"+routeIds.get(text(row,"route_code")):"");}
        void ensureColors(long bomId){
            for(var link:jdbc.queryForList("select product_bom_route_id,article_codes from plm_product_bom_route where product_bom_id=? and deleted_flag=0",bomId)){
                long route=((Number)link.get("product_bom_route_id")).longValue();var articleCodes=new HashSet<>(com.yuewei.plm.module.bom.service.BomStructureService.stringsArray(link.get("article_codes")));
                var colors=new LinkedHashMap<String,String>();s.rows("crm","skus").stream().filter(v->articleCodes.contains(text(v,"itemCode"))).forEach(v->{if(text(v,"colorShortCode")!=null&&text(v,"colorNameZh")!=null)colors.put(text(v,"colorShortCode"),text(v,"colorNameZh"));});
                for(var color:colors.entrySet())if(jdbc.queryForObject("select count(*) from plm_product_bom_route_color where product_bom_route_id=? and color_code=? and deleted_flag=0",Long.class,route,color.getKey())==0){Long colorId=uniqueId("select code_item_id from plm_code_item where code_type='color' and code_value=?",color.getKey());MasterDataAdoption.requireCode(jdbc,"color",color.getKey());jdbc.update("insert into plm_product_bom_route_color(product_bom_id,product_bom_route_id,code_item_id,color_code,color_name,status,created_by,updated_by) values(?,?,?,?,?,'active',?,?)",bomId,route,colorId,color.getKey(),color.getValue(),actor.username(),actor.username());}
            }
        }
    }
    private static java.math.BigDecimal nullableDecimal(JsonNode row,String key){return text(row,key)==null?null:decimal(row,key);}
    private static Long number(Object value){return value==null?null:((Number)value).longValue();}
    private Long uniqueId(String sql,Object...args){var rows=jdbc.queryForList(sql,Long.class,args);require(rows.size()<=1,"本地存在多个同身份主对象，禁止静默合并");return rows.isEmpty()?null:rows.get(0);}
    private long insert(String sql,Object...args){return Objects.requireNonNull(jdbc.queryForObject(sql,Long.class,args));}
    private static String array(List<String> values){return "{"+String.join(",",values.stream().map(v->"\""+v.replace("\\","\\\\").replace("\"","\\\"")+"\"").toList())+"}";}
}
