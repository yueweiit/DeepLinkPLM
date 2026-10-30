package com.yuewei.plm.module.moldsales;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuewei.plm.common.exception.BusinessException;
import com.yuewei.plm.common.util.ProductBusinessCodeGenerator;
import com.yuewei.plm.module.moldreference.PhoneProductEvidence;
import com.yuewei.plm.module.pilot.PilotAccessService;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Sales attribution is derived from immutable original rows, never from invented mold records. */
final class MoldSalesHistoricalProjection {
    static final Set<String> CLASSIFICATIONS=Set.of("coded_history","purchased","named_history","pending","historical_reference");
    static final String ORIGINAL_PRODUCT="coalesce(f.source_data->'raw'->>'product','')";
    static final String PHYSICAL_FACT="not (source_kind='eccount' and style_code='AB' and exists(select 1 from plm_mold_sales_history_line hx where hx.source_id="+sealedSourceId("plm_mold_sales_fact")+"))";
    // Archive facts always carry eccount: plus the original 64-character ID. Keep the indexed
    // char(64) column uncast, and reject unexpected lengths instead of truncating an identity.
    private static String sealedSourceId(String alias){return "(case when length("+alias+".source_id)=72 then substring("+alias+".source_id,9)::char(64) end)";}
    record SourceScope(Set<List<String>> pairs){
        static SourceScope history(String key){
            var parts=key.split(":");require(parts.length>=3&&parts[0].equals("history")&&parts[1].matches("[A-Z]{2}")&&parts[parts.length-1].matches("[0-9]{4}"),"历史销量组不存在或未获授权");
            return new SourceScope(Set.of(List.of(parts[1],parts[parts.length-1])));
        }
        static SourceScope physical(Map<String,Object> group){
            var pairs=new LinkedHashSet<List<String>>();for(String phone:strings(group.get("phoneCodes"))){pairs.add(List.of(text(group.get("styleCode")),phone));if("BA".equals(group.get("styleCode")))pairs.add(List.of("AB",phone));}return new SourceScope(pairs);
        }
        SourceScope includingSkuAliases(Collection<Map<String,Object>> skus){
            var expanded=new LinkedHashSet<>(pairs);var parser=new ProductBusinessCodeGenerator();for(var sku:skus){String code=text(sku.get("code"));if(!code.matches(ProductBusinessCodeGenerator.PRODUCT_STATE_CODE_REGEX))continue;var parts=parser.parseProductStateCode(code);if(pairs.contains(List.of(text(sku.get("styleCode")),parts.phoneModelCode())))expanded.add(List.of(parts.productSpecificCode(),parts.phoneModelCode()));}return new SourceScope(expanded);
        }
        String sql(String alias,List<Object> args){
            if(pairs.isEmpty())return "false";for(var pair:pairs)args.addAll(pair);String prefix=alias.isBlank()?"":alias+".";
            return "("+prefix+"style_code,"+prefix+"phone_code) in ("+String.join(",",Collections.nCopies(pairs.size(),"(?,?)"))+")";
        }
    }
    private record Category(String slug,String name,String targetStyle) {}
    private record Bucket(String style,String phone,String itemCode,String source,String originalProduct,boolean sealed,String originalClassification,JsonNode evidence,String displayPhoneCode,String displayName,String mappingReason,BigDecimal quantity,long lines,List<String> names) {}
    private record Attribution(String classification,String productKey,Long productId,String productName,String targetStyle,String reason) {}
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final String eligible;
    private final List<Map<String,Object>> salesGroups=new ArrayList<>(),reviewGroups=new ArrayList<>();
    private final Map<String,List<Bucket>> selections=new LinkedHashMap<>(),extras=new LinkedHashMap<>();
    private final Map<String,Map<String,Object>> reviewByKey=new LinkedHashMap<>();
    private final Map<String,Map<String,Object>> salesByKey=new LinkedHashMap<>();
    private final Map<String,Map<String,Object>> roots=new HashMap<>();
    private final Map<String,Map<String,Object>> physical=new HashMap<>(),physicalIdentity=new HashMap<>();
    private final Set<String> catalogued=new HashSet<>(),partnerCodes=new HashSet<>();
    private final Map<String,Map<String,Object>> selfSkus=new HashMap<>();
    private final boolean administrator;
    private final Set<String> ignored;

