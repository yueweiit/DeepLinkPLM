package com.yuewei.plm.module.bom.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuewei.plm.module.bom.dto.*;
import com.yuewei.plm.module.bom.entity.*;
import com.yuewei.plm.module.bom.repository.ProductBomRepository;
import com.yuewei.plm.module.bom.vo.*;
import com.yuewei.plm.module.masterdata.MasterDataSnapshotStore;
import com.yuewei.plm.module.operationlog.service.*;
import com.yuewei.plm.module.pilot.PilotAccessService;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.yuewei.plm.module.masterdata.MasterDataSnapshot.*;

/** Missing typed graph/scope relationships shared by the original BOM readers and writers. */
@Service @RequiredArgsConstructor
public class BomStructureService {
    private final JdbcTemplate jdbc;private final ObjectMapper json;private final PilotAccessService access;
    private final ProductBomRepository boms;private final OperationLogService audit;
    public static boolean sourceScoped(ProductBom bom){return Set.of("mes_baseline","mes_derivative").contains(Objects.toString(bom.getSourceType(),""));}
    public static boolean structured(ProductBom bom){return sourceScoped(bom)||"engineering_graph".equals(bom.getSourceType());}
    public ProductBomWorkbenchVO enrich(ProductBomWorkbenchVO value){
        if(value.getRoutes()==null)return value;String original=fingerprint(value.getProductBomId());
        for(var route:value.getRoutes()){
            var row=jdbc.queryForMap("select article_codes,binding_priority,binding_condition from plm_product_bom_route where product_bom_route_id=?",route.getProductBomRouteId());
            route.setArticleCodes(stringsArray(row.get("article_codes")));route.setBindingPriority(((Number)row.get("binding_priority")).intValue());route.setOriginalHash(original);
            if(row.get("binding_condition")!=null)try{route.setBindingCondition(json.readTree(row.get("binding_condition").toString()));}catch(Exception failure){throw new IllegalStateException("绑定条件解析失败",failure);}
            var items=jdbc.query("select i.*,i.uom_code as unit from plm_product_bom_item i where i.deleted_flag=0 and (i.product_bom_route_id=? or exists(select 1 from plm_bom_item_target t where t.product_bom_item_id=i.product_bom_item_id and t.product_bom_route_id=?)) order by line_no,product_bom_item_id",new BeanPropertyRowMapper<>(ProductBomItem.class),route.getProductBomRouteId(),route.getProductBomRouteId());
            var targetRows=jdbc.queryForList("select t.product_bom_item_id,t.process_id,t.article_codes,p.process_name,p.node_code from plm_bom_item_target t join plm_process p on p.process_id=t.process_id where t.product_bom_route_id=?",route.getProductBomRouteId());var targets=new HashMap<Long,Map<String,Object>>();targetRows.forEach(t->targets.put(((Number)t.get("product_bom_item_id")).longValue(),t));
            route.setItems(items.stream().map(item->{var vo=ProductBomItemVO.from(item);vo.setQuantityExact(item.getQuantity().toPlainString());var target=targets.get(item.getProductBomItemId());if(target!=null){vo.setNodeId(((Number)target.get("process_id")).longValue());vo.setNodeCode(Objects.toString(target.get("node_code"),null));vo.setNodeName(Objects.toString(target.get("process_name"),null));vo.setArticleCodes(stringsArray(target.get("article_codes")));}return vo;}).toList());
        }return value;
    }
    @Transactional public ProductBom copyVersion(ProductBom source,String version){
        access.requireEngineeringMaintenance();access.requirePermission("bom:write");access.requireProduct(source.getProductId());
        jdbc.queryForList("select product_bom_id from plm_product_bom where product_bom_id=? for update",source.getProductBomId());
        require(version!=null&&version.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,31}"),"请提供最多32位的明确新版本号");
        require(Set.of("frozen","released").contains(source.getStatus()),"只可复制冻结来源基线或已确认版本");
        String operator=access.actor().username();var overrides=new LinkedHashMap<String,Object>();overrides.put("bom_code","BOM-"+source.getProductId()+"-"+UUID.randomUUID().toString().substring(0,18));overrides.put("version_no",version);overrides.put("status","draft");overrides.put("bom_scope","candidate");overrides.put("source_type","mes_derivative");overrides.put("source_product_id",source.getProductId());overrides.put("source_product_bom_id",source.getProductBomId());overrides.put("frozen_flag",0);for(String f:List.of("frozen_at","frozen_by","confirmed_at","confirmed_by","released_at","released_by"))overrides.put(f,null);
        overrides.put("source_product_bom_id",source.getSourceProductBomId()==null?source.getProductBomId():source.getSourceProductBomId());
        if("engineering_graph".equals(source.getSourceType()))overrides.put("source_type","engineering_graph");
        long target=cloneRow("plm_product_bom","product_bom_id",source.getProductBomId(),overrides);
        var routeIds=new HashMap<Long,Long>();
        for(var row:jdbc.queryForList("select product_bom_route_id from plm_product_bom_route where product_bom_id=? and deleted_flag=0 and status='active'",source.getProductBomId())){long id=((Number)row.get("product_bom_route_id")).longValue();routeIds.put(id,cloneRow("plm_product_bom_route","product_bom_route_id",id,Map.of("product_bom_id",target,"source_product_bom_route_id",id,"variant_source_type","copied")));}
        for(var row:jdbc.queryForList("select product_bom_route_color_id,product_bom_route_id from plm_product_bom_route_color where product_bom_id=? and deleted_flag=0",source.getProductBomId())){long route=((Number)row.get("product_bom_route_id")).longValue();if(routeIds.containsKey(route))cloneRow("plm_product_bom_route_color","product_bom_route_color_id",((Number)row.get("product_bom_route_color_id")).longValue(),Map.of("product_bom_id",target,"product_bom_route_id",routeIds.get(route)));}
        for(var row:jdbc.queryForList("select product_bom_item_id,product_bom_route_id from plm_product_bom_item where product_bom_id=? and deleted_flag=0",source.getProductBomId())){
            long old=((Number)row.get("product_bom_item_id")).longValue();var changes=new LinkedHashMap<String,Object>();changes.put("product_bom_id",target);changes.put("version_no",version);changes.put("status","draft");changes.put("product_bom_route_id",row.get("product_bom_route_id")==null?null:routeIds.get(((Number)row.get("product_bom_route_id")).longValue()));long item=cloneRow("plm_product_bom_item","product_bom_item_id",old,changes);
            for(var t:jdbc.queryForList("select * from plm_bom_item_target where product_bom_item_id=?",old))jdbc.update("insert into plm_bom_item_target(product_bom_item_id,product_bom_id,product_bom_route_id,route_process_id,process_id,article_codes) values(?,?,?,?,?,?::text[])",item,target,routeIds.get(((Number)t.get("product_bom_route_id")).longValue()),t.get("route_process_id"),t.get("process_id"),array(stringsArray(t.get("article_codes"))));
        }
        audit("BOM_COPY_SCOPED_VERSION",target,Map.of("sourceBomId",source.getProductBomId(),"versionNo",version,"reason","保留基准产量、精度、投料节点、款号范围与指定工艺版本"));return boms.selectById(target);
    }
    public void validateSave(ProductBom bom,List<BomRouteSaveDTO> routes){
        if(!structured(bom)&&routes.stream().noneMatch(r->r.getItems().stream().anyMatch(i->i.getNodeId()!=null)))return;
        access.requireEngineeringMaintenance();jdbc.queryForList("select product_bom_id from plm_product_bom where product_bom_id=? for update",bom.getProductBomId());String expected=fingerprint(bom.getProductBomId());
        for(var route:routes){require(expected.equals(route.getOriginalHash()),"BOM／投料范围已变化或缺少原值校验，请重新载入，不能覆盖");
            if(sourceScoped(bom))require(route.getArticleCodes()!=null&&!route.getArticleCodes().isEmpty(),"来源款号范围不能静默清空");
            for(var item:route.getItems()){
                require(item.getNodeId()!=null,"请为每行物料明确选择投料节点");
                require(jdbc.queryForObject("select count(*) from plm_process where process_id=? and parent_process_id=? and deleted_flag=0",Long.class,item.getNodeId(),route.getProcessId())==1,"投料节点不属于所选路线版本");
                if(item.getArticleCodes()!=null&&route.getArticleCodes()!=null)require(route.getArticleCodes().containsAll(item.getArticleCodes()),"行级专用范围必须属于 BOM 路线款号范围");
            }
        }
    }
    public void saveRoute(ProductBomRoute route,BomRouteSaveDTO dto){
        if(dto.getOriginalHash()==null&&dto.getArticleCodes()==null)return;
        var source=jdbc.queryForList("select source_code from plm_master_data_source where source_kind='process_flows' and object_type='process' and object_id=?",String.class,route.getProcessId());
        if(source.isEmpty())source=jdbc.queryForList("select process_param_json->>'sourceRouteCode' from plm_process where process_id=? and process_param_json->>'sourceRouteCode' is not null",String.class,route.getProcessId());
        if(!source.isEmpty())route.setRouteCode(source.get(0));
        jdbc.update("update plm_product_bom_route set route_code=?,article_codes=?::text[],binding_priority=?,binding_condition=?::jsonb where product_bom_route_id=?",route.getRouteCode(),array(dto.getArticleCodes()),dto.getBindingPriority()==null?100:dto.getBindingPriority(),dto.getBindingCondition()==null?null:dto.getBindingCondition().toString(),route.getProductBomRouteId());
    }
    public void saveTarget(ProductBom bom,ProductBomRoute route,ProductBomItem item,ProductBomItemDTO dto){
        if(dto.getNodeId()==null)return;
        jdbc.update("insert into plm_bom_item_target(product_bom_item_id,product_bom_id,product_bom_route_id,route_process_id,process_id,article_codes) values(?,?,?,?,?,?::text[])",item.getProductBomItemId(),bom.getProductBomId(),route.getProductBomRouteId(),route.getProcessId(),dto.getNodeId(),array(dto.getArticleCodes()));
    }
    public void saved(ProductBom bom){audit("BOM_ROUTES_SAVE",bom.getProductBomId(),Map.of("fingerprint",fingerprint(bom.getProductBomId()),"reason","工程维护原 BOM 用料、路线及投料范围"));}
    public String fingerprint(long bom){
        var parts=new ArrayList<String>();parts.add(Objects.toString(jdbc.queryForObject("select row_to_json(b)::text from plm_product_bom b where product_bom_id=?",String.class,bom),""));
        for(String table:List.of("plm_product_bom_route","plm_product_bom_item","plm_bom_item_target"))parts.addAll(jdbc.queryForList("select row_to_json(t)::text from "+table+" t where product_bom_id=? order by row_to_json(t)::text",String.class,bom));
        return MasterDataSnapshotStore.sha256(String.join("\n",parts).getBytes(StandardCharsets.UTF_8));
    }
    private long cloneRow(String table,String pk,long source,Map<String,Object> overrides){
        // Only code-owned table/column identifiers; original business values are copied by the database.
        require(Set.of("plm_product_bom","plm_product_bom_route","plm_product_bom_route_color","plm_product_bom_item").contains(table),"复制对象类型无效");var changes=new LinkedHashMap<String,Object>(overrides);changes.put("created_at",LocalDateTime.now());changes.put("updated_at",LocalDateTime.now());changes.put("created_by",access.actor().username());changes.put("updated_by",access.actor().username());
        var columns=jdbc.queryForList("select column_name from information_schema.columns where table_schema=current_schema() and table_name=? and column_name<>? order by ordinal_position",String.class,table,pk);var selects=new ArrayList<String>();var args=new ArrayList<Object>();for(String column:columns){require(column.matches("[a-z_]+"),"列名无效");if(changes.containsKey(column)){selects.add("?");args.add(changes.get(column));}else selects.add(column);}args.add(source);
        return Objects.requireNonNull(jdbc.queryForObject("insert into "+table+" ("+String.join(",",columns)+") select "+String.join(",",selects)+" from "+table+" where "+pk+"=? returning "+pk,Long.class,args.toArray()));
    }
    private void audit(String action,long id,Object detail){try{audit.logRequired(OperationLogCreateCommand.builder().action(action).businessType("PRODUCT_BOM").businessId(Long.toString(id)).detailJson(json.writeValueAsString(detail)).build(),access.actor());}catch(com.fasterxml.jackson.core.JsonProcessingException failure){throw new IllegalStateException("BOM 结构审计编码失败",failure);}}
    public static List<String> stringsArray(Object value){if(value==null)return null;try{if(value instanceof java.sql.Array a)return List.of((String[])a.getArray());if(value instanceof String[] a)return List.of(a);throw new IllegalArgumentException("array");}catch(java.sql.SQLException failure){throw new IllegalStateException("款号范围读取失败",failure);}}
    private static String array(List<String> values){return values==null?null:"{"+String.join(",",values.stream().map(v->"\""+v.replace("\\","\\\\").replace("\"","\\\"")+"\"").toList())+"}";}
}
