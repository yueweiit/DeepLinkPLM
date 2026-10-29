package com.yuewei.plm.module.masterdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuewei.plm.common.exception.BusinessException;
import java.math.BigDecimal;
import java.util.*;

/** Exact source identities and scopes; deliberately independent from display labels. */
public final class MasterDataSnapshot {
    public static final Map<String,String> CRM_KINDS=Map.of("series","product_series","skus","product_sku","phones","phone_model","brands","phone_brand","colors","product_color_encoding");
    public static final Map<String,String> MES_KINDS=Map.ofEntries(Map.entry("products","mes_product"),Map.entry("boms","bom_main"),Map.entry("bom_items","bom_child"),Map.entry("bom_bindings","mes_product_bom_binding"),Map.entry("route_bindings","product_route_binding"),Map.entry("routes","process_flows"),Map.entry("nodes","process_flow_steps"),Map.entry("edges","process_flow_step_edges"),Map.entry("operations","operation_templates"),Map.entry("materials","material_items"),Map.entry("item_codes","mes_product_item_code"),Map.entry("item_colors","mes_product_item_color"));
    private final JsonNode data;
    private final Set<String> listedIds,purchasedSeries;
    private final Map<String,JsonNode> series=new LinkedHashMap<>(),phones=new LinkedHashMap<>(),brands=new LinkedHashMap<>(),routes=new LinkedHashMap<>(),nodes=new LinkedHashMap<>(),boms=new LinkedHashMap<>();

    public MasterDataSnapshot(JsonNode data) {
        this.data=data.deepCopy();
        listedIds=Set.copyOf(strings(data.path("shop").path("listedSkuIds")));
        purchasedSeries=Set.copyOf(strings(data.path("classification").path("purchasedSeriesCodes")));
        require(data.path("formatVersion").asInt()==1&&data.path("complete").asBoolean(),"来源快照不完整或版本不支持");
        required(data,"organizationId");required(data,"capturedAt");
        for(String key:CRM_KINDS.keySet())unique(rows("crm",key),"id","CRM "+key);
        for(String key:MES_KINDS.keySet())unique(rows("mes",key),"id","MES "+key);
        require(!rows("crm","skus").isEmpty(),"来源 SKU 清单为空，不能作为全量删除依据");
        index(series,rows("crm","series"),"id");index(phones,rows("crm","phones"),"id");index(brands,rows("crm","brands"),"id");
        index(routes,rows("mes","routes"),"route_code");index(nodes,rows("mes","nodes"),"node_code");index(boms,rows("mes","boms"),"bom_code");
        validate();
    }
    public JsonNode data(){return data;}
    public String organization(){return required(data,"organizationId");}
    public List<JsonNode> rows(String section,String name){
        JsonNode value=data.path(section).path(name);require(value.isArray(),"来源集合缺失："+section+"/"+name);
        var out=new ArrayList<JsonNode>();value.forEach(out::add);return List.copyOf(out);
    }
    public JsonNode series(JsonNode sku){return series.get(required(sku,"productSeriesId"));}
    public JsonNode phone(JsonNode sku){return phones.get(required(sku,"phoneModelId"));}
    public JsonNode brand(JsonNode phone){return brands.get(required(phone,"phoneBrandId"));}
    public boolean purchased(JsonNode sku){return purchasedSeries.contains(text(series(sku),"seriesCode"));}
    public boolean listed(JsonNode sku){return listedIds.contains(text(sku,"id"));}
    public JsonNode route(String code){return routes.get(code);}
    public JsonNode node(String code){return nodes.get(code);}
    public List<JsonNode> bindings(String bomCode){return rows("mes","bom_bindings").stream().filter(b->bomCode.equals(text(b,"bom_code"))).toList();}
    public List<JsonNode> items(String bomCode){return rows("mes","bom_items").stream().filter(i->bomCode.equals(text(i,"bom_code"))).toList();}