    MoldSalesHistoricalProjection(JdbcTemplate jdbc,ObjectMapper json,PilotAccessService access,List<Map<String,Object>> existing,Set<String> profiles,Set<String> ignored){
        this(jdbc,json,access,existing,profiles,ignored,null);
    }
    MoldSalesHistoricalProjection(JdbcTemplate jdbc,ObjectMapper json,PilotAccessService access,List<Map<String,Object>> existing,Set<String> profiles,Set<String> ignored,SourceScope requested){
        this.jdbc=jdbc;this.json=json;this.eligible=MoldSalesRules.factEligibleSql(profiles);this.administrator=access.actor().allPermissions();this.ignored=ignored;
        for(var group:existing){var aliases=new HashSet<String>();if(group.get("salesAliases") instanceof Collection<?> values)for(var value:values)if(value instanceof JsonNode alias)aliases.add(alias.path("phoneCode").asText());var originalCodes=new HashSet<String>();if(group.get("productionRecords") instanceof Collection<?> records)for(var record:records)if(record instanceof Map<?,?> item)originalCodes.add(text(item.get("phoneCode")));
            for(String code:strings(group.get("phoneCodes"))){String key=group.get("styleCode")+":"+code;physical.put(key,group);if(!aliases.contains(code)||originalCodes.contains(code))physicalIdentity.put(key,group);}
        }
        var candidates=jdbc.queryForList("select p.product_id as \"productLineId\",p.product_specific_code as \"styleCode\",p.product_name as \"productName\" from plm_product p where p.deleted_flag=0 and p.product_type='product_line' and "+access.visibleSqlClause("product","p.product_id"));
        var counts=new HashMap<String,Integer>();for(var p:candidates)counts.merge(text(p.get("styleCode")),1,Integer::sum);for(var p:candidates)if(counts.get(text(p.get("styleCode")))==1)roots.put(text(p.get("styleCode")),p);
        if(!administrator&&roots.isEmpty()&&physical.isEmpty())return;
        loadCatalogue();if(requested!=null)requested=requested.includingSkuAliases(selfSkus.values());
        var scopeArgs=new ArrayList<Object>();var scopes=new ArrayList<String>();
        if(administrator)scopes.add("true");else{
            if(!roots.isEmpty()){scopes.add("f.style_code in ("+String.join(",",Collections.nCopies(roots.size(),"?"))+")");scopeArgs.addAll(roots.keySet());}
            if(!physical.isEmpty()){var pairs=new ArrayList<String>();for(String key:new TreeSet<>(physical.keySet())){var parts=key.split(":",2);pairs.add("(?,?)");scopeArgs.add(parts[0]);scopeArgs.add(parts[1]);}scopes.add("(f.style_code,f.phone_code) in ("+String.join(",",pairs)+")");}
            var visibleBaPhones=new TreeSet<String>();for(String key:physicalIdentity.keySet())if(key.startsWith("BA:"))visibleBaPhones.add(key.substring(3));
            if(roots.containsKey("BA")||!visibleBaPhones.isEmpty()){
                var aliases=CATEGORIES.entrySet().stream().filter(e->"BA".equals(e.getValue().targetStyle())).map(Map.Entry::getKey).sorted().toList();String transparent="(h.source_id is not null and f.style_code='AB' and lower(regexp_replace(btrim("+ORIGINAL_PRODUCT+"),'\\s+',' ','g')) in ("+String.join(",",Collections.nCopies(aliases.size(),"?"))+")";scopeArgs.addAll(aliases);
                if(!roots.containsKey("BA")){transparent+=" and f.phone_code in ("+String.join(",",Collections.nCopies(visibleBaPhones.size(),"?"))+")";scopeArgs.addAll(visibleBaPhones);}scopes.add(transparent+")");
            }
        }
        String scope="("+String.join(" or ",scopes)+")";if(requested!=null)scope+=" and "+requested.sql("f",scopeArgs);
        String sql="select f.style_code as style,f.phone_code as phone,f.item_code as code,f.source_kind as source,"+ORIGINAL_PRODUCT+" as product,(h.source_id is not null) as sealed,coalesce(g.classification,'') as classification,coalesce(g.evidence,'{}'::jsonb)::text as evidence,coalesce(h.display_phone_code,'') as \"displayPhoneCode\",coalesce(h.display_name,'') as \"displayName\",coalesce(h.mapping_reason,'') as \"mappingReason\",sum(f.quantity) as quantity,count(*) as lines,array_to_json(array_agg(distinct coalesce(f.source_data->>'name','')))::text as names from (select * from plm_mold_sales_fact where "+eligible+") f left join plm_mold_sales_history_line h on f.source_kind='eccount' and h.source_id="+sealedSourceId("f")+" left join plm_mold_sales_history_group g on g.batch_id=h.batch_id and g.style_code=h.style_code and g.phone_code=h.phone_code where "+scope+" group by f.style_code,f.phone_code,f.item_code,f.source_kind,"+ORIGINAL_PRODUCT+",h.source_id is not null,g.classification,g.evidence,h.display_phone_code,h.display_name,h.mapping_reason";
        var rows=scopeArgs.isEmpty()?jdbc.queryForList(sql):jdbc.queryForList(sql,scopeArgs.toArray());
        for(var row:rows){
            var bucket=new Bucket(text(row.get("style")),text(row.get("phone")),text(row.get("code")),text(row.get("source")),text(row.get("product")),Boolean.TRUE.equals(row.get("sealed")),text(row.get("classification")),node(row.get("evidence")),text(row.get("displayPhoneCode")),text(row.get("displayName")),text(row.get("mappingReason")),amount(row.get("quantity")),number(row.get("lines")),texts(node(row.get("names"))));
            var attribution=resolve(bucket);
            if(administrator&&(bucket.sealed()||attribution.classification().equals("pending")||attribution.classification().equals("historical_reference")))review(bucket,attribution);
            if(attribution.productKey()!=null&&!attribution.classification().equals("purchased"))sale(bucket,attribution);
        }
        salesGroups.addAll(salesByKey.values());reviewGroups.addAll(reviewByKey.values());
        reviewGroups.sort(Comparator.<Map<String,Object>,BigDecimal>comparing(g->amount(g.get("quantity"))).reversed().thenComparing(g->text(g.get("sourceGroupKey"))));
    }

