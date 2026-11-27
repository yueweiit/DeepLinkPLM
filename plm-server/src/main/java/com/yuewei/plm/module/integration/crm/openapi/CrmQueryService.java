package com.yuewei.plm.module.integration.crm.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.yuewei.plm.common.util.ProductBusinessCodeGenerator;
import com.yuewei.plm.module.moldreference.PhoneProductEvidence;
import com.yuewei.plm.module.collaboration.MoldSchedulePolicy;
import com.yuewei.plm.module.collaboration.MoldScheduleService;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class CrmQueryService {
    public static final List<String> WRITE_FIELDS=List.of("actualMexicoArrivalDate","plannedAcceptanceDate","plannedProductionDate");
    private static final List<String> DATES=List.of("openedDate","plannedMoldDeliveryDate","actualShipDate","plannedFactoryArrivalDate","actualMexicoArrivalDate","plannedAcceptanceDate","plannedProductionDate");
    private static final Map<String,String> STATUS=Map.ofEntries(Map.entry("pending","待开模"),Map.entry("making","制作中"),Map.entry("ready","待发模"),Map.entry("shipped","已发出"),Map.entry("mexico_arrived","抵达墨西哥"),Map.entry("partially_arrived","部分到厂"),Map.entry("awaiting_acceptance","已到厂，待验收"),Map.entry("partially_accepted","部分验收"),Map.entry("acceptance_failed","验收不通过"),Map.entry("delivered","交付完成"),Map.entry("market_ready","已确认上市"),Map.entry("paused","已暂停"),Map.entry("planned","待开工"));
    private final JdbcTemplate jdbc;private final MoldScheduleService schedules;private final CrmSyncStore store;private final CrmTokenCodec codec;private final CrmOpenApiProperties properties;private final TransactionTemplate read;
    public CrmQueryService(JdbcTemplate jdbc,MoldScheduleService schedules,CrmSyncStore store,CrmTokenCodec codec,CrmOpenApiProperties p,PlatformTransactionManager manager){
        this.jdbc=jdbc;this.schedules=schedules;this.store=store;this.codec=codec;properties=p;read=new TransactionTemplate(manager);read.setReadOnly(true);read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    public Map<String,Object> detail(String kind,long id){return read.execute(s->detailInTransaction(kind,id,store.control()));}
    public Map<String,Object> detailInTransaction(String kind,long id,Map<String,Object> control){
        return detailInTransaction(kind,id,control,null);
    }
    private Map<String,Object> detailInTransaction(String kind,long id,Map<String,Object> control,Map<String,Object> source){
        if(id<1)throw CrmApiException.invalid("ID 必须为正整数");
        String life=jdbc.queryForObject("select plm_crm_openapi_lifecycle(?,?)",String.class,kind,id);
        if("DELETED".equals(life))throw new CrmApiException(404,"NOT_FOUND","记录不存在或已删除");
        Map<String,Object> state=store.resource(kind,id,false);
        Map<String,Object> result=kind.equals("MOLD_SET")?mold(id,source==null?schedules.detail(id):source):phone(id);
        result.put("sourceSystem","PLM");result.put("entityType",kind);result.put("recordId",Long.toString(id));result.put("lifecycleStatus",life);
        result.put("recordVersion",store.version(kind,id,state,control));result.put("updatedAt",timestamp(state.get("projection_updated_at")));
        if(kind.equals("MOLD_SET")&&!"ACTIVE".equals(life))result.put("writableFields",List.of());
        return result;
    }
    public Map<String,Object> page(String kind,Map<String,String> input){return read.execute(s->pageInTransaction(kind,input));}
    private Map<String,Object> pageInTransaction(String kind,Map<String,String> input){
        Set<String> allowed=new HashSet<>(List.of("pageSize","pageToken","keyword","includeInactive"));
        allowed.addAll(kind.equals("MOLD_SET")?List.of("moldSetId","assetNumber","productCode","phoneModelId","phoneModelCode","moldCode","deliveryStatus","overdueOnly","sortOrder"):List.of("phoneModelId","phoneModelCode","brandId"));
        if(!allowed.containsAll(input.keySet()))throw CrmApiException.invalid("存在不支持的查询参数");
        String sortOrder=input.getOrDefault("sortOrder","asc").toLowerCase(Locale.ROOT);
        if(kind.equals("MOLD_SET")&&!Set.of("asc","desc").contains(sortOrder))throw CrmApiException.invalid("sortOrder 仅支持 asc 或 desc");
        int size=integer(input.getOrDefault("pageSize","50"),1,100);boolean inactive=bool(input.getOrDefault("includeInactive","false"));
        var filters=new TreeMap<>(input);filters.remove("pageToken");filters.put("pageSize",Integer.toString(size));filters.put("includeInactive",Boolean.toString(inactive));
        String filterHash=CrmTokenCodec.hash(filters.toString());var control=store.control();String epoch=control.get("stream_epoch").toString();
        long last=0,upper;String start=store.cursor(((Number)control.get("published_head")).longValue(),null,control);
        String table=kind.equals("MOLD_SET")?"plm_collaboration_mold_set":"plm_phone_model_member",pk=kind.equals("MOLD_SET")?"mold_set_id":"id";
        if(input.containsKey("pageToken")){
            JsonNode token=codec.decode(input.get("pageToken"),"page",properties.getSubjectId(),epoch);
            if(!kind.equals(token.path("entity").asText())||!filterHash.equals(token.path("filter").asText()))throw CrmApiException.invalid("翻页时不得改变筛选或页大小");
            last=token.path("last").asLong(-1);upper=token.path("upper").asLong(-1);start=token.path("start").asText();
        }else upper=jdbc.queryForObject("select coalesce(max("+pk+"),0) from "+table,Long.class);
        boolean descending=kind.equals("MOLD_SET")&&sortOrder.equals("desc");
        if(last<0||upper<0||(!descending&&upper<last)||(descending&&last>upper+1))throw CrmApiException.invalid("分页边界无效");
        long scan=descending&& !input.containsKey("pageToken")?upper+1:last;
        StringBuilder where=new StringBuilder(" where x."+pk+(descending?"<? and x.":">? and x.")+pk+"<=? and plm_crm_openapi_lifecycle(?,x."+pk+") in ("+(inactive?"'ACTIVE','INACTIVE'":"'ACTIVE'")+")");
        List<Object> args=new ArrayList<>(List.of(scan,upper,kind));
        if(kind.equals("PHONE_MODEL")){
            exact(input,"phoneModelId","x.id",where,args,true);exact(input,"brandId","x.brand_id",where,args,true);
            if(input.containsKey("phoneModelCode")){where.append(" and exists(select 1 from plm_code_item c where c.code_item_id=x.code_item_id and c.deleted_flag=0 and c.status='enabled' and c.code_value=?)");args.add(input.get("phoneModelCode"));}
            if(input.containsKey("keyword")){where.append(" and (x.model_name ilike ? or exists(select 1 from plm_phone_brand b where b.id=x.brand_id and b.name ilike ?))");args.add(pattern(input.get("keyword")));args.add(pattern(input.get("keyword")));}
        }else{
            exact(input,"moldSetId","x.mold_set_id",where,args,true);
            if(input.containsKey("assetNumber")){where.append(" and ('MS-'||lpad(x.mold_set_id::text,greatest(6,length(x.mold_set_id::text)),'0'))=?");args.add(input.get("assetNumber"));}
            if(input.containsKey("productCode")){where.append(" and exists(select 1 from plm_product p join plm_collaboration_item i on i.item_id=x.item_id where p.product_code=? and (p.product_id=i.product_line_id or exists(select 1 from plm_collaboration_item_project ip where ip.item_id=i.item_id and ip.project_id=p.product_id)))");args.add(input.get("productCode"));}
            if(input.containsKey("phoneModelId")||input.containsKey("phoneModelCode")){
                where.append(" and exists(select 1 from plm_phone_model_member m left join plm_code_item c on c.code_item_id=m.code_item_id and c.deleted_flag=0 and c.status='enabled' where "+phoneScope("m","x"));
                exact(input,"phoneModelId","m.id",where,args,true);exact(input,"phoneModelCode","c.code_value",where,args,false);where.append(")");
            }
            if(input.containsKey("moldCode")){where.append(" and exists(select 1 from plm_collaboration_mold_part mp left join plm_product_mold_code mc on mc.product_mold_code_id=mp.product_mold_code_id and mc.deleted_flag=0 where mp.mold_set_id=x.mold_set_id and coalesce(mc.mold_code,mp.original_mold_code)=?)");args.add(input.get("moldCode"));}
            if(input.containsKey("keyword")){
                where.append("""
                    and (exists(select 1 from plm_collaboration_item i left join plm_product p on p.product_id=i.product_line_id
                      where i.item_id=x.item_id and concat_ws(' ',i.style_name,i.compatible_models,p.product_name,p.product_code,
                        'MS-'||lpad(x.mold_set_id::text,greatest(6,length(x.mold_set_id::text)),'0')) ilike ?)
                    or exists(select 1 from plm_phone_model_member m left join plm_phone_brand b on b.id=m.brand_id
                      left join plm_code_item c on c.code_item_id=m.code_item_id where
                    """+phoneScope("m","x")+" and concat_ws(' ',b.name,m.model_name,c.code_value) ilike ?)"
                    +" or exists(select 1 from plm_collaboration_mold_part mp left join plm_product_mold_code mc on mc.product_mold_code_id=mp.product_mold_code_id and mc.deleted_flag=0 where mp.mold_set_id=x.mold_set_id and coalesce(mc.mold_code,mp.original_mold_code) ilike ?))");
                for(int i=0;i<3;i++)args.add(pattern(input.get("keyword")));
            }
            if(input.containsKey("deliveryStatus")&&!STATUS.containsKey(input.get("deliveryStatus")))throw CrmApiException.invalid("交付状态无效");
            if(input.containsKey("overdueOnly"))bool(input.get("overdueOnly"));
        }
        // Some status predicates require canonical Java policy. Scan bounded ID batches without offset;
        // stop after size+1 matches, retaining the last returned ID for correct continuation.
        List<Map<String,Object>> items=new ArrayList<>();
        while(items.size()<=size){
            var batchArgs=new ArrayList<>(args);batchArgs.set(0,scan);batchArgs.add(100);
            var ids=jdbc.queryForList("select x."+pk+" from "+table+" x"+where+" order by x."+pk+(descending?" desc":" asc")+" limit ?",Long.class,batchArgs.toArray());
            if(ids.isEmpty())break;
            Map<Long,Map<String,Object>> sources=new HashMap<>();
            if(kind.equals("MOLD_SET"))for(var source:schedules.projectRows(ids))sources.put(((Number)source.get("moldSetId")).longValue(),source);
            for(long id:ids){scan=id;var row=detailInTransaction(kind,id,control,sources.get(id));
                if(kind.equals("MOLD_SET")&&input.containsKey("deliveryStatus")&&!input.get("deliveryStatus").equals(row.get("deliveryStatus")))continue;
                if(kind.equals("MOLD_SET")&&bool(input.getOrDefault("overdueOnly","false"))&&!Boolean.TRUE.equals(row.get("overdue")))continue;
                items.add(row);if(items.size()>size)break;
            }
            if(ids.size()<100||items.size()>size)break;
        }
        boolean more=items.size()>size;if(more)items=new ArrayList<>(items.subList(0,size));
        String next=null;
        if(more){long after=Long.parseLong(items.get(items.size()-1).get("recordId").toString());var token=new LinkedHashMap<String,Object>();token.put("kind","page");token.put("subject",properties.getSubjectId());token.put("epoch",epoch);token.put("entity",kind);token.put("filter",filterHash);token.put("last",after);token.put("upper",upper);token.put("start",start);token.put("expires",Instant.now().plusSeconds(86400).getEpochSecond());next=codec.encode(token);}
        var result=new LinkedHashMap<String,Object>();result.put("items",items);result.put("hasMore",more);result.put("nextPageToken",next);
        boolean full=!inactive&&input.keySet().stream().allMatch(k->Set.of("pageSize","pageToken","includeInactive","overdueOnly","sortOrder").contains(k))&&!bool(input.getOrDefault("overdueOnly","false"));
        result.put("syncCursor",full?start:null);return result;
    }
    private Map<String,Object> mold(long id,Map<String,Object> row){
        var result=new LinkedHashMap<String,Object>();
        result.put("moldSetId",Long.toString(id));result.put("itemId",text(row.get("itemId")));result.put("styleName",row.get("productLineName")!=null?row.get("productLineName"):row.get("styleName"));result.put("assetNumber",row.get("assetNumber"));result.put("setNo",row.get("setNo"));
        result.putAll(productAssociations(((Number)row.get("itemId")).longValue()));
        result.put("productSchemaVersion",2);
        var phones=compatiblePhones(id);result.put("compatiblePhones",phones);
        result.put("compatiblePhonesText",phoneText(phones,row.get("compatibleModels")));
        result.put("owner",row.get("ownerUserId")==null?null:nullableMap("userId",text(row.get("ownerUserId")),"displayName",row.get("ownerName")));
        var dates=new LinkedHashMap<String,Object>();for(String key:DATES)dates.put(key,text(row.get(key)));result.put("dates",dates);
        String status=Boolean.TRUE.equals(row.get("schedulePaused"))?"paused":Boolean.TRUE.equals(row.get("applicationPlanned"))?"planned":Objects.toString(row.get("scheduleStatus"),"pending");
        result.put("deliveryStatus",status);result.put("deliveryStatusText",STATUS.getOrDefault(status,status));
        var tasks=new ArrayList<Map<String,Object>>();Object values=row.get("overdueTasks");
        if(values instanceof List<?> list)for(Object value:list)if(value instanceof com.yuewei.plm.module.collaboration.MoldScheduleTypes.Task t)tasks.add(Map.of("taskKey",t.taskKey(),"title",t.title(),"dueField",t.dueField(),"dueDate",t.dueDate(),"overdueDays",t.overdueDays()));
        result.put("overdue",!tasks.isEmpty());result.put("overdueTasks",tasks);result.put("asOfDate",MoldSchedulePolicy.today().toString());
        var parts=new ArrayList<Map<String,Object>>();for(String prefix:List.of("pc","tpu")){String raw=text(row.get(prefix+"MoldCode"));String part=prefix.toUpperCase(Locale.ROOT);parts.add(nullableMap("partType",part,"materialCode",prefix.equals("pc")?"20":"10","moldCode",ProductBusinessCodeGenerator.isMoldCodeForPart(raw,part)?raw:null));}result.put("moldParts",parts);
        result.put("writableFields",!properties.isWriteEnabled()||!properties.getScopes().contains("crm:schedule-dates:write")?List.of():Boolean.TRUE.equals(row.get("schedulePaused"))?List.of("actualMexicoArrivalDate"):WRITE_FIELDS);return result;
    }
    private Map<String,Object> phone(long id){
        var rows=jdbc.queryForList("select m.id,m.brand_id,b.name,m.model_name,case when c.deleted_flag=0 and c.status='enabled' then c.code_value end code,m.created_at from plm_phone_model_member m left join plm_phone_brand b on b.id=m.brand_id left join plm_code_item c on c.code_item_id=m.code_item_id where m.id=?",id);
        if(rows.isEmpty())throw new CrmApiException(404,"NOT_FOUND","型号不存在");var p=rows.get(0);
        return nullableMap("phoneModelId",Long.toString(id),"brandId",text(p.get("brand_id")),"brandName",p.get("name"),"modelName",p.get("model_name"),"phoneModelCode",p.get("code"),"createdAt",timestamp(p.get("created_at")));
    }
    private Map<String,List<Map<String,Object>>> productAssociations(long item){
        // Preserve the original explicit item relation scope. Never expand every SKU under a line,
        // or infer mold compatibility from a SKU name/code or a shared material relationship.
        var rows=jdbc.queryForList("""
            select p.product_id,p.product_code,p.product_name,p.product_type,p.parent_product_id,
              p.finished_product_code,p.phone_model_member_id,p.phone_model_code,p.color,p.color_code,
              p.master_data_status,
            """+" (p.product_type='sku' or "+PhoneProductEvidence.historicalSkuSql("p")+") as is_sku "+"""
            from plm_product p where p.deleted_flag=0
              and (p.product_id=(select product_line_id from plm_collaboration_item where item_id=?)
                or exists(select 1 from plm_collaboration_item_project ip where ip.item_id=? and ip.project_id=p.product_id))
            order by p.product_id
            """,item,item);
        return splitProductAssociations(rows);
    }
    static Map<String,List<Map<String,Object>>> splitProductAssociations(List<Map<String,Object>> rows){
        var products=new ArrayList<Map<String,Object>>();var skus=new ArrayList<Map<String,Object>>();
        for(var p:rows){
            if(Boolean.TRUE.equals(p.get("is_sku"))){
                String finished=text(p.get("finished_product_code"));
                skus.add(nullableMap("skuId",text(p.get("product_id")),
                    "skuCode",finished==null||finished.isBlank()?p.get("product_code"):finished,
                    "skuName",p.get("product_name"),"productType",p.get("product_type"),
                    "parentProductId",text(p.get("parent_product_id")),"finishedProductCode",finished,
                    "phoneModelId",text(p.get("phone_model_member_id")),"phoneModelCode",text(p.get("phone_model_code")),
                    "color",p.get("color"),"colorCode",text(p.get("color_code")),"masterDataStatus",p.get("master_data_status")));
            }else{
                products.add(nullableMap("productId",text(p.get("product_id")),"productCode",p.get("product_code"),
                    "productName",p.get("product_name"),"productType",p.get("product_type"),
                    "parentProductId",text(p.get("parent_product_id")),"masterDataStatus",p.get("master_data_status")));
            }
        }
        return Map.of("products",products,"skus",skus);
    }
    private List<Map<String,Object>> compatiblePhones(long id){
        var rows=jdbc.queryForList("""
          select m.id,m.model_name,b.name brand_name,
            case when c.deleted_flag=0 and c.status='enabled' then c.code_value end phone_code,
            plm_crm_openapi_lifecycle('PHONE_MODEL',m.id) lifecycle,
            case when cp.phone_model_id is null then 'APPLICATION_SOURCE'
              when cp.pc_confirmed and cp.tpu_confirmed and nullif(btrim(cp.pc_evidence),'') is not null and nullif(btrim(cp.tpu_evidence),'') is not null then 'CONFIRMED'
              else 'PENDING_CONFIRMATION' end relation
          from plm_phone_model_member m left join plm_phone_brand b on b.id=m.brand_id
          left join plm_code_item c on c.code_item_id=m.code_item_id
          left join plm_collaboration_phone_set_compatibility cp on cp.phone_model_id=m.id and cp.mold_set_id=?
          where cp.phone_model_id is not null or exists(select 1 from plm_mold_application a where a.phone_model_id=m.id and a.mold_set_id=? and a.deleted_flag=0)
          order by m.id
          """,id,id);
        return rows.stream().map(p->nullableMap("phoneModelId",text(p.get("id")),"brandName",p.get("brand_name"),"modelName",p.get("model_name"),"phoneModelCode",p.get("phone_code"),"lifecycleStatus",p.get("lifecycle"),"relationStatus",p.get("relation"))).toList();
    }
    private static String phoneText(List<Map<String,Object>> phones,Object legacy){
        if(phones.isEmpty())return legacy==null?"本套适用手机待核对":"待核对："+legacy;
        return String.join(" / ",phones.stream().map(p->Objects.toString(p.get("brandName"),"")+" "+p.get("modelName")+(p.get("phoneModelCode")==null?"":" · "+p.get("phoneModelCode"))+("CONFIRMED".equals(p.get("relationStatus"))?"":"APPLICATION_SOURCE".equals(p.get("relationStatus"))?"（来源申请，适配待确认）":"（适配待确认）")).toList());
    }
    /** Scope is exactly the explicit set relation or its undeleted source application. */
    private static String phoneScope(String phone,String mold){
        return "(exists(select 1 from plm_collaboration_phone_set_compatibility cp where cp.phone_model_id="+phone+".id and cp.mold_set_id="+mold+".mold_set_id) or exists(select 1 from plm_mold_application a where a.phone_model_id="+phone+".id and a.mold_set_id="+mold+".mold_set_id and a.deleted_flag=0))";
    }
    private static void exact(Map<String,String> input,String key,String column,StringBuilder sql,List<Object> args,boolean numeric){if(input.containsKey(key)){sql.append(" and ").append(column).append("=?");args.add(numeric?positiveId(input.get(key)):input.get(key));}}
    public static long positiveId(String value){try{long n=Long.parseLong(value);if(n<1)throw new IllegalArgumentException();return n;}catch(Exception e){throw CrmApiException.invalid("ID 必须为正整数");}}
    public static int integer(String value,int min,int max){try{int n=Integer.parseInt(value);if(n<min||n>max)throw new IllegalArgumentException();return n;}catch(Exception e){throw CrmApiException.invalid("分页数量必须在 "+min+" 到 "+max+" 之间");}}
    public static boolean bool(String value){if(!Set.of("true","false").contains(value))throw CrmApiException.invalid("布尔参数须为 true 或 false");return Boolean.parseBoolean(value);}
    private static String pattern(String value){if(value==null||value.length()>128)throw CrmApiException.invalid("搜索词过长");return "%"+value.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%";}
    public static String text(Object value){return value==null?null:value.toString();}
    public static Object timestamp(Object value){if(value instanceof java.sql.Timestamp t)return t.toInstant().atOffset(java.time.ZoneOffset.UTC).toString();if(value instanceof OffsetDateTime t)return t.toString();return text(value);}
    public static Map<String,Object> nullableMap(Object... values){var result=new LinkedHashMap<String,Object>();for(int i=0;i<values.length;i+=2)result.put(values[i].toString(),values[i+1]);return result;}
}