    public List<JsonNode> routesFor(JsonNode sku){
        String product=text(series(sku),"seriesCode");var result=new LinkedHashMap<String,JsonNode>();
        for(var binding:rows("mes","route_bindings"))if(product.equals(text(binding,"product_code"))&&"enabled".equals(text(binding,"status"))&&matchesColor(binding.path("condition_json"),sku)) {
            String code=text(binding,"route_code");if(routes.containsKey(code))result.put(code,routes.get(code));
        }
        return List.copyOf(result.values());
    }
    public Optional<JsonNode> bomFor(JsonNode sku,String routeCode){
        require(routesFor(sku).stream().anyMatch(r->routeCode.equals(text(r,"route_code"))),"所选路线不适用于该 SKU");
        var matches=rows("mes","bom_bindings").stream().filter(b->text(series(sku),"seriesCode").equals(text(b,"product_code")))
            .filter(b->"enabled".equals(text(b,"status"))).filter(b->{var scope=b.path("condition_json").path("workbook_scope");return strings(scope.path("article_codes")).contains(text(sku,"itemCode"))&&strings(scope.path("route_codes")).contains(routeCode);})
            .sorted(Comparator.comparingInt((JsonNode b)->b.path("priority").asInt()).reversed()).toList();
        if(matches.isEmpty())return Optional.empty();int priority=matches.get(0).path("priority").asInt();
        require(matches.stream().filter(b->b.path("priority").asInt()==priority).map(b->text(b,"bom_code")).distinct().count()==1,"当前 SKU 和路线有同优先级的冲突 BOM，请人工核对");
        return Optional.of(boms.get(text(matches.get(0),"bom_code")));
    }
    public List<JsonNode> recipe(JsonNode sku,String routeCode){
        var bom=bomFor(sku,routeCode);if(bom.isEmpty())return List.of();String article=required(sku,"itemCode");
        var applicable=items(text(bom.get(),"bom_code")).stream().filter(i->i.path("route_targets").has(routeCode)).toList();
        var scoped=applicable.stream().filter(i->i.path("route_targets").path(routeCode).has("article_codes")).toList();
        require(scoped.isEmpty()||scoped.stream().anyMatch(i->strings(i.path("route_targets").path(routeCode).path("article_codes")).contains(article)),"共享 BOM 缺少当前成品的型号物料范围");
        return applicable.stream().filter(i->{var target=i.path("route_targets").path(routeCode);return !target.has("article_codes")||strings(target.path("article_codes")).contains(article);}).toList();
    }
    private boolean matchesColor(JsonNode condition,JsonNode sku){
        if(condition.isMissingNode()||condition.isNull())return true;
        allowedKeys(condition,Set.of("color_names","exclude_color_names"),"路线适用条件");
        var names=new HashSet<String>();for(String f:List.of("colorCode","colorNameZh","colorNameEn","colorNameEs"))if(text(sku,f)!=null)names.add(text(sku,f).strip().toLowerCase(Locale.ROOT));
        var included=strings(condition.path("color_names"));var excluded=strings(condition.path("exclude_color_names"));
        return (included.isEmpty()||included.stream().anyMatch(n->names.contains(n.strip().toLowerCase(Locale.ROOT))))&&excluded.stream().noneMatch(n->names.contains(n.strip().toLowerCase(Locale.ROOT)));
    }
    private void validate(){
        for(var row:rows("crm","skus")){
            require(organization().equals(text(row,"organizationId")),"CRM SKU 组织不一致");required(row,"itemCode");
            require(series.containsKey(text(row,"productSeriesId"))&&phones.containsKey(text(row,"phoneModelId")),"CRM SKU 引用缺失");
        }
        for(var row:rows("crm","phones")){
            require(organization().equals(text(row,"organizationId"))&&brands.containsKey(text(row,"phoneBrandId")),"CRM 型号组织或品牌引用不一致");
            String code=text(row,"modelCode");require(code==null||code.matches("[0-9]{4}"),"CRM 手机编码须为四位字符串；不能自动分配或截断");
        }
        for(var row:rows("crm","brands"))require(organization().equals(text(row,"organizationId")),"CRM 品牌组织不一致");
        for(var row:rows("crm","series"))require(organization().equals(text(row,"organizationId")),"CRM 系列组织不一致");
        var skuIds=new HashSet<String>();rows("crm","skus").forEach(s->skuIds.add(required(s,"id")));
        require(skuIds.containsAll(strings(data.path("shop").path("listedSkuIds"))),"商城目录含来源清单以外的 SKU");
        var operations=new HashSet<String>();rows("mes","operations").forEach(o->operations.add(required(o,"operation_code")));
        var materials=new HashSet<String>();rows("mes","materials").forEach(m->materials.add(required(m,"material_code")));
        for(var node:nodes.values())require(routes.containsKey(text(node,"route_code"))&&operations.contains(required(node,"operation_code")),"路线节点的所属路线或工序定义不存在");
        validateGraph();
        for(var bom:boms.values()){require(bom.path("is_active").asBoolean()&&decimal(bom,"output_qty").signum()>0,"BOM 未生效或基准产量无效");precision(decimal(bom,"output_qty"),14,4);}
        for(var binding:rows("mes","bom_bindings")){
            require(boms.containsKey(text(binding,"bom_code")),"BOM 绑定引用不存在");
            var condition=binding.path("condition_json");allowedKeys(condition,Set.of("shared_recipe","workbook_scope"),"BOM 绑定条件");
            var scope=condition.path("workbook_scope");allowedKeys(scope,Set.of("route_codes","article_codes","source_sha256"),"BOM 范围");
            require(scope.path("route_codes").isArray()&&scope.path("article_codes").isArray(),"缺少明确款号或路线范围，不能推导为全部适用");
            require(routes.keySet().containsAll(strings(scope.path("route_codes"))),"BOM 范围引用的路线不存在");
        }
        for(var item:rows("mes","bom_items")){
            require(boms.containsKey(text(item,"bom_code"))&&decimal(item,"quantity").signum()>0,"BOM 明细引用或数量无效");
            precision(decimal(item,"quantity"),16,8);require(materials.contains(Objects.requireNonNullElse(text(item,"child_code_normalized"),required(item,"child_code"))),"BOM 组件未包含在本期必要物料中");
            required(item,"unit");var targets=item.path("route_targets");require(targets.isObject()&&!targets.isEmpty(),"BOM 行缺少明确投料节点，不能自动猜测");
            targets.fields().forEachRemaining(entry->{
                String route=entry.getKey();var target=entry.getValue();allowedKeys(target,Set.of("node_code","process_code","process_name","article_codes"),"BOM 行投料条件");
                var node=nodes.get(required(target,"node_code"));require(node!=null&&route.equals(text(node,"route_code")),"BOM 行投料节点不属于所选路线");
                require(text(node,"operation_code").equals(required(target,"process_code")),"BOM 行投料工序与节点不一致");
                if(target.has("article_codes"))require(target.path("article_codes").isArray(),"型号物料范围格式无效");
            });
        }
        for(var binding:rows("mes","route_bindings")){require(routes.containsKey(text(binding,"route_code")),"路线绑定不存在");matchesColor(binding.path("condition_json"),rows("crm","skus").get(0));}
    }
    private void validateGraph(){
        var degrees=new HashMap<String,Integer>();var successors=new HashMap<String,List<String>>();nodes.keySet().forEach(k->degrees.put(k,0));
        var pairs=new HashSet<String>();
        for(var edge:rows("mes","edges")){
            String from=required(edge,"from_node_code"),to=required(edge,"to_node_code"),route=required(edge,"route_code");
            require(nodes.containsKey(from)&&nodes.containsKey(to)&&route.equals(text(nodes.get(from),"route_code"))&&route.equals(text(nodes.get(to),"route_code")),"路线连接端点不在同一路线版本内");
            require(!from.equals(to)&&pairs.add(from+"\n"+to),"路线存在自连接或重复连接");
            required(edge,"relation_type");degrees.put(to,degrees.get(to)+1);successors.computeIfAbsent(from,k->new ArrayList<>()).add(to);
        }
        var queue=new ArrayDeque<String>();degrees.forEach((k,v)->{if(v==0)queue.add(k);});int visited=0;
        while(!queue.isEmpty()){String n=queue.remove();visited++;for(String next:successors.getOrDefault(n,List.of()))if(degrees.compute(next,(k,v)->v-1)==0)queue.add(next);}
        require(visited==nodes.size(),"工艺路线存在循环，不能导入或确认");
    }
    private static void precision(BigDecimal value,int integer,int fraction){var exact=value.stripTrailingZeros();require(exact.scale()<=fraction&&exact.precision()-exact.scale()<=integer,"来源数量超出已核实精度，禁止静默舍入");}
    private static void index(Map<String,JsonNode> out,List<JsonNode> values,String field){for(var v:values)require(out.put(required(v,field),v)==null,"来源业务键重复："+field);}
    private static void unique(List<JsonNode> values,String field,String kind){var keys=new HashSet<String>();for(var v:values)require(keys.add(required(v,field)),kind+" 来源身份重复，不能按品目码静默去重");}
    private static void allowedKeys(JsonNode value,Set<String> keys,String name){require(value.isObject(),name+"格式无效");value.fieldNames().forEachRemaining(k->require(keys.contains(k),name+"出现尚未核实的字段："+k));}
    public static String text(JsonNode n,String key){if(n==null)return null;var v=n.get(key);return v==null||v.isNull()?null:v.asText();}
    public static String required(JsonNode n,String key){String value=text(n,key);require(value!=null&&!value.isBlank(),"来源字段缺失："+key);return value;}
    public static BigDecimal decimal(JsonNode n,String key){try{return new BigDecimal(required(n,key));}catch(NumberFormatException e){throw invalid("来源数值无效："+key);}}
    public static List<String> strings(JsonNode n){if(n==null||n.isMissingNode()||n.isNull())return List.of();require(n.isArray(),"来源范围必须为数组");var out=new ArrayList<String>();n.forEach(v->{require(v.isTextual()&&!v.asText().isBlank(),"来源范围含空值或非字符串");out.add(v.asText());});return List.copyOf(out);}
    public static void require(boolean valid,String message){if(!valid)throw invalid(message);}
    public static BusinessException invalid(String message){return new BusinessException(40001,message);}
}