    private void loadCatalogue(){
        var phoneCounts=new HashMap<String,Integer>();for(var r:jdbc.queryForList("select code_value,count(*) n from plm_code_item where code_type='phone_model' and deleted_flag=0 group by code_value"))phoneCounts.put(text(r.get("code_value")),((Number)r.get("n")).intValue());
        var parser=new ProductBusinessCodeGenerator();for(String code:jdbc.queryForList("select upper(btrim(mold_code)) from plm_mold_code_source where not source_disabled",String.class))if(ProductBusinessCodeGenerator.isCompleteMoldCode(code)){
            var parts=parser.parseMoldCode(code,null,List.of(),null);if(roots.containsKey(parts.productSpecificCode())&&phoneCounts.getOrDefault(parts.phoneModelCode(),0)==1)catalogued.add(parts.productSpecificCode()+":"+parts.phoneModelCode());
        }
        partnerCodes.addAll(jdbc.queryForList("select distinct source_code from plm_master_data_source where source_kind='product_sku' and object_type='partner'",String.class));
        var skuRows=jdbc.queryForList("select sku.finished_product_code as code,line.product_id as \"productLineId\",line.product_name as \"productName\",line.product_specific_code as \"styleCode\" from plm_product sku left join plm_product parent on parent.product_id=sku.parent_product_id and parent.deleted_flag=0 left join plm_product line on line.product_id=case when parent.product_type='product_line' then parent.product_id else parent.parent_product_id end and line.product_type='product_line' and line.deleted_flag=0 where sku.deleted_flag=0 and "+PhoneProductEvidence.historicalSkuSql("sku"));
        var duplicates=new HashSet<String>();for(var sku:skuRows)if(roots.containsKey(text(sku.get("styleCode")))){String code=text(sku.get("code"));if(selfSkus.putIfAbsent(code,sku)!=null)duplicates.add(code);}for(String code:duplicates)selfSkus.remove(code);
    }

