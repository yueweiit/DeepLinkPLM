package com.yuewei.plm.module.manufacturing;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuewei.plm.common.exception.BusinessException;
import com.yuewei.plm.common.security.CurrentUser;
import com.yuewei.plm.common.util.ProductBusinessCodeGenerator;
import com.yuewei.plm.module.masterdata.ProductMoldLifecycleService;
import com.yuewei.plm.module.moldreference.PhoneModelReferenceService;
import com.yuewei.plm.module.operationlog.service.OperationLogCreateCommand;
import com.yuewei.plm.module.operationlog.service.OperationLogService;
import com.yuewei.plm.module.pilot.PilotAccessService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Customer phone identity never substitutes the original manufacturing identity. */
@Service @RequiredArgsConstructor
public class ManufacturingIdentityService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final PilotAccessService access;
    private final PhoneModelReferenceService phones;
    private final OperationLogService audit;
    private final ProductBusinessCodeGenerator codeGenerator;
    public record ResolveInput(long productLineId,long phoneModelId,String holeType,String structureDescription,Long relationId,Long expectedVersion) {}
    public record WriteInput(Long version,long productLineId,long itemId,long moldSetId,long phoneModelId,String holeType,
            String structureDescription,boolean sharedMaterial,Long manufacturingProductId,List<Long> skuIds,
            String moldEvidence,String materialEvidence,String compatibilityDigest,String reason,String status) {}
    private static final String SELECT="""
        select r.relation_id as id,r.relation_id as "relationId",r.version,r.version as "relationVersion",
          r.product_line_id as "productLineId",r.item_id as "itemId",r.mold_set_id as "moldSetId",r.phone_model_id as "phoneModelId",
          s.set_no as "setNo",('MS-'||lpad(s.mold_set_id::text,greatest(6,length(s.mold_set_id::text)),'0')) as "physicalCode",
          r.hole_type as "holeType",r.structure_description as "structureDescription",r.shared_material as "sharedMaterial",
          r.manufacturing_product_id as "manufacturingProductId",r.sku_ids::text as "skuJson",r.mold_evidence as "moldEvidence",
          r.material_evidence as "materialEvidence",r.compatibility_digest as "compatibilityDigest",r.status,
          r.updated_by as "updatedBy",r.updated_at as "updatedAt",i.style_name as "styleName",i.mold_name as "moldName",
          p.product_name as "productLineName",m.model_name as "modelName",c.code_value as "phoneModelCode",b.name as "brandName",
          mp.phone_model_code as "manufacturingPhoneCode",mp.product_name as "manufacturingProductName",mp.product_code as "manufacturingProductCode",
          (select coalesce(jsonb_agg(jsonb_build_object('id',sku.product_id,'code',sku.finished_product_code,'name',sku.product_name,'colorCode',sku.color_code) order by sku.product_id),'[]'::jsonb)::text
            from plm_product sku where sku.product_id in (select value::bigint from jsonb_array_elements_text(r.sku_ids))) as "skusJson",
          (select coalesce(jsonb_agg(jsonb_build_object('partType',part.part_type,'code',coalesce(part.original_mold_code,mc.mold_code)) order by part.part_type),'[]'::jsonb)::text
            from plm_collaboration_mold_part part left join plm_product_mold_code mc on mc.product_mold_code_id=part.product_mold_code_id and mc.deleted_flag=0
            where part.mold_set_id=r.mold_set_id) as "partsJson"
        from plm_manufacturing_relation r join plm_collaboration_item i on i.item_id=r.item_id and i.deleted_flag=0
          join plm_collaboration_mold_set s on s.mold_set_id=r.mold_set_id and s.item_id=r.item_id and s.deleted_flag=0
          join plm_product p on p.product_id=r.product_line_id and p.deleted_flag=0
          join plm_phone_model_member m on m.id=r.phone_model_id
          join plm_phone_brand b on b.id=m.brand_id left join plm_code_item c on c.code_item_id=m.code_item_id
          left join plm_product mp on mp.product_id=r.manufacturing_product_id
        """;
    private String visible(){
        return access.visibleSqlClause("product","r.product_line_id")+" and "+access.visibleSqlClause("item","r.item_id")
            +" and "+access.visibleSqlClause("mold","r.mold_set_id")+" and (r.manufacturing_product_id is null or ("+access.visibleSqlClause("product","r.manufacturing_product_id")+"))"
            +" and not exists(select 1 from jsonb_array_elements_text(r.sku_ids) sid where not exists(select 1 from plm_product sku_scope where sku_scope.product_id=sid.value::bigint and sku_scope.deleted_flag=0 and ("+access.visibleSqlClause("product","sku_scope.product_id")+")))";
    }
    private void read(){access.requireDomesticView();access.requirePermission("collaboration:view");}
    public Map<String,Object> page(Map<String,String> query){
        read();var args=new ArrayList<Object>();String where=" where "+visible();
        for(var field:Map.of("productLineId","r.product_line_id","phoneModelId","r.phone_model_id","itemId","r.item_id","moldSetId","r.mold_set_id").entrySet()){
            if(!text(query.get(field.getKey())).isEmpty()){where+=" and "+field.getValue()+"=?";args.add(positive(query.get(field.getKey()),field.getKey()));}
        }
        String status=text(query.getOrDefault("status","active"));if(!status.equals("all")){require(Set.of("active","inactive").contains(status),"关系状态无效");where+=" and r.status=?";args.add(status);}
        String keyword=text(query.get("keyword"));if(!keyword.isEmpty()){where+=" and concat_ws(' ',p.product_name,i.style_name,i.mold_name,m.model_name,c.code_value,mp.phone_model_code,r.structure_description) ilike ?";args.add("%"+keyword+"%");}
        long page=positive(query.getOrDefault("page","1"),"页码"),size=positive(query.getOrDefault("size","50"),"每页数量");require(size<=100,"每页最多100条");
        long total=jdbc.queryForObject("select count(*) from ("+SELECT+where+") counted",Long.class,args.toArray());args.add(size);args.add(Math.multiplyExact(page-1,size));
        boolean canWrite=access.canMaintainEngineeringData();return pageView(jdbc.queryForList(SELECT+where+" order by r.relation_id desc limit ? offset ?",args.toArray()).stream().map(row->view(row,canWrite)).toList(),total,page,size);
    }
    public Map<String,Object> detail(long id){read();return row(id,false);}
    @Transactional public Map<String,Object> save(Long id,WriteInput input,HttpServletRequest request){
        CurrentUser actor=access.requireEngineeringMaintenance();validate(input);var before=id==null?null:row(id,true);
        if(before!=null){if(input.version()==null||number(before.get("version"))!=input.version())throw new BusinessException(40901,"制造关联已变化，请刷新后核对");require(number(before.get("productLineId"))==input.productLineId()&&number(before.get("phoneModelId"))==input.phoneModelId(),"不能把原关联改成另一款式或手机，请分别维护");}
        access.requireProduct(input.productLineId());access.requireItem(input.itemId());access.requireMold(input.moldSetId());
        var item=jdbc.queryForList("select product_line_id from plm_collaboration_item where item_id=? and deleted_flag=0",Long.class,input.itemId());require(item.size()==1&&item.get(0)==input.productLineId(),"所选模具档案不属于本款式");
        String status=text(input.status()).isEmpty()?"active":text(input.status());
        if(status.equals("active"))validateParts(phones.requireCompatibility(input.productLineId(),input.phoneModelId(),input.itemId(),input.moldSetId(),input.compatibilityDigest()),input.productLineId());
        if(input.sharedMaterial())validateManufacturing(input.productLineId(),input.manufacturingProductId(),input.skuIds(),input.holeType(),input.structureDescription());
        var skuIds=input.skuIds()==null?List.<Long>of():input.skuIds().stream().distinct().sorted().toList();
        try {
            if(id==null)id=jdbc.queryForObject("""
                insert into plm_manufacturing_relation(product_line_id,item_id,mold_set_id,phone_model_id,hole_type,structure_description,shared_material,
                  manufacturing_product_id,sku_ids,mold_evidence,material_evidence,compatibility_digest,status,created_by,updated_by)
                values(?,?,?,?,?,?,?,?,?::jsonb,?,?,?,?,?,?) returning relation_id
                """,Long.class,input.productLineId(),input.itemId(),input.moldSetId(),input.phoneModelId(),input.holeType(),text(input.structureDescription()),input.sharedMaterial(),input.manufacturingProductId(),encode(skuIds),input.moldEvidence().strip(),text(input.materialEvidence()),input.compatibilityDigest(),status,actor.username(),actor.username());
            else jdbc.update("""
                update plm_manufacturing_relation set item_id=?,mold_set_id=?,hole_type=?,structure_description=?,shared_material=?,manufacturing_product_id=?,
                  sku_ids=?::jsonb,mold_evidence=?,material_evidence=?,compatibility_digest=?,status=?,version=version+1,updated_by=?,updated_at=now() where relation_id=?
                """,input.itemId(),input.moldSetId(),input.holeType(),text(input.structureDescription()),input.sharedMaterial(),input.manufacturingProductId(),encode(skuIds),input.moldEvidence().strip(),text(input.materialEvidence()),input.compatibilityDigest(),status,actor.username(),id);
        }catch(DuplicateKeyException conflict){throw new BusinessException(40901,"相同手机、孔型和实物套已有有效制造关联，请打开原关联维护");}
        var after=row(id,false);var changes=new LinkedHashMap<String,Object>();changes.put("before",before);changes.put("after",after);changes.put("reason",input.reason().strip());
        jdbc.update("insert into plm_collaboration_history(item_id,entity_type,entity_id,field_name,before_value,after_value,reason,actor_id,actor_name) values(?,'manufacturing',?,'manufacturingRelation',?::jsonb,?::jsonb,?,?,?)",input.itemId(),id,encode(before),encode(after),input.reason().strip(),actor.userId(),actor.displayName());
        audit.logRequired(OperationLogCreateCommand.builder().action(before==null?"MANUFACTURING_RELATION_CREATE":"MANUFACTURING_RELATION_UPDATE").businessType("MANUFACTURING_RELATION").businessId(id.toString()).businessName("共模与制造物料关联").detailJson(encode(changes)).request(request).build(),actor);
        return after;
    }
    @Transactional public Map<String,Object> resolve(ResolveInput input){
        read();require(input!=null&&input.productLineId()>0&&input.phoneModelId()>0,"请选择款式和实际适用手机");hole(input.holeType(),input.structureDescription());access.requireProduct(input.productLineId());
        var phone=phones.options(List.of(input.phoneModelId())).get(0);var args=new ArrayList<Object>(List.of(input.productLineId(),input.phoneModelId(),input.holeType(),text(input.structureDescription())));
        String where=" where r.product_line_id=? and r.phone_model_id=? and r.hole_type=? and r.structure_description=? and r.status='active' and "+visible();
        if(input.relationId()!=null){require(input.relationId()>0,"制造关联无效");where+=" and r.relation_id=?";args.add(input.relationId());}
        var found=jdbc.queryForList(SELECT+where+" order by r.relation_id",args.toArray());
        if(found.size()!=1)return pending(phone,found.isEmpty()?"尚未确认本款式、孔型和手机的制造关联，请由工程核对":"存在多个制造对象，请明确选择原关联，不能自动猜配");
        var result=view(found.get(0),access.canMaintainEngineeringData());if(input.expectedVersion()!=null&&number(result.get("version"))!=input.expectedVersion())return pending(phone,"制造关联版本已变化，请重新核对原选择");
        try {
            validateParts(phones.requireCompatibility(input.productLineId(),input.phoneModelId(),number(result.get("itemId")),number(result.get("moldSetId")),text(result.get("compatibilityDigest"))),input.productLineId());
            if(Boolean.TRUE.equals(result.get("sharedMaterial")))validateManufacturing(input.productLineId(),number(result.get("manufacturingProductId")),longs(result.get("skuIds")),input.holeType(),input.structureDescription());
        }catch(BusinessException stale){if(stale.getCode()==40103)throw stale;return pending(phone,stale.getMessage());}
        result.put("status","reuse");result.put("relationStatus","active");result.put("reason",Boolean.TRUE.equals(result.get("sharedMaterial"))?"已确认共模及共用生产物料，沿用原制造编码":"仅确认共用模具，尚未确认共用生产物料");return result;
    }
    public Map<String,Object> requireResolved(ResolveInput input){var result=resolve(input);if(!"reuse".equals(result.get("status")))throw new BusinessException(40901,text(result.get("reason")));return result;}
    public Map<String,Object> resolveForConnector(ResolveInput input,long configuredProductLineId){
        require(input!=null&&configuredProductLineId>0&&input.productLineId()==configuredProductLineId,"连接器款式与审批类型映射不一致");
        hole(input.holeType(),input.structureDescription());
        return pending(Map.of("phoneModelId",input.phoneModelId()),"连接器尚未配置受限制造身份读取账号，请完成隔离联调；不会按手机码另行生成生产编码");
    }
    public List<Map<String,Object>> resolvedSkus(Map<String,Object> resolution){
        require(Boolean.TRUE.equals(resolution.get("sharedMaterial")),"共用模具不等于共用成品，请先确认制造物料");
        return validateManufacturing(number(resolution.get("productLineId")),number(resolution.get("manufacturingProductId")),longs(resolution.get("skuIds")),text(resolution.get("holeType")),text(resolution.get("structureDescription")));
    }
    public Map<String,Object> options(Map<String,String> query){
        read();long line=positive(query.get("productLineId"),"款式");access.requireProduct(line);String kind=text(query.get("kind"));require(Set.of("products","skus").contains(kind),"制造对象类型无效");
        var args=new ArrayList<Object>();String where=" where p.deleted_flag=0 and coalesce(to_jsonb(p)->>'master_data_status','active')='active' and p.status not in ('archived','abandoned') and coalesce(p.lock_status,'') not in ('frozen','abandoned') and "+access.visibleSqlClause("product","p.product_id");
        if(kind.equals("products")){where+=" and p.parent_product_id=? and p.product_type='model_variant'";args.add(line);}
        else {long parent=positive(query.get("manufacturingProductId"),"原制造型号产品");requireManufacturingProduct(line,parent);where+=" and p.parent_product_id=? and p.product_type='sku'";args.add(parent);}
        String keyword=text(query.get("keyword"));if(!keyword.isEmpty()){where+=" and concat_ws(' ',p.product_name,p.product_code,p.finished_product_code,p.phone_model_code,p.color_code) ilike ?";args.add("%"+keyword+"%");}
        long page=positive(query.getOrDefault("page","1"),"页码"),size=positive(query.getOrDefault("size","50"),"每页数量");require(size<=100,"每页最多100条");long total=jdbc.queryForObject("select count(*) from plm_product p"+where,Long.class,args.toArray());args.add(size);args.add((page-1)*size);
        var rows=jdbc.queryForList("select p.product_id as id,coalesce(p.finished_product_code,p.product_code) as code,p.product_name as name,p.phone_model_code as \"phoneModelCode\",p.color_code as \"colorCode\" from plm_product p"+where+" order by p.product_id desc limit ? offset ?",args.toArray());return pageView(rows,total,page,size);
    }
    public Map<Long,List<Map<String,Object>>> batchCoverage(List<Long> phoneIds){
        read();if(phoneIds.isEmpty())return Map.of();String placeholders=String.join(",",Collections.nCopies(phoneIds.size(),"?"));var result=new LinkedHashMap<Long,List<Map<String,Object>>>();
        boolean canWrite=access.canMaintainEngineeringData();for(var value:jdbc.queryForList(SELECT+" where r.phone_model_id in ("+placeholders+") and r.status='active' and "+visible()+" order by r.relation_id",phoneIds.toArray())){var row=view(value,canWrite);result.computeIfAbsent(number(row.get("phoneModelId")),id->new ArrayList<>()).add(row);}return result;
    }
    private Map<String,Object> row(long id,boolean lock){
        if(lock){var ids=jdbc.queryForList("select r.relation_id from plm_manufacturing_relation r where r.relation_id=? and "+visible()+" for update",Long.class,id);if(ids.size()!=1)throw new BusinessException(40103,"制造关联不存在或未获授权");}
        var rows=jdbc.queryForList(SELECT+" where r.relation_id=? and "+visible(),id);if(rows.size()!=1)throw new BusinessException(40103,"制造关联不存在或未获授权");return view(rows.get(0),access.canMaintainEngineeringData());
    }
    private Map<String,Object> view(Map<String,Object> row,boolean canWrite){
        row.put("skuIds",decode(row.remove("skuJson")));row.put("skus",decode(row.remove("skusJson")));row.put("parts",decode(row.remove("partsJson")));var business=new LinkedHashMap<String,Object>();business.put("phoneModelId",row.get("phoneModelId"));for(String field:List.of("modelName","phoneModelCode","brandName"))business.put(field,row.get(field));business.put("displayName",row.get("modelName"));row.put("businessPhone",business);row.put("canWrite",canWrite);return row;
    }
    private Map<String,Object> pending(Map<String,Object> phone,String reason){var result=new LinkedHashMap<String,Object>();result.put("status","pending");result.put("businessPhone",phone);result.put("sharedMaterial",false);result.put("reason",reason);result.put("reasons",List.of(reason));return result;}
    private void validateParts(Map<String,Object> fit,long line){
        String prefix=jdbc.queryForObject("select product_specific_code from plm_product where product_id=? and deleted_flag=0",String.class,line);
        require(prefix!=null&&!prefix.isBlank(),"本款式产品特定编码尚待核对，不能确认制造关联");
        for(String part:List.of("PC","TPU")){String material=part.equals("PC")?ProductBusinessCodeGenerator.PC_MATERIAL_CODE:ProductBusinessCodeGenerator.TPU_MATERIAL_CODE;codeGenerator.parseMoldCode(text(fit.get(part.toLowerCase(Locale.ROOT)+"Code")),prefix,List.of(material),null);}
    }
    private List<Map<String,Object>> validateManufacturing(long line,Long productId,List<Long> ids,String holeType,String structure){
        var product=requireManufacturingProduct(line,productId);String known=com.yuewei.plm.common.util.ManufacturingHole.canonicalHole(text(product.get("holeType")));if(!known.isEmpty())require(known.equals(holeType),"原制造物料的已知孔型与本规格不一致，不能因共模合并成品");String description=text(product.get("structureDescription"));if(!description.isEmpty())require(description.equals(text(structure)),"原制造物料的结构说明与本规格不一致，请工程核对");
        require(ids!=null&&!ids.isEmpty(),"请明确本次共用的原成品SKU范围");var unique=ids.stream().distinct().sorted().toList();for(long id:unique){require(id>0,"成品SKU无效");access.requireProduct(id);ProductMoldLifecycleService.requireActive(jdbc,"product",id);}
        String placeholders=String.join(",",Collections.nCopies(unique.size(),"?"));var rows=jdbc.queryForList("select product_id as id,parent_product_id as \"parentProductId\",product_type as \"productType\",product_name as name,finished_product_code as code,phone_model_code as \"phoneModelCode\",color_code as \"colorCode\",product_specific_code as \"productSpecificCode\",status,lock_status as \"lockStatus\" from plm_product where deleted_flag=0 and product_id in ("+placeholders+") order by product_id",unique.toArray());require(rows.size()==unique.size(),"原成品SKU不存在");
        for(var sku:rows){require(number(sku.get("parentProductId"))==productId&&"sku".equals(sku.get("productType")),"所选SKU不属于原制造型号产品");require(!Set.of("archived","abandoned").contains(text(sku.get("status")))&&!Set.of("frozen","abandoned").contains(text(sku.get("lockStatus"))),"原制造SKU已归档或冻结");var code=codeGenerator.parseProductStateCode(text(sku.get("code")));require(code.productSpecificCode().equals(text(product.get("productSpecificCode")))&&code.phoneModelCode().equals(text(product.get("phoneModelCode")))&&text(sku.get("phoneModelCode")).equals(text(product.get("phoneModelCode")))&&code.colorCode().equals(text(sku.get("colorCode"))),"原SKU完整编码与制造身份不一致，请核对原值");sku.put("operationCode",code.operationCode());}
        return rows;
    }
    private Map<String,Object> requireManufacturingProduct(long line,Long id){
        require(id!=null&&id>0,"请选择原制造型号产品");access.requireProduct(id);ProductMoldLifecycleService.requireActive(jdbc,"product",id);
        var rows=jdbc.queryForList("select p.parent_product_id as \"parentProductId\",p.product_type as \"productType\",p.phone_model_code as \"phoneModelCode\",p.product_specific_code as \"productSpecificCode\",p.status,p.lock_status as \"lockStatus\",line.product_specific_code as \"lineCode\",spec.hole_type as \"holeType\",spec.manufacturing_structure_description as \"structureDescription\" from plm_product p join plm_product line on line.product_id=? and line.deleted_flag=0 left join plm_project_requirement_form spec on spec.project_id=p.product_id where p.product_id=? and p.deleted_flag=0",line,id);
        require(rows.size()==1,"原制造型号产品不存在");var row=rows.get(0);require(number(row.get("parentProductId"))==line&&"model_variant".equals(row.get("productType")),"原制造型号产品不属于本款式");require(text(row.get("phoneModelCode")).matches("[0-9]{4}")&&text(row.get("productSpecificCode")).equals(text(row.get("lineCode"))),"原制造编码待核对，不能用当前手机码替换");require(!Set.of("archived","abandoned").contains(text(row.get("status")))&&!Set.of("frozen","abandoned").contains(text(row.get("lockStatus"))),"原制造型号产品已归档或冻结");return row;
    }
    private void validate(WriteInput input){
        require(input!=null&&input.productLineId()>0&&input.itemId()>0&&input.moldSetId()>0&&input.phoneModelId()>0,"请选择款式、具体模具套和实际手机");hole(input.holeType(),input.structureDescription());required(input.moldEvidence(),2000,"本规格共模依据");required(input.reason(),2000,"修改原因");required(input.compatibilityDigest(),64,"适配核对版本");require(Set.of("active","inactive").contains(text(input.status()).isEmpty()?"active":input.status()),"关联状态无效");
        if(input.sharedMaterial()){require(input.manufacturingProductId()!=null&&input.skuIds()!=null&&!input.skuIds().isEmpty(),"请明确原制造型号产品和SKU范围");required(input.materialEvidence(),2000,"共用生产物料依据");}
        else require(input.manufacturingProductId()==null&&(input.skuIds()==null||input.skuIds().isEmpty()),"仅共模时不能同时指定共用成品，请分别确认");
    }
    private static void hole(String value,String structure){require(Set.of("large","precision","other").contains(text(value)),"请明确孔型：大孔、精孔或其他");require(text(structure).length()<=1000,"结构说明过长");if(value.equals("other"))required(structure,1000,"其他孔型说明");}
    private static void required(String value,int max,String label){require(!text(value).isEmpty()&&text(value).length()<=max,"请填写有效的"+label);}
    private static Map<String,Object> pageView(List<Map<String,Object>> content,long total,long page,long size){return Map.of("content",content,"totalElements",total,"page",page,"size",size,"totalPages",(total+size-1)/size);}
    private static long positive(String value,String label){try{long n=Long.parseLong(text(value));require(n>0,label+"无效");return n;}catch(NumberFormatException invalid){throw new BusinessException(40001,label+"无效");}}
    private static List<Long> longs(Object value){if(!(value instanceof List<?> list))return List.of();return list.stream().map(ManufacturingIdentityService::number).toList();}
    private static long number(Object value){return value instanceof Number n?n.longValue():value==null?0:Long.parseLong(value.toString());}
    private static String text(Object value){return Objects.toString(value,"").strip();}
    private static void require(boolean value,String message){if(!value)throw new BusinessException(40001,message);}
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception invalid){throw new IllegalStateException("制造关联序列化失败",invalid);}}
    private List<Object> decode(Object value){try{return json.readValue(value.toString(),new TypeReference<List<Object>>(){});}catch(Exception invalid){throw new IllegalStateException("制造关联读取失败",invalid);}}
}