    private Attribution resolve(Bucket b){
        if(b.sealed()&&b.style().equals("AB")){
            var category=category(b.originalProduct());
            if(category!=null){
                var target=category.targetStyle()==null?null:roots.get(category.targetStyle());if(target==null&&category.targetStyle()!=null)target=physicalIdentity.get(category.targetStyle()+":"+b.phone());
                if(category.targetStyle()!=null&&target==null)return unknown(b,"已确认的亮甲产品资料当前不可用");
                return new Attribution("named_history",target==null?"history:AB:"+category.slug():productKey(target),target==null?null:number(target.get("productLineId")),target==null?category.name():text(target.get("productName")),category.targetStyle(),"按原商品名称逐行归属，原商品编码和颜色保留");
            }
            return unknown(b,b.originalProduct().isBlank()?"原商品名称未记录":"原商品名称“"+b.originalProduct()+"”暂无法辨认");
        }
        var originalPhysical=physical.get(b.style()+":"+b.phone());
        if(originalPhysical!=null)return target("coded_history",originalPhysical,"现有明确档案销售归属");
        if(b.style().equals("RU")&&roots.containsKey("RU"))return target("coded_history",roots.get("RU"),"三合一历史订单已恢复；原手机编码保留，实物套数待核");
        var sku=selfSkus.get(b.itemCode());if(sku!=null)return target("coded_history",sku,"现有成品SKU明确归属");
        if(partnerCodes.contains(b.itemCode())&&!selfSkus.containsKey(b.itemCode()))return new Attribution("purchased",null,null,"外购销量",null,"外购来源已明确，不分摊自有模具成本");
        if(b.originalClassification().equals("coded_history")&&catalogued.contains(b.style()+":"+b.phone()))return target("coded_history",roots.get(b.style()),"原完整模具编码与当前产品及手机编码一致，仅作历史销售归属");
        return unknown(b,"产品或手机销售归属尚未明确");
    }

    private Attribution unknown(Bucket b,String why){
        boolean old=b.source().equals("eccount")||ignored.contains(b.style()+":"+b.phone());
        return new Attribution(old?"historical_reference":"pending",null,null,"",null,old?why+"；历史待考，不计入当前待补":why);
    }
    private Attribution target(String kind,Map<String,Object> product,String why){return new Attribution(kind,productKey(product),number(product.get("productLineId")),text(product.getOrDefault("productName",product.get("styleName"))),text(product.get("styleCode")),why);}
    static String productKey(Map<String,Object> product){return product.get("productKey")!=null?text(product.get("productKey")):"product:"+number(product.get("productLineId"));}
    static String groupKey(Map<String,Object> group){return group.get("sourceGroupKey")!=null?text(group.get("sourceGroupKey")):"item:"+number(group.get("groupId"));}

    private void sale(Bucket b,Attribution a){
        var target=a.targetStyle()==null?null:(b.sealed()&&b.style().equals("AB")?physicalIdentity:physical).get(a.targetStyle()+":"+b.phone());
        if(target!=null){
            if(b.sealed()&&b.style().equals("AB")||!b.style().equals(a.targetStyle())){
                String key=groupKey(target);extras.computeIfAbsent(key,k->new ArrayList<>()).add(b);
            }
            return;
        }
        String key="history:"+b.style()+":"+(b.style().equals("AB")?category(b.originalProduct()).slug()+":":"")+b.phone();
        var group=salesByKey.computeIfAbsent(key,k->{var g=new LinkedHashMap<String,Object>();g.put("sourceGroupKey",k);g.put("groupId",null);g.put("itemIds",List.of());g.put("productKey",a.productKey());g.put("productLineId",a.productId());g.put("styleName",a.productName());g.put("productName",a.productName());g.put("styleCode",a.targetStyle()==null?b.style():a.targetStyle());g.put("phoneCodes",new TreeSet<>(Set.of(b.phone())));g.put("modelNames",new LinkedHashSet<String>());g.put("originalProductNames",new LinkedHashSet<String>());g.put("physicalSets",null);g.put("physicalSetsKnown",false);g.put("productionRecords",List.of());g.put("readonly",true);g.put("sourceOnly",true);g.put("salesScopeOnly",true);g.put("canWriteCost",false);g.put("salesQuantity",BigDecimal.ZERO);g.put("sourceBreakdown",new LinkedHashMap<String,Object>(Map.of("eccount",BigDecimal.ZERO,"crm",BigDecimal.ZERO)));g.put("historyReviewRows",0L);g.put("associationReason",a.reason());return g;});
        mergeNames(group,"modelNames",b.names());if(!b.displayName().isBlank())mergeNames(group,"modelNames",List.of(b.displayName()));if(!b.originalProduct().isBlank())mergeNames(group,"originalProductNames",List.of(b.originalProduct()));
        group.put("salesQuantity",amount(group.get("salesQuantity")).add(b.quantity()));addSource(group,b);group.put("historyReviewRows",number(group.get("historyReviewRows"))+b.lines());
        selections.computeIfAbsent(key,k->new ArrayList<>()).add(b);

    }
    @SuppressWarnings("unchecked") private void addSource(Map<String,Object> group,Bucket b){var sources=(Map<String,Object>)group.get("sourceBreakdown");sources.put(b.source(),amount(sources.get(b.source())).add(b.quantity()));}

    private void review(Bucket b,Attribution a){
        String key="review:"+b.style()+":"+b.phone()+":"+a.classification()+":"+(a.productKey()==null?"unresolved":a.productKey().replace(':','-'));
        var row=reviewByKey.computeIfAbsent(key,k->{var r=new LinkedHashMap<String,Object>();r.put("sourceGroupKey",k);r.put("styleCode",b.style());r.put("phoneCode",b.phone());r.put("productKey",a.productKey());r.put("productLineId",a.productId());r.put("classification",a.classification());r.put("quantity",BigDecimal.ZERO);r.put("lineCount",0L);var e=new LinkedHashMap<String,Object>();e.put("styleName",a.productName().isBlank()?(roots.containsKey(b.style())?text(roots.get(b.style()).get("productName")):b.evidence().path("styleName").asText(b.style())):a.productName());e.put("phoneName",b.evidence().path("phoneName").asText(b.names().isEmpty()?"原编码 "+b.phone():String.join(" / ",b.names())));e.put("sourceMoldCodes",b.evidence().path("sourceMoldCodes"));e.put("originalMoldCodes",b.evidence().path("originalMoldCodes").asText());e.put("missingEvidence",a.reason());e.put("recommendation",a.reason());e.put("originalItemCodes",new TreeSet<String>());e.put("originalProductNames",new TreeSet<String>());e.put("originalNames",new TreeSet<String>());e.put("displayNames",new TreeSet<String>());e.put("displayPhoneCodes",new TreeSet<String>());e.put("originalNamesWithQuantity","");e.put("nameReview",b.evidence().path("nameReview").asText());e.put("nameCandidates",b.evidence().path("nameCandidates").asText());e.put("codeConflict",b.evidence().path("codeConflict").asBoolean());r.put("evidence",e);return r;});
        row.put("quantity",amount(row.get("quantity")).add(b.quantity()));row.put("lineCount",number(row.get("lineCount"))+b.lines());
        @SuppressWarnings("unchecked") var evidence=(Map<String,Object>)row.get("evidence");mergeNames(evidence,"originalItemCodes",List.of(b.itemCode()));mergeNames(evidence,"originalProductNames",List.of(b.originalProduct()));mergeNames(evidence,"originalNames",b.names());mergeNames(evidence,"displayNames",List.of(b.displayName()));mergeNames(evidence,"displayPhoneCodes",List.of(b.displayPhoneCode()));evidence.put("originalNamesWithQuantity",String.join(" / ",strings(evidence.get("originalNames"))));
        selections.computeIfAbsent(key,k->new ArrayList<>()).add(b);
    }

    Map<String,Object> summary(){
        var categories=new LinkedHashMap<String,Map<String,Object>>();for(String kind:List.of("coded_history","purchased","named_history","historical_reference","pending"))categories.put(kind,new LinkedHashMap<>(Map.of("groups",0L,"rows",0L,"quantity",BigDecimal.ZERO)));
        BigDecimal quantity=BigDecimal.ZERO;long lines=0;for(var row:reviewGroups){var c=categories.get(text(row.get("classification")));c.put("groups",number(c.get("groups"))+1);c.put("rows",number(c.get("rows"))+number(row.get("lineCount")));c.put("quantity",amount(c.get("quantity")).add(amount(row.get("quantity"))));quantity=quantity.add(amount(row.get("quantity")));lines+=number(row.get("lineCount"));}
        return Map.of("groups",reviewGroups.size(),"rows",lines,"quantity",quantity,"categories",categories,"realtime",true);
    }
    List<Map<String,Object>> salesGroups(){return salesGroups;}
    List<Map<String,Object>> pendingRows(){var result=new ArrayList<Map<String,Object>>();for(var original:reviewGroups)if(original.get("classification").equals("pending")){var row=new LinkedHashMap<String,Object>();row.put("styleCode",original.get("styleCode"));row.put("phoneCode",original.get("phoneCode"));row.put("quantity",original.get("quantity"));row.put("lines",original.get("lineCount"));@SuppressWarnings("unchecked") var evidence=(Map<String,Object>)original.get("evidence");row.put("names",json.valueToTree(evidence.get("originalNames")));result.add(row);}return result;}
    BigDecimal attributedQuantity(String key){return extras.getOrDefault(key,List.of()).stream().map(Bucket::quantity).reduce(BigDecimal.ZERO,BigDecimal::add);}
    Map<String,BigDecimal> attributedSources(String key){var totals=new LinkedHashMap<String,BigDecimal>(Map.of("eccount",BigDecimal.ZERO,"crm",BigDecimal.ZERO));for(var bucket:extras.getOrDefault(key,List.of()))totals.merge(bucket.source(),bucket.quantity(),BigDecimal::add);return totals;}
    Map<String,Object> metadata(Map<String,Object> initial){var result=new LinkedHashMap<>(initial);result.put("initialSummary",initial.get("summary"));var current=summary();result.put("summary",current);result.put("liveSummary",current);return result;}

    Map<String,Object> page(int page,int size,String keyword,String classification,Map<String,Object> initial){
        require(page>0&&size>0&&size<=100&&(classification.equals("all")||CLASSIFICATIONS.contains(classification)),"历史资料分页或分类无效");
        String term=keyword.strip().toLowerCase(Locale.ROOT);var rows=reviewGroups.stream().filter(r->(classification.equals("all")||classification.equals(r.get("classification")))&&(term.isBlank()||r.toString().toLowerCase(Locale.ROOT).contains(term))).toList();
        var result=metadata(initial);int from=(int)Math.min(rows.size(),(long)(page-1)*size),to=(int)Math.min(rows.size(),(long)page*size);result.put("content",rows.subList(from,to));result.put("page",page);result.put("size",size);result.put("totalElements",rows.size());result.put("totalPages",(rows.size()+size-1)/size);return result;
    }
    Map<String,Object> reviewDetail(String key,int page,int size){var group=reviewByKey.get(key);require(group!=null,"历史资料不存在或未获授权");var result=orders(key,page,size);result.put("group",group);result.put("sourceLabel","原始销售订单");result.put("mappingScope","sales_attribution_only");return result;}
    Map<String,Object> orders(String key,int page,int size){
        require(page>0&&size>0&&size<=100,"分页参数无效");var buckets=selections.get(key);require(buckets!=null&&!buckets.isEmpty(),"历史销量组不存在或未获授权");var args=new ArrayList<Object>();String where=selectionSql(buckets,args);var total=jdbc.queryForObject("select count(*) from plm_mold_sales_fact f where "+eligible+" and ("+where+")",Long.class,args.toArray());args.add(size);args.add((long)(page-1)*size);
        String fields="f.source_id as \"sourceId\",f.order_id as \"orderId\",f.order_no as \"orderNo\",f.order_status as \"orderStatus\",f.item_code as \"itemCode\",f.phone_code as \"phoneCode\",f.quantity,f.unit,'eligible' as eligibility,coalesce(f.source_data->>'name','') as name,coalesce(f.source_data->>'name','') as \"originalName\","+ORIGINAL_PRODUCT+" as \"originalProductName\",f.source_data->>'spec' as specification,f.business_date::text as \"orderDate\",f.business_date::text as \"businessDate\",f.source_kind as source,f.company,f.source_file as \"sourceFile\",f.source_sheet as \"sourceSheet\",f.source_row as \"sourceRow\",hh.display_phone_code as \"displayPhoneCode\",hh.display_name as \"displayName\",hh.mapping_reason as \"mappingReason\"";
        var rows=jdbc.queryForList("select "+fields+" from (select * from plm_mold_sales_fact where "+eligible+") f left join plm_mold_sales_history_line hh on f.source_kind='eccount' and hh.source_id="+sealedSourceId("f")+" where ("+where+") order by f.business_date desc,f.source_id limit ? offset ?",args.toArray());var result=new LinkedHashMap<String,Object>();result.put("orders",rows);result.put("totalElements",total);result.put("page",page);result.put("size",size);return result;
    }
    String attributedSql(String key,List<Object> args){var buckets=extras.get(key);return buckets==null?"false":selectionSql(buckets,args);}
    private String selectionSql(List<Bucket> buckets,List<Object> args){
        // Expose the indexed original style/phone and source predicates before the row-level
        // name/sealed test. The exact six-part selection still decides ownership and dedupes.
        var prefixes=new LinkedHashSet<List<Object>>();for(var b:buckets)prefixes.add(List.of(b.style(),b.phone(),b.source()));for(var prefix:prefixes)args.addAll(prefix);
        String scope="(f.style_code,f.phone_code,f.source_kind) in ("+String.join(",",Collections.nCopies(prefixes.size(),"(?,?,?)"))+")";
        var rows=new ArrayList<String>();var seen=new HashSet<List<Object>>();for(var b:buckets){var tuple=List.<Object>of(b.style(),b.phone(),b.itemCode(),b.source(),b.originalProduct(),b.sealed());if(!seen.add(tuple))continue;rows.add("(?,?,?,?,?,?)");args.addAll(tuple);}
        return scope+" and (f.style_code,f.phone_code,f.item_code,f.source_kind,"+ORIGINAL_PRODUCT+",exists(select 1 from plm_mold_sales_history_line sh where f.source_kind='eccount' and sh.source_id="+sealedSourceId("f")+")) in ("+String.join(",",rows)+")";
    }
    private static final Map<String,Category> CATEGORIES=Map.ofEntries(
        Map.entry("airbag pintura羽绒服喷油",new Category("airbag-pintura","羽绒服喷油",null)),Map.entry("airbag pintura",new Category("airbag-pintura","羽绒服喷油",null)),
        Map.entry("airbag liso羽绒服透明",new Category("airbag-liso","羽绒服透明",null)),Map.entry("airbag liso",new Category("airbag-liso","羽绒服透明",null)),
        Map.entry("rainbow transparente亮甲透色",new Category("rainbow-transparente","亮甲（透明色）","BA")),Map.entry("rainbow transparente",new Category("rainbow-transparente","亮甲（透明色）","BA")),
        Map.entry("rainbow pintura",new Category("rainbow-pintura","RAINBOW PINTURA",null)),
        Map.entry("rainbow doble亮甲渐变",new Category("rainbow-doble","亮甲渐变（历史品名）",null)),Map.entry("rainbow doble",new Category("rainbow-doble","亮甲渐变（历史品名）",null)));
    private static Category category(String original){return CATEGORIES.get(original.strip().replaceAll("\\s+"," ").toLowerCase(Locale.ROOT));}
    private JsonNode node(Object value){try{return json.readTree(value.toString());}catch(Exception e){throw new IllegalStateException(e);}}
    private static List<String> texts(JsonNode values){var result=new ArrayList<String>();for(var value:values)result.add(value.asText());return result;}
    @SuppressWarnings("unchecked") private static Collection<String> strings(Object value){return value==null?List.of():(Collection<String>)value;}
    @SuppressWarnings("unchecked") private static void mergeNames(Map<String,Object> target,String key,Collection<String> names){for(String name:names)if(!name.isBlank())((Collection<String>)target.get(key)).add(name);}
    private static String text(Object value){return Objects.toString(value,"");}
    private static long number(Object value){return value instanceof Number n?n.longValue():0;}
    private static BigDecimal amount(Object value){return value==null?BigDecimal.ZERO:new BigDecimal(value.toString());}
    private static void require(boolean valid,String message){if(!valid)throw new BusinessException(40001,message);}
}
