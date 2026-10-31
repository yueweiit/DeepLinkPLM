package com.yuewei.plm.module.partner;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import static com.yuewei.plm.module.partner.PartnerValues.*;

/** Supplier catalogue is a scoped projection of quote/selection rows, not another product library. */
@Service
@RequiredArgsConstructor
public class PartnerCatalogService {
    private static final Set<String> MODEL_SHEET_COMMAND_FIELDS = Set.of("supplierCode", "sourceKind", "recordId", "rowId", "rowVersion", "recordVersion", "entries", "modelSheetRefs", "requestId", "previewToken", "sha256");
    private static final String RESTRICTED_FILE = "exists(select 1 from plm_partner_file x where x.attachment_id=a.attachment_id and x.purpose in ('cooperation_cost','financial'))"
        + " or exists(select 1 from plm_partner_file_job j where j.kind='import' and j.source_attachment_id=a.attachment_id)";
    private static final List<String> SUMMARY_FIELDS = List.of("name", "series", "supplierItemCode",
        "modelText", "color", "material", "specification", "moq", "packaging", "styleKey", "configKey",
        "quoteVersion", "sourcePosition", "sourceSheet", "sourceVersion", "productId", "productCode",
        "thickness", "features", "leadTimeNote", "catalogStyleId", "catalogStyleCode", "catalogStyleLabel", "moqQuantity", "moqUnit", "moqScope", "moqNote", "discarded", "supplierColorIds", "supplierColors");
    private static final Set<String> EDITABLE_FIELDS = Set.of("name","series","supplierItemCode","modelText","color","material","specification","moq","packaging","styleKey","configKey","unitPrice","currency","priceNote","notes","phoneModelIds","phoneScopeDeclaration","availablePhoneModelIds","unavailablePhoneModelIds","rangeRefs","pairings","rangeVersionAcknowledgements","supplyScopeNote","thickness","features","leadTimeNote","sampleFeeNote","priceOptions","priceUnit","moqQuantity","moqUnit","moqScope","moqNote","refs","discarded","sourcePosition","sameStyleKey","developmentEvidence","developmentStatus","developedOn","supplierColorIds","newSupplierColors");
    private static final List<String> COPY_FIELDS = List.of("name", "series", "supplierItemCode", "modelText",
        "color", "material", "specification", "moq", "packaging", "styleKey", "configKey", "quoteVersion",
        "sourcePosition", "sourceSheet", "sourceVersion", "unitPrice", "currency", "priceNote", "notes",
        "quoteId", "quoteLineId", "phoneModelIds", "availablePhoneModelIds", "unavailablePhoneModelIds",
        "rangeRefs", "pairings", "rangeVersionAcknowledgements", "supplyScopeNote", "supplierColorIds",
        "thickness", "features", "leadTimeNote", "sampleFeeNote", "priceOptions", "priceUnit", "moqQuantity", "moqUnit", "moqScope", "moqNote", "currencySource", "rawQuoteFields");
    private final PartnerStore store;
    private final PartnerOperationsAccess permissions;
    private final SupplierProfileService profiles;
    private final PartnerSelectionService selections;
    private final PartnerMaterialService materials;
    private final PartnerCatalogPhoneResolver phones;
    private final PartnerOperationsService operations;

    private void view() {
        store.access.requireDomesticView();
        store.access.requirePermission("partner:view");
    }

    /** The row supplier is explicit. A blank row in a mixed selection never inherits another supplier. */
    private String sourceSql(Map<String,String> query){
        var styles=new PartnerCatalogStyles(store);
        if("true".equals(query.get("deleted")))return "select * from ("+styles.sourceSql(true)+") removed where coalesce(removed.data->>'discarded','false')='true'";
        String raw=styles.sourceSql(false);
        if(!text(query.get("sourceRecordId")).isEmpty()||!text(query.get("sourceKind")).isEmpty())return raw;
        // A later quote/photo carrier is not another product. Rank only already-visible sources;
        // source maintenance and exact deep links continue to expose every original row.
        return """
            select ranked.* from (
              select c.*,row_number() over(partition by c.supplier_code,
                case when link.style_id is null then c.source_kind||':'||c.record_id::text||':'||c.row_id else 'CP:'||link.style_id::text end,
                jsonb_build_array(coalesce(btrim(c.data->>'color'),''),coalesce(btrim(c.data->>'material'),''),
                  coalesce(btrim(c.data->>'specification'),''),coalesce(btrim(c.data->>'thickness'),''),
                  coalesce(btrim(c.data->>'features'),''),coalesce(btrim(c.data->>'supplierItemCode'),''),
                  case when coalesce(btrim(c.data->>'styleKey'),'') in ('',c.row_id,coalesce(btrim(c.data->>'supplierItemCode'),'')) then '' else btrim(c.data->>'styleKey') end,
                  coalesce(nullif(btrim(c.data->>'configKey'),'default'),''))
                order by c.record_id,c.sort_order,c.source_kind,c.row_id) as catalog_source_rank
              from (%s) c left join plm_partner_catalog_style_source link
                on link.source_kind=c.source_kind and link.record_id=c.record_id and link.row_id::text=c.row_id
            ) ranked where catalog_source_rank=1
            """.formatted(raw);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> suppliers(Map<String, String> query) {
        view();
        String filter = "p.deleted_flag=0 and " + store.access.visibleSqlClause("supplier_profile", "p.supplier_id");
        List<Object> arguments = new ArrayList<>();
        String keyword = text(query.getOrDefault("q", query.get("keyword")));
        if (!keyword.isEmpty()) {
            filter += " and (p.supplier_name ilike ? or p.supplier_code ilike ? or p.short_code ilike ?)";
            for (int i = 0; i < 3; i++) arguments.add("%" + keyword + "%");
        }
        long page = page(query), size = size(query);
        String catalog = "with catalog as (" + sourceSql(query) + "), catalog_suppliers as ("
            + "select supplier_code,count(*) as style_count from catalog group by supplier_code) ";
        String scopedSuppliers = "from plm_supplier_profile p join catalog_suppliers c on c.supplier_code=p.supplier_code where " + filter;
        long total = store.jdbc.queryForObject(catalog + "select count(*) " + scopedSuppliers,
            Long.class, arguments.toArray());
        arguments.add(size);
        arguments.add(offset(page, size));
        String sql = catalog
            + "select p.supplier_id as id,p.supplier_code as \"supplierCode\",p.supplier_name as \"supplierName\","
            + "p.short_code as \"shortCode\",p.status,c.style_count as \"styleCount\" "
            + scopedSuppliers + " order by p.supplier_name,p.supplier_code limit ? offset ?";
        return pageResult(store.jdbc.queryForList(sql, arguments.toArray()), total, page, size);
    }

    /** SQ is the existing supplier-owned candidate source; legacy SL sources are only explicitly classified. */
    @Transactional(readOnly = true)
    public Map<String,Object> sources(Map<String,String> query) {
        view();
        String supplier = required(query.get("supplierCode"), "请先选择供应商");
        profiles.requireSupplier(supplier);
        boolean historical = "historical".equals(text(query.get("purpose")));
        if (historical) permissions.engineer();
        String purpose = historical ? "r.kind='selection' and coalesce(r.data->>'catalogSource','false')<>'true'"
            : "(r.kind='supplier_quote' or (r.kind='selection' and r.data->>'catalogSource'='true'))";
        String supplierSql="case when r.kind='supplier_quote' then r.supplier_code else "
            +"(select min(s.data->>'supplierCode') from plm_partner_selection_row s where s.record_id=r.record_id "
            +"having count(distinct nullif(s.data->>'supplierCode',''))=1 and count(*) filter(where coalesce(s.data->>'supplierCode','')='')=0 "
            +"and (coalesce(r.supplier_code,'')='' or r.supplier_code=min(s.data->>'supplierCode'))) end";
        String filter = purpose + " and r.deleted_flag=0 and ("+supplierSql+")=? and ("
            + store.access.visibleSqlClause("partner", "r.record_id") + ")";
        var args = new ArrayList<Object>(List.of(supplier));
        String keyword = text(query.get("keyword"));
        if (!keyword.isEmpty()) { filter += " and (r.name ilike ? or r.code ilike ?)"; args.add("%"+keyword+"%"); args.add("%"+keyword+"%"); }
        long page = page(query), size = size(query);
        long total = store.jdbc.queryForObject("select count(*) from plm_partner_record r where " + filter, Long.class, args.toArray());
        args.add(size); args.add(offset(page,size));
        var content = store.jdbc.queryForList("select r.record_id as id,r.code,r.name,r.kind,r.status,r.version,("+supplierSql+") as \"supplierCode\",r.data::text,"
            + "case when r.kind='selection' then (select count(*) from plm_partner_selection_row x where x.record_id=r.record_id) "
            + "else (select count(*) from plm_partner_quote_line x where x.record_id=r.record_id) end as \"rowCount\" "
            + "from plm_partner_record r where " + filter + " order by r.updated_at desc,r.record_id desc limit ? offset ?", args.toArray());
        return pageResult(content.stream().map(this::sourceHeader).toList(),total,page,size);
    }

    @Transactional
    public Map<String,Object> createSource(Map<String,Object> input) {
        permissions.engineer();
        var command = new LinkedHashMap<String,Object>();
        for (String field : List.of("name","supplierCode","requestId")) command.put(field,input.get(field));
        var created = operations.create("supplier_quote",command);
        require(text(created.get("supplierCode")).equals(text(input.get("supplierCode")))&&text(created.get("name")).equals(text(input.get("name"))),"提交标识已用于另一份供应商来源资料，请核对后重试");
        return sourceHeader(created);
    }

    @Transactional(readOnly=true)
    public Map<String,Object> sourceDetail(long id) {
        var header=readableSource(id);var result=sourceHeader(header);String kind=text(header.get("kind"));
        String table=kind.equals("selection")?"plm_partner_selection_row":"plm_partner_quote_line";
        result.put("rowCount",store.jdbc.queryForObject("select count(*) from "+table+" where record_id=?",Long.class,id));
        result.put("files",List.of());
        if(permissions.costs()){
            store.access.requirePermission("file:read");
            result.put("files",store.jdbc.queryForList("select a.attachment_id as id,a.file_name as name,a.version_no as version,a.content_type as type,a.file_size as size "
                +"from plm_partner_file f join plm_attachment a on a.attachment_id=f.attachment_id where f.record_id=? and f.purpose<>'financial' "
                +"and a.deleted_flag=0 and (f.purpose='cooperation_cost' or coalesce(a.content_type,'') not like 'image/%') and ("
                +store.access.visibleSqlClause("file","a.attachment_id")+") order by a.attachment_id desc",id));
        }return result;
    }

    @Transactional(readOnly=true)
    public Map<String,Object> sourceFiles(long id,Map<String,String> query) {
        var header=readableSource(id);
        require(required(query.get("supplierCode"),"供应商").equals(text(header.get("supplierCode"))),"来源资料不属于当前供应商");
        store.access.requirePermission("file:read");
        long page=page(query),size=size(query);
        String where="f.record_id=? and a.deleted_flag=0 and "+store.access.visibleSqlClause("file","a.attachment_id")
            +(permissions.costs()?"":" and not ("+RESTRICTED_FILE+")");
        String from=" from plm_partner_file f join plm_attachment a on a.attachment_id=f.attachment_id where "+where;
        long total=store.jdbc.queryForObject("select count(*)"+from,Long.class,id);
        var files=store.jdbc.queryForList("select a.attachment_id as id,a.file_name as name,a.version_no as version,a.content_type as type,a.file_size as size"
            +from+" order by a.attachment_id limit ? offset ?",id,size,offset(page,size));
        return pageResult(files,total,page,size);
    }

    private Map<String,Object> readableSource(long id) {
        view();var kinds=store.jdbc.queryForList("select kind from plm_partner_record r where r.record_id=? and r.deleted_flag=0 and ("+store.access.visibleSqlClause("partner","r.record_id")+")",id);
        require(kinds.size()==1,"供应商来源不存在或未获授权");String kind=text(kinds.get(0).get("kind"));
        require(Set.of("selection","supplier_quote").contains(kind),"这不是供应商目录资料");
        var header=sourceSupplier(store.load(id,kind,false));profiles.requireSupplier(required(header.get("supplierCode"),"供应商"));return header;
    }

    private Map<String,Object> sourceHeader(Map<String,Object> value) {
        Map<String,Object> header = new LinkedHashMap<>(value);
        if (header.containsKey("data")) {var metadata=decode(store.json,header.remove("data"));metadata.putAll(header);header=metadata;}
        boolean quote = "supplier_quote".equals(text(header.get("kind")));
        var result = new LinkedHashMap<String,Object>();
        for (String field : List.of("id","code","name","kind","status","version","supplierCode","rowCount")) result.put(field,header.getOrDefault(field,field.equals("rowCount")?0L:""));
        result.put("sourceKind",quote?"quote":"selection");
        result.put("purpose",quote||PartnerSelectionService.isCatalogSource(header)?"catalog_source":"my_selection");
        result.put("readOnly",!quote||!"draft".equals(text(header.get("status"))));
        result.put("canImport",quote&&"draft".equals(text(header.get("status")))&&permissions.engineering());
        result.put("canWrite",permissions.engineering()); result.put("canReadCost",permissions.costs());
        return result;
    }

    /** Historical imports explicitly store supplier on rows; deriving the single identity never rewrites the header. */
    private Map<String,Object> sourceSupplier(Map<String,Object> input) {
        return PartnerSelectionService.sourceSupplierHeader(store,input);
    }

    /** Explicit historical/cross-version binding, never a GET side effect or source JSON rewrite. */
    @Transactional(readOnly=true)
    public Map<String,Object> styleIdentities(Map<String,String> query){
        view();String supplier=required(query.get("supplierCode"),"供应商");var profile=profiles.requireSupplier(supplier);
        String from="""
            from plm_partner_catalog_style s join lateral (
              select coalesce(q.data->'catalogCurrent'->>'name',a.data->'catalogCurrent'->>'name',q.data->>'name',a.data->>'name','') as name
              from plm_partner_catalog_style_source x join plm_partner_record r on r.record_id=x.record_id
              left join plm_partner_quote_line q on x.source_kind='quote' and q.record_id=x.record_id and q.line_id=x.row_id
              left join plm_partner_selection_row a on x.source_kind='selection' and a.record_id=x.record_id and a.row_id=x.row_id
              where x.style_id=s.style_id and r.deleted_flag=0 and (%s)
              order by case when exists(select 1 from (%s) current_source where current_source.source_kind=x.source_kind
                  and current_source.record_id=x.record_id and current_source.row_id=x.row_id::text and current_source.supplier_code=s.supplier_code) then 0 else 1 end,
                coalesce(q.updated_at,a.updated_at,r.updated_at,x.created_at) desc,x.created_at desc,x.record_id desc,x.row_id desc limit 1
            ) source on true where s.supplier_code=?
            """.formatted(store.access.visibleSqlClause("partner","r.record_id"),new PartnerCatalogStyles(store).sourceSql(false));
        var args=new ArrayList<Object>(List.of(supplier));String keyword=text(query.get("keyword"));if(!keyword.isEmpty()){from+=" and (s.code ilike ? or source.name ilike ?)";args.add("%"+keyword+"%");args.add("%"+keyword+"%");}long page=page(query),size=size(query),total=store.jdbc.queryForObject("select count(*)"+from,Long.class,args.toArray());args.add(size);args.add(offset(page,size));
        var rows=store.jdbc.queryForList("select s.style_id as id,s.code,source.name "+from+" order by s.style_id desc limit ? offset ?",args.toArray());for(var row:rows){row.put("supplierCode",supplier);row.put("supplierName",profile.get("supplierName"));String shortCode=text(profile.get("shortCode"));row.put("label",shortCode.isEmpty()?row.get("code"):shortCode+"-"+row.get("code"));}return pageResult(rows,total,page,size);
    }
    @Transactional(readOnly=true)
    public Map<String,Object> identityPreview(Map<String,Object> input){permissions.engineer();return identityPlan(input,false);}
    private Map<String,Object> identityPlan(Map<String,Object> input,boolean lock){
        String supplier=required(input.get("supplierCode"),"供应商");profiles.requireSupplier(supplier);var result=new ArrayList<Map<String,Object>>();
        for(var item:additionItems(input)){long record=number(item.get("recordId"));String kind=text(item.get("sourceKind"));if(lock)store.load(record,kind.equals("quote")?"supplier_quote":"selection",true);
            var row=source(kind,record,text(item.get("rowId")),supplier);require(number(row.get("rowVersion"))==number(item.get("rowVersion"))&&number(row.get("recordVersion"))==number(item.get("recordVersion")),"目录资料已变化，请刷新预览");
            long existing=number(item.get("catalogStyleId")),current=number(row.get("catalogStyleId"));require(current==0||existing==0||existing==current,"既有目录款式身份不能改绑");
            if(existing>0){var found=store.jdbc.queryForList("select s.code from plm_partner_catalog_style s where s.style_id=? and s.supplier_code=? and exists(select 1 from plm_partner_catalog_style_source x join plm_partner_record r on r.record_id=x.record_id where x.style_id=s.style_id and r.deleted_flag=0 and ("+store.access.visibleSqlClause("partner","r.record_id")+"))",existing,supplier);require(found.size()==1,"目录款式不存在、跨供应商或无权访问");}
            var value=new LinkedHashMap<String,Object>();for(String key:List.of("sourceKind","recordId","rowId","rowVersion","recordVersion","name","catalogStyleId","catalogStyleCode","catalogStyleLabel"))if(row.containsKey(key))value.put(key,row.get(key));
            value.put("catalogStyleId",current>0?current:existing);if(current==0&&existing>0){String code=store.jdbc.queryForObject("select code from plm_partner_catalog_style where style_id=?",String.class,existing);value.put("catalogStyleCode",code);String shortCode=text(profiles.requireSupplier(supplier).get("shortCode"));value.put("catalogStyleLabel",shortCode.isEmpty()?code:shortCode+"-"+code);}
            var before=new LinkedHashMap<String,Object>();for(String field:List.of("currency","moq","unitPrice","priceUnit","moqQuantity","moqUnit","moqScope","moqNote"))before.put(field,row.getOrDefault(field,""));value.put("beforeFields",before);value.put("messages",List.of());value.put("action",current>0?"unchanged":existing>0?"link":"new");value.put("normalizedFields",PartnerCatalogFields.normalize(row,Map.of(),true));result.add(value);
        }
        var out=new LinkedHashMap<String,Object>();out.put("supplierCode",supplier);out.put("items",result);out.put("canConfirm",true);out.put("messages",List.of());out.put("previewToken",digest(out));return out;
    }
    @Transactional public Map<String,Object> identify(Map<String,Object> input){
        permissions.engineer();String request=required(input.get("requestId"),"提交标识"),reason=required(input.get("reason"),"款式关联原因");require(request.length()<=80,"提交标识过长");
        profiles.requireSupplier(required(input.get("supplierCode"),"供应商"));
        var requested=additionItems(input);String fingerprint=digest(input),eventKey="cp-batch-"+key(request);Long replay=store.created("catalog_identity",request);
        if(replay!=null){store.load(replay,"quote".equals(text(requested.get(0).get("sourceKind")))?"supplier_quote":"selection",false);var events=store.jdbc.queryForList("select data::text from plm_partner_event where record_id=? and action='catalog_identity_batch' and request_id=?",replay,eventKey);require(events.size()==1,"款式编号提交记录不完整，请联系管理员核对");var saved=decode(store.json,events.get(0).get("data"));require(fingerprint.equals(text(saved.get("fingerprint"))),"提交标识已用于不同款式编号操作");return map(saved.get("result"));}
        var plan=identityPlan(input,true);require(text(plan.get("previewToken")).equals(required(input.get("previewToken"),"请先预览款式编号")),"款式或来源已变化，请重新预览");var result=new ArrayList<Map<String,Object>>();
        for(var item:maps(plan.get("items"))){var row=source(text(item.get("sourceKind")),number(item.get("recordId")),text(item.get("rowId")),text(plan.get("supplierCode")));result.add(new PartnerCatalogStyles(store).ensure(text(item.get("sourceKind")),number(item.get("recordId")),row,number(item.get("catalogStyleId")),reason,request));}
        var response=Map.<String,Object>of("items",result,"sourceRowsUnchanged",true);long record=number(maps(plan.get("items")).get(0).get("recordId"));store.remember("catalog_identity",request,record);store.event(record,"catalog_identity_batch",Map.of("fingerprint",fingerprint,"result",response,"reason",reason),eventKey);return response;
    }

    @Transactional(readOnly = true)
    public Map<String,Object> purposePreview(long id,Map<String,Object> input) {
        permissions.engineer();
        return purposePlan(id,input,false);
    }

    private Map<String,Object> purposePlan(long id,Map<String,Object> input,boolean lock) {
        require("catalog_source".equals(text(input.get("purpose"))),"仅可明确标记为供应商候选来源");
        required(input.get("reason"),"用途调整原因");
        var header = store.load(id,"selection",lock); store.check(header,number(input.get("version")));
        var stats = store.jdbc.queryForMap("select count(*) as \"rowCount\",count(*) filter(where confirmed or coalesce(data->>'selected','false')='true' "
            + "or coalesce(data->>'productId','0') not in ('','0') or coalesce(data->>'specId','0') not in ('','0') "
            + "or jsonb_exists(data,'catalogSourceSnapshot') or case when jsonb_typeof(data->'specIds')='array' then jsonb_array_length(data->'specIds')>0 else false end) as \"adoptedCount\" from plm_partner_selection_row where record_id=?",id);
        var messages = new ArrayList<String>();
        if (PartnerSelectionService.isCatalogSource(header)) messages.add("该资料已是供应商候选来源");
        if (number(stats.get("adoptedCount"))>0) messages.add("已有明确选中、冻结或产品关联，不能把我方选品直接改成供应商来源");
        if(Boolean.TRUE.equals(store.jdbc.queryForObject("select exists(select 1 from plm_partner_event where record_id=? and action='catalog_add' and jsonb_typeof(data->'createdRowIds')='array' and jsonb_array_length(data->'createdRowIds')>0)",Boolean.class,id)))messages.add("这份清单已有目录转入选品的来源事实，不能再反向标为供应商资料");
        boolean samples = Boolean.TRUE.equals(store.jdbc.queryForObject("select exists(select 1 from plm_partner_record where kind='sample' and deleted_flag=0 and (data->>'selectionId'=? or data->>'selectionRowId' in (select row_id::text from plm_partner_selection_row where record_id=?)))",Boolean.class,Long.toString(id),id));
        if (samples) messages.add("已有真实样品关联，不能调整来源用途");
        boolean active = Boolean.TRUE.equals(store.jdbc.queryForObject("select exists(select 1 from plm_partner_file_job where record_id=? and (status in ('queued','processing') or (kind='import' and status in ('awaiting_sheets','ready')))) "
            +"or exists(select 1 from plm_partner_upload_session where record_id=? and status='uploading')",Boolean.class,id,id));
        if(active)messages.add("仍有上传或文件任务尚未完成，请先完成或核对现有导入后再调整用途");
        var supplierHeader=sourceSupplier(header);profiles.requireSupplier(required(supplierHeader.get("supplierCode"),"供应商"));
        var result = new LinkedHashMap<>(sourceHeader(supplierHeader));
        result.put("rowCount",number(stats.get("rowCount"))); result.put("targetPurpose","catalog_source");
        result.put("reason",text(input.get("reason"))); result.put("canChange",messages.isEmpty()); result.put("messages",messages);
        result.put("previewToken",digest(result)); return result;
    }

    @Transactional
    public Map<String,Object> changePurpose(long id,Map<String,Object> input) {
        permissions.engineer();
        String request = required(input.get("requestId"),"提交标识"); require(request.length()<=80,"提交标识过长");
        store.jdbc.queryForList("select pg_advisory_xact_lock(hashtextextended(?,0))",store.access.actorId()+":catalog-purpose:"+id+":"+request);
        var replay = store.jdbc.queryForList("select data::text from plm_partner_event where record_id=? and action='catalog_source_purpose' and request_id=?",id,request);
        if (!replay.isEmpty()) {
            store.load(id,"selection",false); var saved = decode(store.json,replay.get(0).get("data"));
            require(signature(store.json,map(saved.get("input"))).equals(signature(store.json,input)),"提交标识已用于另一用途调整");
            return map(saved.get("result"));
        }
        var preview = purposePlan(id,input,true);
        require(Boolean.TRUE.equals(preview.get("canChange")),String.join("；",messages(preview.get("messages"))));
        require(text(preview.get("previewToken")).equals(required(input.get("previewToken"),"请先预览用途变化")),"用途或关联资料已变化，请重新预览");
        var header = store.load(id,"selection",true); header.put("catalogSource",true);header.put("purpose","catalog_source");
        store.revision(id,header); store.save(id,header);
        var result = sourceHeader(sourceSupplier(store.load(id,"selection",false))); result.put("rowCount",preview.get("rowCount"));
        store.event(id,"catalog_source_purpose",Map.of("beforePurpose","my_selection","afterPurpose","catalog_source","reason",input.get("reason"),"input",input,"result",result),request);
        return result;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> styles(Map<String, String> query) {
        view();
        String supplier = required(query.get("supplierCode"), "请先选择供应商");
        Map<String, Object> profile = profiles.requireSupplier(supplier);
        long page = page(query), size = size(query);
        var clauses=new ArrayList<String>(List.of("c.supplier_code=?"));var arguments=new ArrayList<Object>(List.of(supplier));
        if(!text(query.get("sourceRecordId")).isEmpty()){long source=number(query.get("sourceRecordId"));String kind=text(query.get("sourceKind"));require(source>0&&Set.of("selection","quote").contains(kind),"来源资料筛选无效");clauses.add("c.record_id=? and c.source_kind=?");arguments.add(source);arguments.add(kind);}
        var filter = new Filter(String.join(" and ",clauses),arguments,sourceSql(query));
        String keyword = text(query.getOrDefault("keyword", query.get("q"))).toLowerCase(Locale.ROOT);
        // A previous readable label must still resolve after the supplier short code changes.
        // Load only the authorized supplier's aliases once, not one query per catalogue row.
        var shortCodeHistory = keyword.contains("-cp-") ? profiles.shortCodeHistory(supplier) : List.<String>of();
        List<Map<String, Object>> result;
        long total;
        if (text(query.get("phoneModelId")).isEmpty() && keyword.isEmpty()) {
            total = store.jdbc.queryForObject("select count(*) from (" + filter.sourceSql + ") c where " + filter.sql,
                Long.class, filter.arguments.toArray());
            result = phones.resolveAll(fetch(filter, size, offset(page, size)));
        } else {
            long phone = number(query.get("phoneModelId"));
            require(text(query.get("phoneModelId")).isEmpty() || phone > 0, "手机型号标识无效");
            // Legacy textual ranges must participate too; scan bounded pages with one master snapshot.
            var scope = phones.newScope();
            result = new ArrayList<>();
            total = 0;
            long scanOffset = 0, wantedOffset = offset(page, size);
            while (true) {
                var batch = fetch(filter, 100, scanOffset);
                if (batch.isEmpty()) break;
                for (var row : scope.resolveAll(batch)) {
                    if (phone > 0 && !PartnerSelectionService.scopeIds(row.get("phoneModelIds")).contains(phone)) continue;
                    if (!matches(row, keyword, shortCodeHistory)) continue;
                    if (total >= wantedOffset && result.size() < size) result.add(row);
                    total++;
                }
                scanOffset += batch.size();
            }
        }
        var out = pageResult(projectBatch(result, profile, false), total, page, size);
        out.put("supplier", profile);
        return out;
    }

    private record Filter(String sql, List<Object> arguments,String sourceSql) {}

    private boolean matches(Map<String, Object> row, String keyword, List<String> shortCodeHistory) {
        if (keyword.isEmpty()) return true;
        for (String field : List.of("name", "supplierItemCode", "modelText", "color", "series", "catalogStyleCode", "catalogStyleLabel"))
            if (text(row.get(field)).toLowerCase(Locale.ROOT).contains(keyword)) return true;
        String code = text(row.get("catalogStyleCode"));
        if (!code.isEmpty()) for (String alias : shortCodeHistory)
            if ((text(alias) + "-" + code).toLowerCase(Locale.ROOT).contains(keyword)) return true;
        for (var phone : maps(row.get("phones")))
            for (String field : List.of("phoneModelCode", "code", "brandName", "modelName", "displayName"))
                if (text(phone.get(field)).toLowerCase(Locale.ROOT).contains(keyword)) return true;
        return false;
    }

    private List<Map<String, Object>> fetch(Filter filter, long size, long offset) {
        var args = new ArrayList<>(filter.arguments);
        args.add(size);
        args.add(offset);
        return store.jdbc.queryForList("select c.*,c.data::text as row_data from (" + filter.sourceSql + ") c where "
            + filter.sql + " order by c.updated_at desc,c.record_id desc,c.sort_order,c.row_id limit ? offset ?", args.toArray())
            .stream().map(this::decodeSource).toList();
    }

    private Map<String, Object> decodeSource(Map<String, Object> source) {
        var row = PartnerCatalogStyles.currentData(decode(store.json, source.get("row_data")));
        // Classify the untouched declaration before resolution may drop malformed IDs.
        // Never trust a source/client-supplied mode, or infer absence from a failed match.
        row.put("_catalogPhoneScopeMode",PartnerCatalogPhoneResolver.supplierScopeMode(row));
        row.put("sourceKind", source.get("source_kind"));
        row.put("recordId", source.get("record_id"));
        row.put("sourceCode", source.get("source_code"));
        row.put("sourceName", source.get("source_name"));
        row.put("recordVersion", source.get("record_version"));
        row.put("sourceStatus", source.get("source_status"));
        row.put("rowId", source.get("row_id"));
        row.put("rowVersion", source.get("row_version"));
        row.put("supplierCode", source.get("supplier_code"));
        row.put("sourcePurpose", source.getOrDefault("source_purpose", "quote".equals(text(source.get("source_kind")))?"catalog_source":"my_selection"));
        row.put("confirmed", source.get("confirmed"));
        return new PartnerCatalogStyles(store).enrich(text(row.get("sourceKind")),number(row.get("recordId")),row);
    }

    private Map<String, Object> source(String kind, long id, String rowId, String supplier) {
        return source(kind,id,rowId,supplier,false);
    }
    private Map<String,Object> source(String kind,long id,String rowId,String supplier,boolean includeDiscarded){
        require(Set.of("quote", "selection").contains(kind), "目录来源类型无效");
        require(id > 0 && PartnerSelectionService.validRowId(rowId), "目录行标识无效");
        var header = store.load(id, kind.equals("quote") ? "supplier_quote" : "selection", false);
        if(kind.equals("selection"))require(!"my_selection".equals(text(header.get("purpose")))||PartnerSelectionService.isCatalogSource(header),"我方选品不是供应商候选来源，请从供应商目录选择");
        String table = kind.equals("quote") ? "plm_partner_quote_line" : "plm_partner_selection_row";
        String key = kind.equals("quote") ? "line_id" : "row_id";
        var found = store.jdbc.queryForList("select " + key + "::text as row_id,row_version,data::text as row_data,"
            + (kind.equals("quote") ? "false" : "confirmed") + " as confirmed from " + table
            + " where record_id=? and " + key + "=?::uuid", id, rowId);
        require(found.size() == 1, "目录款式不存在或不属于当前资料");
        if (kind.equals("selection")) require(!Boolean.TRUE.equals(store.jdbc.queryForObject(
            "select exists(select 1 from plm_partner_event where record_id=? and action='catalog_add' and (data->'createdRowIds') @> jsonb_build_array(?::text))",
            Boolean.class, id, rowId)), "该行是已转入选品的副本，请从供应商目录选择原款式");
        var db = found.get(0);
        var data = PartnerCatalogStyles.currentData(decode(store.json, db.get("row_data")));
        String actual = text(data.get("supplierCode"));
        if (kind.equals("quote")) {
            String quoteSupplier = text(header.get("supplierCode"));
            require(actual.isEmpty() || actual.equals(quoteSupplier), "报价行供应商与原报价不符");
            actual = quoteSupplier;
        }
        require(!supplier.isEmpty() && supplier.equals(actual), "目录款式不属于当前供应商");
        require(includeDiscarded||!Boolean.TRUE.equals(data.get("discarded")), "该款已排除，不在可选目录中");
        db.put("source_kind", kind);
        db.put("record_id", id);
        db.put("source_code", header.get("code"));
        db.put("source_name", header.get("name"));
        db.put("record_version", header.get("version"));
        db.put("source_status", header.get("status"));
        db.put("supplier_code", actual);
        db.put("source_purpose",kind.equals("quote")||PartnerSelectionService.isCatalogSource(header)?"catalog_source":"my_selection");
        return decodeSource(db);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> detail(String kind, long id, String rowId, String supplier) {
        return detail(kind,id,rowId,supplier,false);
    }
    @Transactional(readOnly=true)
    public Map<String,Object> detail(String kind,long id,String rowId,String supplier,boolean includeDiscarded){
        view();
        var profile = profiles.requireSupplier(required(supplier, "请先选择供应商"));
        return projectBatch(phones.resolveAll(List.of(source(kind, id, rowId, supplier,includeDiscarded))), profile, true).get(0);
    }

    private record SourceRowState(Map<String,Object> header, Map<String,Object> row, Map<String,Object> data) {}

    @Transactional
    public Map<String,Object> updateStyle(String kind,long id,String rowId,String supplier,Map<String,Object> input){
        permissions.engineer();
        require(Set.of("name","changes","rowVersion","recordVersion","requestId").containsAll(input.keySet())&&!(input.containsKey("name")&&input.containsKey("changes")),"请只提交本次修改字段及原版本");
        var changes=input.containsKey("changes")?map(input.get("changes")):Map.<String,Object>of("name",required(input.get("name"),"款式名称"));
        require(!changes.isEmpty()&&EDITABLE_FIELDS.containsAll(changes.keySet()),"目录修改包含不可编辑的系统字段");
        String request=required(input.get("requestId"),"提交标识");require(request.length()<=80,"提交标识过长");
        require(Set.of("quote","selection").contains(kind),"目录来源类型无效");
        profiles.requireSupplier(required(supplier,"供应商"));
        var header=store.load(id,kind.equals("quote")?"supplier_quote":"selection",true);
        var row=source(kind,id,rowId,supplier,true);
        require(input.containsKey("rowVersion")&&input.containsKey("recordVersion")
            &&number(input.get("rowVersion"))==number(row.get("rowVersion"))
            &&number(input.get("recordVersion"))==number(row.get("recordVersion")),"款式资料已变化，请刷新后再保存");
        var current=new SourceRowState(header,row,sourceData(kind,id,rowId,true));
        var effective=PartnerCatalogStyles.currentData(current.data);
        var mutable=new LinkedHashMap<>(effective);mutable.put("confirmed",false);mutable.put("rowId",rowId);
        var command=new LinkedHashMap<>(changes);command.put("rowId",rowId);
        var canonical=selections.canonicalRow(id,mutable,command);
        var after=new LinkedHashMap<>(current.data);var metadata=new LinkedHashMap<>(map(current.data.get("catalogCurrent")));
        var changedFields=new LinkedHashSet<>(changes.keySet());changedFields.remove("newSupplierColors");
        if(changes.containsKey("color")||changes.containsKey("supplierColorIds")||changes.containsKey("newSupplierColors"))changedFields.addAll(List.of("supplierColorIds","supplierColors","color"));
        for(String field:changedFields)if(!signature(store.json,effective.get(field)).equals(signature(store.json,canonical.get(field))))metadata.put(field,canonical.get(field));
        if(changes.containsKey("currency")&&metadata.containsKey("currency"))metadata.put("currencySource",canonical.get("currencySource"));
        if(!metadata.isEmpty())after.put("catalogCurrent",metadata);
        var next=PartnerCatalogStyles.currentData(after);require(!text(next.get("name")).isEmpty(),"款式名称不能为空");
        boolean changed=!signature(store.json,current.data).equals(signature(store.json,after));
        if(changed){
            saveSourceData(current,after,request);
            selections.refreshCatalogDrafts(kind,id,rowId,next,changedFields,request);
            store.event(id,"catalog_style_update",Map.of("sourceKind",kind,"rowId",rowId,"before",effective,"after",next,"fields",changedFields,"reason","维护目录当前资料；确认和实收历史保留"),request);
        }
        return Map.of("name",next.get("name"),"rowVersion",number(row.get("rowVersion"))+(changed?1:0),"recordVersion",number(row.get("recordVersion"))+(changed?1:0),"changed",changed);
    }

    private Map<String,Object> sourceData(String kind,long id,String rowId,boolean lock){
        String table=kind.equals("quote")?"plm_partner_quote_line":"plm_partner_selection_row",column=kind.equals("quote")?"line_id":"row_id";
        var stored=store.jdbc.queryForList("select data::text from "+table+" where record_id=? and "+column+"=?::uuid"+(lock?" for update":""),id,rowId);
        require(stored.size()==1,"目录款式不存在");return decode(store.json,stored.get(0).get("data"));
    }

    private void saveSourceData(SourceRowState current,Map<String,Object> after,String request){
        var metadata=new LinkedHashMap<>(map(after.get("catalogCurrent")));
        metadata.keySet().removeIf(field->!signature(store.json,current.data.get(field)).equals(signature(store.json,after.get(field))));
        if(metadata.isEmpty())after.remove("catalogCurrent");else after.put("catalogCurrent",metadata);
        String kind=text(current.row.get("sourceKind")),rowId=text(current.row.get("rowId"));long id=number(current.row.get("recordId")),next=number(current.row.get("rowVersion"))+1;
        String table=kind.equals("quote")?"plm_partner_quote_line":"plm_partner_selection_row",column=kind.equals("quote")?"line_id":"row_id";
        require(store.jdbc.update("update "+table+" set data=?::jsonb,row_version=?,updated_at=now(),updated_by=? where record_id=? and "+column+"=?::uuid and row_version=?",
            encode(store.json,after),next,store.access.actor().username(),id,rowId,current.row.get("rowVersion"))==1,"款式资料已变化，请刷新后再保存");
        String revision=kind.equals("quote")?"plm_partner_quote_line_revision":"plm_partner_selection_row_revision";
        store.jdbc.update("insert into "+revision+"(record_id,"+column+",row_version,request_id,data,created_by) values(?,?::uuid,?,?,?::jsonb,?)",
            id,rowId,next,request,encode(store.json,Map.of("before",current.data,"after",after)),store.access.actor().username());
        store.revision(id,current.header);
        store.jdbc.update("update plm_partner_record set version=version+1,updated_at=now(),updated_by=? where record_id=?",store.access.actor().username(),id);
    }

    /** This narrowly scoped maintenance path does not reopen legacy whole-sheet imports. */
    private SourceRowState modelSheetSource(Map<String,Object> input, boolean lock, boolean checkVersion) {
        permissions.engineer();
        require(MODEL_SHEET_COMMAND_FIELDS.containsAll(input.keySet()), "型号表操作只接收当前款式身份、型号条目与原图，不接受其他业务字段");
        String supplier = required(input.get("supplierCode"), "供应商"), kind = text(input.get("sourceKind"));
        long id = number(input.get("recordId")); String rowId = text(input.get("rowId"));
        profiles.requireSupplier(supplier);
        require(Set.of("quote", "selection").contains(kind) && id > 0 && PartnerSelectionService.validRowId(rowId), "目录来源身份无效");
        var header = store.load(id, kind.equals("quote") ? "supplier_quote" : "selection", lock);
        require(kind.equals("quote") ? "draft".equals(text(header.get("status"))) : PartnerSelectionService.isCatalogSource(header),
            "仅供应商目录来源可补型号依据；我方选品或已确认报价不能改写");
        var row = source(kind, id, rowId, supplier);
        require(!PartnerSelectionService.confirmed(row) && map(row.get("catalogSourceSnapshot")).isEmpty(), "已确认或派生选品的来源依据已冻结");
        if (checkVersion) require(input.containsKey("rowVersion") && input.containsKey("recordVersion")
            && number(input.get("rowVersion")) == number(row.get("rowVersion"))
            && number(input.get("recordVersion")) == number(row.get("recordVersion")), "目录或型号资料已变化，请刷新后重新预览");
        return new SourceRowState(header,row,sourceData(kind,id,rowId,lock));
    }

    @Transactional(readOnly=true)
    public Map<String,Object> modelSheetPreview(Map<String,Object> input) {
        return modelSheetPlan(input, modelSheetSource(input, false, true), false);
    }

    private Map<String,Object> modelSheetPlan(Map<String,Object> input, SourceRowState current, boolean lock) {
        var data=PartnerCatalogStyles.currentData(current.data);
        var entries = PartnerCatalogModelSheet.resolve(input.get("entries"), phones.newScope(), maps(data.get("supplierModelEntries")));
        var originalEntries = new HashSet<List<String>>();
        for (var entry : entries) originalEntries.add(PartnerCatalogModelSheet.entryIdentity(entry));
        for (var previous : maps(data.get("supplierModelEntries"))) require(originalEntries.contains(PartnerCatalogModelSheet.entryIdentity(previous)),
            "本次缺少或改写已保存的供方原型号条目；请保留全部原记录，不能将漏行当作删除");
        long id = number(current.row.get("recordId"));
        require(input.get("modelSheetRefs") instanceof List<?>, "请明确本款型号表原图引用");
        var refs = new LinkedHashMap<Long,Map<String,Object>>();
        for (var ref : store.canonicalFiles(id, data.get("modelSheetRefs"), lock)) refs.put(number(ref.get("id")), ref);
        for (var ref : store.canonicalFiles(id, input.get("modelSheetRefs"), lock)) refs.put(number(ref.get("id")), ref);
        require(!refs.isEmpty(), "请关联本款型号表原图，不能只保存无原件的转录");
        for (long file : refs.keySet()) require(Boolean.TRUE.equals(store.jdbc.queryForObject("select exists(select 1 from plm_partner_file f join plm_attachment a on a.attachment_id=f.attachment_id where f.record_id=? and f.attachment_id=? and f.purpose='photo' and a.deleted_flag=0 and a.content_type like 'image/%')", Boolean.class, id, file)), "型号依据须为当前来源已授权的型号表原图，不能引用财务或整本报价资料");
        var previousSheetIds = new LinkedHashSet<Long>();
        for (var entry : maps(data.get("supplierModelEntries"))) previousSheetIds.addAll(PartnerSelectionService.scopeIds(entry.get("phoneModelIds")));
        var independentIds = new LinkedHashSet<Long>(PartnerSelectionService.scopeIds(data.get("phoneModelIds")));
        independentIds.removeAll(previousSheetIds);
        // A pre-existing explicit ID can overlap the first table. The original audit,
        // rather than a new ledger or a numeric-ID guess, preserves that provenance.
        if (!maps(data.get("supplierModelEntries")).isEmpty()) {
            var first = store.jdbc.queryForList("select (e.data->'before')::text as original_data from plm_partner_event e join plm_partner_record r on r.record_id=e.record_id where e.record_id=? and e.action='catalog_model_sheet' and e.data->>'sourceKind'=? and e.data->>'rowId'=? and r.deleted_flag=0 and (" + store.access.visibleSqlClause("partner", "r.record_id") + ") order by e.event_id limit 1",
                id, text(current.row.get("sourceKind")), text(current.row.get("rowId")));
            require(first.size() == 1, "已有型号表缺少最初关联审计，不能猜测删除原有范围；请核对来源后再维护");
            independentIds.addAll(PartnerSelectionService.scopeIds(decode(store.json, first.get(0).get("original_data")).get("phoneModelIds")));
        }
        var ids = new LinkedHashSet<Long>(independentIds);
        for (var entry : entries) ids.addAll(PartnerSelectionService.scopeIds(entry.get("phoneModelIds")));
        var after = new LinkedHashMap<String,Object>();
        after.put("supplierModelEntries", entries); after.put("modelSheetRefs", new ArrayList<>(refs.values()));
        // Preserve independent old scope, but replace the previous model-table projection so corrected IDs cannot linger in the picker.
        after.put("phoneModelIds", new ArrayList<>(ids));
        var before = new LinkedHashMap<String,Object>();
        for (String field : List.of("supplierModelEntries", "modelSheetRefs", "phoneModelIds")) before.put(field, current.data.getOrDefault(field, List.of()));
        var out = new LinkedHashMap<String,Object>();
        for (String field : List.of("sourceKind", "recordId", "rowId", "rowVersion", "recordVersion", "supplierCode", "name")) out.put(field, current.row.get(field));
        out.put("entries", entries); out.put("summary", PartnerCatalogModelSheet.summary(entries));
        out.put("before", before); out.put("after", after); out.put("canSave", true);
        out.put("preservedIndependentPhoneModelIds", new ArrayList<>(independentIds));
        out.put("removedPhoneModelIds", PartnerSelectionService.scopeIds(data.get("phoneModelIds")).stream().filter(phone -> !ids.contains(phone)).toList());
        out.put("messages", List.of("仅更新当前款式型号依据；原报价、图片、其他款式和既有选品快照不变，未匹配原文仍保留。"));
        out.put("previewToken", digest(out));
        return out;
    }

    @Transactional
    public Map<String,Object> saveModelSheet(Map<String,Object> input) {
        permissions.engineer();
        String request = required(input.get("requestId"), "提交标识"), fingerprint = digest(input);
        require(request.length() <= 80, "提交标识过长");
        Long replay = store.created("catalog_model_sheet", request);
        var current = modelSheetSource(input, true, replay == null);
        long id = number(current.row.get("recordId"));
        if (replay != null) {
            require(replay == id, "提交标识已用于另一份来源");
            var events = store.jdbc.queryForList("select data::text from plm_partner_event where record_id=? and action='catalog_model_sheet' and request_id=?", id, request);
            require(events.size() == 1, "型号表提交记录不完整，请核对原提交结果");
            var saved = decode(store.json, events.get(0).get("data"));
            require(fingerprint.equals(text(saved.get("fingerprint"))), "提交标识已用于不同型号表操作");
            var result = new LinkedHashMap<>(map(saved.get("result"))); result.put("replayed", true); return result;
        }
        var plan = modelSheetPlan(input, current, true);
        require(text(plan.get("previewToken")).equals(required(input.get("previewToken"), "请先预览型号表")), "型号、原图或来源已变化，请重新预览");
        var after = new LinkedHashMap<>(current.data); after.putAll(map(plan.get("after")));
        boolean changed = !signature(store.json, current.data).equals(signature(store.json, after));
        String kind = text(current.row.get("sourceKind")), rowId = text(current.row.get("rowId"));
        if (changed) saveSourceData(current,after,request);
        var result = new LinkedHashMap<String,Object>();
        result.put("style", detail(kind, id, rowId, text(current.row.get("supplierCode"))));
        result.put("changed", changed); result.put("replayed", false);
        store.remember("catalog_model_sheet", request, id);
        store.event(id, "catalog_model_sheet", Map.of("fingerprint", fingerprint, "sourceKind", kind, "rowId", rowId,
            "before", current.data, "after", after, "result", result), request);
        return result;
    }

    @Transactional
    public Map<String,Object> uploadModelSheet(Map<String,Object> input, MultipartFile file, HttpServletRequest request) {
        permissions.engineer();
        String key = required(input.get("requestId"), "上传标识"), expected = required(input.get("sha256"), "原图校验值").toLowerCase(Locale.ROOT);
        require(key.length() <= 80 && expected.matches("[0-9a-f]{64}"), "上传标识或校验值无效");
        require(file != null && !file.isEmpty() && Set.of("image/png", "image/jpeg", "image/webp").contains(text(file.getContentType())), "型号表原图仅支持PNG、JPEG或WebP，不接受可执行图片内容");
        String name = PartnerFileWorkspace.fileName(file.getOriginalFilename()).toLowerCase(Locale.ROOT), type = text(file.getContentType());
        require(type.equals("image/png") && name.endsWith(".png") || type.equals("image/jpeg") && name.matches(".*\\.jpe?g$") || type.equals("image/webp") && name.endsWith(".webp"), "原图扩展名与格式不一致");
        try (var stream = file.getInputStream()) {
            byte[] prefix = stream.readNBytes(12);
            boolean raster = type.equals("image/png") ? prefix.length >= 8 && Arrays.equals(Arrays.copyOf(prefix, 8), new byte[]{(byte)137,80,78,71,13,10,26,10})
                : type.equals("image/jpeg") ? prefix.length >= 3 && (prefix[0]&255)==255 && (prefix[1]&255)==216 && (prefix[2]&255)==255
                : prefix.length == 12 && new String(prefix,0,4,StandardCharsets.US_ASCII).equals("RIFF") && new String(prefix,8,4,StandardCharsets.US_ASCII).equals("WEBP");
            require(raster, "原图内容不是声明的PNG、JPEG或WebP格式");
            var hash = MessageDigest.getInstance("SHA-256"); hash.update(prefix); var bytes = new byte[65536]; int count;
            while ((count = stream.read(bytes)) >= 0) if (count > 0) hash.update(bytes, 0, count);
            require(expected.equals(HexFormat.of().formatHex(hash.digest())), "原图校验失败，请重新选择文件");
        } catch (java.io.IOException | java.security.NoSuchAlgorithmException e) { throw invalid("原图无法校验，请重试"); }
        String fingerprint = digest(Map.of("sourceKind", text(input.get("sourceKind")), "recordId", number(input.get("recordId")),
            "rowId", text(input.get("rowId")), "supplierCode", text(input.get("supplierCode")), "sha256", expected));
        Long replay = store.created("catalog_model_sheet_file", key);
        var current = modelSheetSource(input, true, replay == null); long id = number(current.row.get("recordId"));
        if (replay != null) {
            require(replay == id, "上传标识已用于另一份来源");
            var events = store.jdbc.queryForList("select data::text from plm_partner_event where record_id=? and action='catalog_model_sheet_file' and request_id=?", id, key);
            require(events.size() == 1, "上传记录不完整，请核对原提交结果"); var saved = decode(store.json, events.get(0).get("data"));
            require(fingerprint.equals(text(saved.get("fingerprint"))), "上传标识已用于不同原图");
            var result = map(saved.get("result")); store.access.requireFile(number(result.get("attachmentId"))); return result;
        }
        var uploaded = materials.uploadCatalogModelSheet(id, text(current.header.get("kind")), file, request);
        var result = store.json.convertValue(uploaded, new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String,Object>>() {});
        store.remember("catalog_model_sheet_file", key, id);
        store.event(id, "catalog_model_sheet_file", Map.of("fingerprint", fingerprint, "rowId", current.row.get("rowId"), "sha256", expected, "result", result), key);
        return result;
    }

    private record FileKey(long recordId, long fileId) {}
    private record SourceRow(long recordId, String rowId) {}

    /** Old imports stored source identity on the committed job row, not on the business row itself. */
    private void attachOriginals(List<Map<String, Object>> rows) {
        var chosen = new LinkedHashSet<SourceRow>();
        for (var row : rows) {
            var originals = new ArrayList<Long>();
            if (number(row.get("sourceAttachmentId")) > 0) originals.add(number(row.get("sourceAttachmentId")));
            row.put("_catalogSourceOriginalIds", originals);
            if (permissions.costs())
                chosen.add(new SourceRow(number(row.get("recordId")), text(row.get("rowId"))));
        }
        if (chosen.isEmpty()) return;
        var references = chosen.stream().map(row -> Map.of("record_id", row.recordId, "row_id", row.rowId)).toList();
        String sql = "select distinct c.record_id,c.row_id::text,j.source_attachment_id as file_id "
            + "from jsonb_to_recordset(?::jsonb) c(record_id bigint,row_id uuid) "
            + "join plm_partner_file_job_row jr on jr.row_id=c.row_id "
            + "join plm_partner_file_job j on j.job_id=jr.job_id and j.record_id=c.record_id and j.kind='import' and j.status='committed' "
            + "join plm_partner_file f on f.record_id=j.record_id and f.attachment_id=j.source_attachment_id "
            + "join plm_attachment a on a.attachment_id=f.attachment_id and a.deleted_flag=0 where "
            + store.access.visibleSqlClause("file", "a.attachment_id") + " order by c.record_id,c.row_id::text,j.source_attachment_id";
        var byRow = new HashMap<SourceRow, LinkedHashSet<Long>>();
        for (var original : store.jdbc.queryForList(sql, encode(store.json, references)))
            byRow.computeIfAbsent(new SourceRow(number(original.get("record_id")), text(original.get("row_id"))), ignored -> new LinkedHashSet<>())
                .add(number(original.get("file_id")));
        for (var row : rows) {
            var ids = new LinkedHashSet<>(PartnerSelectionService.scopeIds(row.get("_catalogSourceOriginalIds")));
            ids.addAll(byRow.getOrDefault(new SourceRow(number(row.get("recordId")), text(row.get("rowId"))), new LinkedHashSet<>()));
            row.put("_catalogSourceOriginalIds", new ArrayList<>(ids));
        }
    }

    private Map<FileKey, Map<String, Object>> files(List<Map<String, Object>> rows) {
        var keys = new LinkedHashSet<FileKey>();
        for (var row : rows) {
            long record = number(row.get("recordId"));
            for (String field : List.of("refs", "modelSheetRefs"))
                for (var ref : maps(row.get(field))) if (number(ref.get("id")) > 0) keys.add(new FileKey(record, number(ref.get("id"))));
            for (long original : PartnerSelectionService.scopeIds(row.get("_catalogSourceOriginalIds"))) keys.add(new FileKey(record, original));
        }
        var result = new HashMap<FileKey, Map<String, Object>>();
        var list = new ArrayList<>(keys);
        for (int begin = 0; begin < list.size(); begin += 200) {
            var batch = list.subList(begin, Math.min(begin + 200, list.size()));
            var args = new ArrayList<Object>();
            var clauses = new ArrayList<String>();
            for (var key : batch) { clauses.add("(f.record_id=? and f.attachment_id=?)"); args.add(key.recordId); args.add(key.fileId); }
            String sql = "select f.record_id,a.attachment_id as id,a.file_name as name,a.version_no as version,a.content_type as type,a.file_size as size,("
                + RESTRICTED_FILE + ") as restricted from plm_partner_file f join plm_attachment a on a.attachment_id=f.attachment_id where a.deleted_flag=0 and ("
                + String.join(" or ", clauses) + ") and " + store.access.visibleSqlClause("file", "a.attachment_id")
                + (permissions.costs() ? "" : " and not (" + RESTRICTED_FILE + ")");
            for (var file : store.jdbc.queryForList(sql, args.toArray())) {
                long record = number(file.remove("record_id"));
                result.put(new FileKey(record, number(file.get("id"))), file);
            }
        }
        return result;
    }

    private List<Map<String, Object>> projectBatch(List<Map<String, Object>> rows, Map<String, Object> profile, boolean detail) {
        attachOriginals(rows);
        var fileMap = files(rows);
        return rows.stream().map(row -> project(row, profile, fileMap, detail)).toList();
    }

    /** Positive field list: arbitrary source notes/snapshots never escape through a hidden UI field. */
    Map<String, Object> project(Map<String, Object> row, Map<String, Object> profile,
                                Map<FileKey, Map<String, Object>> fileMap, boolean detail) {
        var out = new LinkedHashMap<String, Object>();
        for (String key : SUMMARY_FIELDS) if (row.containsKey(key)) out.put(key, row.get(key));
        for (String key : List.of("sourceKind", "sourcePurpose", "recordId", "rowId", "rowVersion", "sourceCode", "sourceName", "recordVersion", "confirmed")) out.put(key, row.get(key));
        out.put("supplierCode", profile.get("supplierCode"));
        out.put("supplierName", profile.get("supplierName"));
        for (String key : List.of("phoneModelIds", "phones", "phoneMatchIssues", "phoneMatchSummary")) if (row.containsKey(key)) out.put(key, row.get(key));
        // A whole-model exclusion is safe operational data, not a colour/price inference.
        out.put("unavailablePhoneModelIds", PartnerSelectionService.scopeIds(row.get("unavailablePhoneModelIds")));
        out.put("phoneScopeMode", row.containsKey("_catalogPhoneScopeMode") ? row.get("_catalogPhoneScopeMode")
            : PartnerCatalogPhoneResolver.supplierScopeMode(row));
        var refs = new ArrayList<Map<String, Object>>();
        var originals = new LinkedHashMap<Long, Map<String, Object>>();
        for (var ref : maps(row.get("refs"))) {
            var file = fileMap.get(new FileKey(number(row.get("recordId")), number(ref.get("id"))));
            if (file == null) continue;
            if (Boolean.TRUE.equals(file.get("restricted"))) originals.put(number(file.get("id")), publicFile(file));
            else refs.add(publicFile(file));
        }
        if (permissions.costs()) for (long file : PartnerSelectionService.scopeIds(row.get("_catalogSourceOriginalIds"))) {
            var source = fileMap.get(new FileKey(number(row.get("recordId")), file));
            if (source != null) originals.put(number(source.get("id")), publicFile(source));
        }
        out.put("refs", refs.stream().map(f -> Map.of("id", f.get("id"), "name", f.get("name"), "version", f.get("version"))).toList());
        out.put("fileDetails", refs);
        out.put("canMaintainModelSheet", permissions.engineering() && "catalog_source".equals(text(row.get("sourcePurpose")))
            && !PartnerSelectionService.confirmed(row) && (!"quote".equals(text(row.get("sourceKind"))) || "draft".equals(text(row.get("sourceStatus")))));
        if (detail) {
            out.put("supplierModelEntries", maps(row.get("supplierModelEntries")).isEmpty() ? List.of()
                : PartnerCatalogModelSheet.project(row.get("supplierModelEntries"), phones.newScope()));
            var modelFiles = new ArrayList<Map<String,Object>>();
            for (var ref : maps(row.get("modelSheetRefs"))) {
                var file = fileMap.get(new FileKey(number(row.get("recordId")), number(ref.get("id"))));
                if (file != null) modelFiles.add(publicFile(file));
            }
            out.put("modelSheetRefs", modelFiles.stream().map(f -> Map.of("id", f.get("id"), "name", f.get("name"), "version", f.get("version"))).toList());
            out.put("modelSheetFileDetails", modelFiles);
        }
        if(detail)out.put("pairings",PartnerCatalogFields.operationalPairings(row.get("pairings")));
        if (permissions.costs()) {
            for (String key : List.of("unitPrice", "currency", "priceNote", "sampleFeeNote", "priceUnit", "currencySource", "rawQuoteFields")) if (row.containsKey(key)) out.put(key, row.get(key));
            if (row.containsKey("priceOptions")) out.put("priceOptions", maps(row.get("priceOptions")).stream().map(option -> {
                var projected = new LinkedHashMap<String, Object>();
                for (String key : List.of("label", "unitPrice", "currency", "priceNote", "moq", "packaging"))
                    if (option.containsKey(key)) projected.put(key, option.get(key));
                return projected;
            }).toList());
            if (detail) {
                for (String key : List.of("notes", "rangeRefs", "rangeSnapshots", "pairings", "supplyScopeNote", "developmentEvidence")) if (row.containsKey(key)) out.put(key, row.get(key));
                out.put("sourceOriginals", new ArrayList<>(originals.values()));
            }
        }
        out.put("canWrite", permissions.engineering());
        out.put("canReadCost", permissions.costs());
        return out;
    }

    private Map<String, Object> publicFile(Map<String, Object> input) {
        var out = new LinkedHashMap<String, Object>();
        for (String key : List.of("id", "name", "version", "type", "size")) if (input.get(key) != null) out.put(key, input.get(key));
        return out;
    }

    private record Choice(Map<String,Object> source,List<Long> chosen,List<Long> added,List<Long> reused,
                          List<Map<String,Object>> pairs,List<Map<String,Object>> existing,Map<String,Object> mergeTarget,Long sampleQty,Map<String,Object> quantityTarget,boolean styleOnly,String selectionModelText) {}
    private record AdditionPlan(Map<String,Object> preview,List<Choice> choices,Map<String,Object> target) {}

    private List<Map<String,Object>> additionItems(Map<String,Object> input) {
        var items=maps(input.get("items"));require(!items.isEmpty()&&items.size()<=100,"请选择1至100款");
        var seen=new HashSet<String>();var result=new ArrayList<Map<String,Object>>();
        for(var item:items){
            String kind=text(item.get("sourceKind")),row=text(item.get("rowId"));long record=number(item.get("recordId"));
            require(Set.of("quote","selection").contains(kind)&&record>0&&PartnerSelectionService.validRowId(row),"目录来源身份无效");
            row=UUID.fromString(row).toString();require(seen.add(kind+":"+record+":"+row),"本次选择含重复款式");
            var value=new LinkedHashMap<>(item);value.put("rowId",row);
            String intent=text(item.get("selectionIntent"));require(intent.isEmpty()||intent.equals("style"),"选品方式无效");
            if(intent.equals("style")){require(PartnerSelectionService.scopeIds(item.get("phoneModelIds")).isEmpty()&&maps(item.get("selectedPairs")).isEmpty(),"仅选款式时请保留型号和颜色待定，明确型号请使用型号选品");String modelText=text(item.get("selectionModelText"));require(modelText.length()<=12000,"原选品型号要求过长");value.put("selectionModelText",modelText);}
            else require(text(item.get("selectionModelText")).isEmpty(),"原选品型号要求仅用于型号后补的款式选品");
            if(value.containsKey("sampleQty")){if(text(value.get("sampleQty")).isEmpty())value.remove("sampleQty");else value.put("sampleQty",qty(value.get("sampleQty"),true));}result.add(value);
        }return result;
    }

    @Transactional(readOnly=true)
    public Map<String,Object> additionPreview(Map<String,Object> input){permissions.engineer();return additionPlan(input,additionItems(input),false,null).preview;}

    private boolean supplierDirectory(Map<String,Object> input){String mode=text(input.get("targetMode"));require(mode.isEmpty()||mode.equals("supplier_directory"),"保存目录方式无效");return mode.equals("supplier_directory");}

    /** Global existence is checked before object authorization: a hidden directory is never absence. */
    private Map<String,Object> destination(Map<String,Object> input,String supplier,boolean lock,Long expected) {
        boolean automatic=supplierDirectory(input);long context=number(input.get("targetSelectionId")),target=context;require(context>=0,"目标目录标识无效");if(automatic){if(input.containsKey("targetSelectionId"))require(text(input.get("targetSelectionId")).matches("[1-9][0-9]*"),"明确的工作台目录标识必须为正整数，不能改用默认目录");if(input.containsKey("targetVersion"))require(context>0&&text(input.get("targetVersion")).matches("[1-9][0-9]*"),"明确的工作台目录版本无效，请保留输入重新载入");}
        if(automatic){
            var relevant=store.jdbc.queryForList("select r.record_id,btrim(coalesce(r.supplier_code,'')) as column_supplier,btrim(coalesce(r.data->>'supplierCode','')) as data_supplier,x.total_rows,x.blank_rows,x.other_rows from plm_partner_record r cross join lateral (select count(*) as total_rows,count(*) filter(where btrim(coalesce(s.data->>'supplierCode',''))='') as blank_rows,count(*) filter(where btrim(coalesce(s.data->>'supplierCode',''))=?) as matching_rows,count(*) filter(where btrim(coalesce(s.data->>'supplierCode','')) not in ('',?)) as other_rows from plm_partner_selection_row s where s.record_id=r.record_id) x where r.kind='selection' and r.deleted_flag=0 and r.data->>'purpose'='my_selection' and coalesce(r.data->>'catalogSource','false')<>'true' and (btrim(coalesce(r.supplier_code,''))=? or btrim(coalesce(r.data->>'supplierCode',''))=? or x.matching_rows>0) order by r.record_id",supplier,supplier,supplier,supplier);
            var candidates=new ArrayList<Long>();for(var row:relevant){if(context>0&&number(row.get("record_id"))!=context)continue;String column=text(row.get("column_supplier")),data=text(row.get("data_supplier"));boolean declared=!column.isEmpty()||!data.isEmpty();require((column.isEmpty()||column.equals(supplier))&&(data.isEmpty()||data.equals(supplier))&&number(row.get("other_rows"))==0&&(declared||number(row.get("blank_rows"))==0),"该供应商的样品目录归属存在冲突或未归类行，请先核对；本次尚未保存");if(declared||number(row.get("total_rows"))>0)candidates.add(number(row.get("record_id")));}
            if(context>0)require(candidates.contains(context),"当前上下文不属于该供应商的样品目录，请返回原工作台核对；本次尚未保存");
            else {require(candidates.size()<=1,"该供应商存在多份样品目录，请从明确的工作台入口办理或先整理目录；本次尚未保存");target=candidates.isEmpty()?0:candidates.get(0);}
        }
        if(expected!=null)require(target==expected,"供应商样品目录已变化，请保留输入并重新保存；本次尚未保存");
        if(target==0)return Map.of();var header=store.load(target,"selection",lock);PartnerSelectionService.requireSelectionPurpose(header);
        if(automatic){require("my_selection".equals(text(header.get("purpose"))),"当前上下文不是我方样品目录，不能改写供应商原始资料");var metadata=store.jdbc.queryForMap("select supplier_code as column_supplier,data->>'supplierCode' as data_supplier from plm_partner_record where record_id=?",target);for(String field:List.of("column_supplier","data_supplier"))require(text(metadata.get(field)).isEmpty()||supplier.equals(text(metadata.get(field))),"样品目录的供应商归属存在冲突，请先核对；本次尚未保存");if(text(header.get("supplierCode")).isEmpty()&&!text(metadata.get("column_supplier")).isEmpty())header.put("supplierCode",supplier);}
        if(!automatic)require(input.containsKey("targetVersion"),"请刷新目标选品表");if(input.containsKey("targetVersion"))store.check(header,number(input.get("targetVersion")));requireTargetSupplier(target,header,supplier);return header;
    }

    private AdditionPlan additionPlan(Map<String,Object> input,List<Map<String,Object>> items,boolean lock,Long expectedTarget) {
        String supplier=required(input.get("supplierCode"),"请先选择供应商");profiles.requireSupplier(supplier);
        Map<String,Object> header=destination(input,supplier,lock,expectedTarget);long target=number(header.get("id"));
        var scope=phones.newScope();var choices=new ArrayList<Choice>();var views=new ArrayList<Map<String,Object>>();var allMessages=new ArrayList<String>();long added=0,reused=0,newPhones=0,updated=0;
        for(var item:items){
            var view=new LinkedHashMap<String,Object>();for(String key:List.of("sourceKind","recordId","rowId","rowVersion","recordVersion"))view.put(key,item.get(key));
            var errors=new ArrayList<String>();view.put("messages",errors);view.put("name","");
            try{
                String kind=text(item.get("sourceKind")),rowId=text(item.get("rowId"));long record=number(item.get("recordId"));
                require(!(kind.equals("selection")&&record==target),"供应商来源不能作为目标选品，请另建我方选品清单");
                var sourceRow=source(kind,record,rowId,supplier);
                String phoneScopeMode=PartnerCatalogPhoneResolver.supplierScopeMode(sourceRow);
                boolean styleOnly="style".equals(text(item.get("selectionIntent")));
                require(styleOnly||!"unresolved".equals(phoneScopeMode),"本款型号资料尚未提取或关联，请先核对原件中的型号表；不能把字段空白当作供应商未提供");
                boolean unspecified="unspecified".equals(phoneScopeMode);
                var chosen=PartnerSelectionService.scopeIds(item.get("phoneModelIds"));
                require(styleOnly||!chosen.isEmpty(),"请选择本次所需的系列或具体型号");
                var row=scope.resolve(sourceRow);
                if(unspecified&&!styleOnly){
                    var proposed=new LinkedHashMap<String,Object>(sourceRow);proposed.put("phoneModelIds",chosen);
                    row=scope.resolve(proposed);
                    row.put("phoneScopeMode","unspecified");
                }
                if(styleOnly)row.put("phoneScopeMode",phoneScopeMode);
                view.put("name",text(row.get("name")));view.put("phoneScopeMode",styleOnly?phoneScopeMode:unspecified?"unspecified":"supplier");
                // Preview never allocates a CP; bind the current identity to the token so a
                // concurrent explicit association requires a fresh preview before saving.
                view.put("catalogStyleId",number(row.get("catalogStyleId")));
                view.put("catalogStyleCode",text(row.get("catalogStyleCode")));
                require(item.containsKey("recordVersion")&&number(item.get("recordVersion"))==number(row.get("recordVersion"))
                    &&number(item.get("rowVersion"))==number(row.get("rowVersion")),"目录来源版本已变化，请刷新后重新预览");
                require(styleOnly||!chosen.isEmpty()&&PartnerSelectionService.scopeIds(row.get("phoneModelIds")).containsAll(chosen),"请明确选择来源可供型号的非空子集");
                var pairs=styleOnly?List.<Map<String,Object>>of():selections.validateCatalogChoice(row,chosen,maps(item.get("selectedPairs")));if(pairs==null)pairs=List.of();
                var existing=new ArrayList<Map<String,Object>>();
                if(target>0)for(var found:store.jdbc.queryForList("select row_id::text,row_version,data::text,confirmed from plm_partner_selection_row where record_id=? "
                    + "and data->'catalogSourceSnapshot'->>'sourceKind'=? and data->'catalogSourceSnapshot'->>'recordId'=? and data->'catalogSourceSnapshot'->>'rowId'=? order by row_id",target,kind,Long.toString(record),rowId)){
                    var saved=decode(store.json,found.get("data"));saved.put("rowId",found.get("row_id"));saved.put("rowVersion",found.get("row_version"));saved.put("confirmed",found.get("confirmed"));existing.add(saved);
                }
                var oldChosen=new LinkedHashSet<Long>();var oldView=new ArrayList<Map<String,Object>>();
                for(var saved:existing){var snapshot=map(saved.get("catalogSourceSnapshot"));
                    require(number(snapshot.get("rowVersion"))==number(row.get("rowVersion"))&&number(snapshot.get("recordVersion"))==number(row.get("recordVersion")),"目标选品已采用该款另一来源版本，请先核对版本差异或另建选品");
                    var shared=PartnerSelectionService.scopeIds(saved.get("phoneModelIds")).stream().filter(chosen::contains).toList();
                    if(styleOnly||!shared.isEmpty()){
                        require(!Boolean.TRUE.equals(saved.get("discarded"))&&!Boolean.FALSE.equals(saved.get("selected")),"已有选品中的型号已取消或排除，请在原选品明确处理，不能静默恢复");
                        for(var pair:pairs)if(shared.contains(number(pair.get("phoneModelId"))))require(maps(saved.get("selectedPairs")).stream().anyMatch(p->number(p.get("phoneModelId"))==number(pair.get("phoneModelId"))&&number(p.get("colorId"))==number(pair.get("colorId"))),"该型号已在选品，新增颜色请在已有选品核对，不能静默忽略");
                        oldChosen.addAll(shared);
                    }
                    oldView.add(Map.of("rowId",saved.get("rowId"),"rowVersion",saved.get("rowVersion"),"confirmed",Boolean.TRUE.equals(saved.get("confirmed")),"phoneModelIds",PartnerSelectionService.scopeIds(saved.get("phoneModelIds")),"selectedPairs",maps(saved.get("selectedPairs"))));
                }
                var newIds=chosen.stream().filter(id->!oldChosen.contains(id)).toList();
                // An unspecified supplier scope is not a claim of full supply. A later new
                // model gets its own draft row, except an unchanged style-only draft awaiting its first model.
                Map<String,Object> merge=newIds.isEmpty()?Map.of():existing.stream().filter(this::mergeable).filter(saved->!unspecified||("style".equals(text(map(saved.get("catalogSourceSnapshot")).get("selectionIntent")))&&PartnerSelectionService.scopeIds(saved.get("phoneModelIds")).isEmpty())).findFirst().orElse(Map.of());
                Long planned=item.containsKey("sampleQty")?number(item.get("sampleQty")):null;var active=existing.stream().filter(saved->!Boolean.TRUE.equals(saved.get("discarded"))&&!Boolean.FALSE.equals(saved.get("selected"))).filter(saved->styleOnly||PartnerSelectionService.scopeIds(saved.get("phoneModelIds")).stream().anyMatch(chosen::contains)||(!merge.isEmpty()&&text(saved.get("rowId")).equals(text(merge.get("rowId"))))).toList();var quantities=active.stream().filter(saved->number(saved.get("sampleQty"))>0).toList();
                require(planned==null||newIds.isEmpty()||!merge.isEmpty()||active.isEmpty(),"本次同时含已有独立选样和新增型号，请分开选择并填写各次拟拿样合计，不能自动分配数量");
                require(planned==null||quantities.size()<=1,"该款已有多行分别填写拟拿样数量，请在原选样清单明确调整，不能把本款合计数量重复填给每行");Map<String,Object> quantityTarget=!quantities.isEmpty()?quantities.get(0):active.stream().findFirst().orElse(Map.of());
                if(styleOnly&&planned!=null&&!quantityTarget.isEmpty()&&!text(quantityTarget.get("sampleQty")).isEmpty())require(planned==number(quantityTarget.get("sampleQty")),"该款已有明确拟拿样数量，请在原选品中调整，不覆盖已有选择");
                boolean quantityChanged=planned!=null&&!quantityTarget.isEmpty()&&(text(quantityTarget.get("sampleQty")).isEmpty()||planned!=number(quantityTarget.get("sampleQty")));if(quantityChanged)require(!PartnerSelectionService.confirmed(quantityTarget),"该款选样依据已保留，请先修改选样再调整拟拿样数量；已有实收记录可另增选样");
                view.put("sampleQty",planned==null?(active.isEmpty()?0L:active.stream().mapToLong(saved->number(saved.get("sampleQty"))).sum()):planned);view.put("sampleQtyChanged",quantityChanged);view.put("quantityTargetRowId",quantityTarget.getOrDefault("rowId",""));
                view.put("phoneModelIds",chosen);view.put("addedPhoneModelIds",newIds);view.put("reusedPhoneModelIds",new ArrayList<>(oldChosen));
                view.put("action",styleOnly?(existing.isEmpty()?"new":"reused"):newIds.isEmpty()?"reused":merge.isEmpty()?"new":"merge");if(styleOnly){view.put("selectionIntent","style");view.put("selectionModelText",text(item.get("selectionModelText")));}view.put("mergeTargetRowId",merge.getOrDefault("rowId",""));
                view.put("selectedPairs",pairs);view.put("availablePhoneModelIds",unspecified?List.of():PartnerSelectionService.scopeIds(row.get("phoneModelIds")));view.put("existingRows",oldView);
                view.put("phones",maps(row.get("phones")).stream().filter(phone->chosen.contains(number(phone.get("phoneModelId")))).toList());
                choices.add(new Choice(row,chosen,newIds,new ArrayList<>(oldChosen),pairs,existing,merge,planned,quantityTarget,styleOnly,text(item.get("selectionModelText"))));if(styleOnly&&existing.isEmpty())added++;else if(!newIds.isEmpty()){if(merge.isEmpty())added++;else updated++;}else if(quantityChanged)updated++;newPhones+=newIds.size();reused+=styleOnly&&!existing.isEmpty()?1:oldChosen.size();
            }catch(com.yuewei.plm.common.exception.BusinessException e){errors.add(e.getMessage());allMessages.add(text(view.get("name"))+"："+e.getMessage());}
            views.add(view);
        }
        var preview=new LinkedHashMap<String,Object>();preview.put("supplierCode",supplier);preview.put("targetSelectionId",target);
        preview.put("targetVersion",header.getOrDefault("version",0L));preview.put("selectionName",text(input.get("selectionName")));
        if(supplierDirectory(input)){preview.put("targetMode","supplier_directory");preview.put("targetName",target>0?text(header.get("name")):text(profiles.requireSupplier(supplier).get("supplierName"))+"样品目录");}
        preview.put("canAdd",allMessages.isEmpty());preview.put("items",views);preview.put("messages",allMessages);
        preview.put("addedCount",added);preview.put("updatedCount",updated);preview.put("reusedCount",reused);preview.put("newPhoneCount",newPhones);preview.put("previewToken",digest(preview));
        return new AdditionPlan(preview,choices,header);
    }

    private boolean mergeable(Map<String,Object> row){
        if(PartnerSelectionService.confirmed(row)||Boolean.TRUE.equals(row.get("discarded"))||Boolean.FALSE.equals(row.get("selected")))return false;
        var baseline=map(map(row.get("catalogSourceSnapshot")).get("selectionBaseline"));
        var actual=new LinkedHashMap<>(PartnerSelectionService.catalogBaseline(row));var original=new LinkedHashMap<>(baseline);actual.remove("sampleQty");original.remove("sampleQty");return !baseline.isEmpty()&&signature(store.json,original).equals(signature(store.json,actual));
    }

    @Transactional
    public Map<String,Object> addToSelection(Map<String,Object> input) {
        permissions.engineer();String supplier=required(input.get("supplierCode"),"请先选择供应商");var profile=profiles.requireSupplier(supplier);
        String request=required(input.get("requestId"),"提交标识");require(request.length()<=80,"提交标识过长");var items=additionItems(input);
        var identity=new LinkedHashMap<String,Object>(Map.of("supplierCode",supplier,"items",items,"targetSelectionId",number(input.get("targetSelectionId")),"selectionName",text(input.get("selectionName")),"previewToken",text(input.get("previewToken"))));if(supplierDirectory(input)){identity.put("targetMode","supplier_directory");if(input.containsKey("targetVersion"))identity.put("targetVersion",input.get("targetVersion"));}String fingerprint=signature(store.json,identity);
        String eventKey="catalog-"+key(store.access.actorId()+":"+request);Long replay=store.created("catalog_add",request);
        if(replay!=null){var target=store.load(replay,"selection",false);PartnerSelectionService.requireSelectionPurpose(target);
            var events=store.jdbc.queryForList("select data::text from plm_partner_event where record_id=? and request_id=? and action='catalog_add'",replay,eventKey);
            require(events.size()==1,"加入记录不完整，请联系管理员核对");var saved=decode(store.json,events.get(0).get("data"));
            require(fingerprint.equals(text(saved.get("fingerprint"))),"重复提交标识对应了不同款式、型号或选品表");var result=new LinkedHashMap<>(map(saved.get("result")));result.put("replayed",true);return result;}
        store.jdbc.queryForList("select pg_advisory_xact_lock(hashtextextended(?,0))","catalog-supplier-directory:"+supplier);
        long target=number(destination(input,supplier,false,null).get("id"));var lockIds=new TreeSet<Long>();
        if(target>0)lockIds.add(target);
        for(var item:items){long id=number(item.get("recordId"));store.load(id,"quote".equals(text(item.get("sourceKind")))?"supplier_quote":"selection",false);lockIds.add(id);}
        for(long id:lockIds)store.jdbc.queryForList("select record_id from plm_partner_record where record_id=? for update",id);
        var plan=additionPlan(input,items,true,target);require(Boolean.TRUE.equals(plan.preview.get("canAdd")),String.join("；",messages(plan.preview.get("messages"))));
        require(text(plan.preview.get("previewToken")).equals(required(input.get("previewToken"),"请先预览本次所选型号及新增内容")),"来源或目标选品已变化，请重新预览，尚未保存");
        var identities=new PartnerCatalogStyles(store);
        for(var choice:plan.choices){
            if(choice.added.isEmpty()&&!(choice.styleOnly&&choice.existing.isEmpty()))continue;
            var row=choice.source;
            identities.ensure(text(row.get("sourceKind")),number(row.get("recordId")),row,number(row.get("catalogStyleId")),
                "系统在保存选品草稿时分配或沿用款式编号；不代表人工核对报价、供货或质检",request);
            // Apply the same identity/default overlay used by GET before the draft snapshot
            // is frozen; the historical source JSON and its version stay unchanged.
            row.putAll(identities.enrich(text(row.get("sourceKind")),number(row.get("recordId")),row));
        }
        Map<String,Object> targetHeader=plan.target;
        if(target==0){String name=supplierDirectory(input)?text(plan.preview.get("targetName")):text(input.get("selectionName")).isEmpty()?text(profile.get("supplierName"))+"选品":text(input.get("selectionName"));targetHeader=selections.save(null,Map.of("name",name,"supplierCode",supplier,"requestId","cat-create-"+key(store.access.actorId()+":"+request)));target=number(targetHeader.get("id"));}
        var commands=new ArrayList<Map<String,Object>>();var origins=new ArrayList<Map<String,Object>>();var resultRows=new LinkedHashSet<String>();
        var sourceRows=plan.choices.stream().map(Choice::source).toList();attachOriginals(sourceRows);
        for(var choice:plan.choices){var row=choice.source;String kind=text(row.get("sourceKind")),rowId=text(row.get("rowId"));long record=number(row.get("recordId"));
            for(var old:choice.existing)if(choice.styleOnly||PartnerSelectionService.scopeIds(old.get("phoneModelIds")).stream().anyMatch(choice.chosen::contains))resultRows.add(text(old.get("rowId")));
            if(choice.sampleQty!=null&&!choice.quantityTarget.isEmpty()&&(text(choice.quantityTarget.get("sampleQty")).isEmpty()||choice.sampleQty!=number(choice.quantityTarget.get("sampleQty")))){var old=choice.quantityTarget;selectionCommand(commands,text(old.get("rowId")),old.get("rowVersion"),Map.of("sampleQty",choice.sampleQty));resultRows.add(text(old.get("rowId")));}
            if(choice.added.isEmpty()&&!(choice.styleOnly&&choice.existing.isEmpty()))continue;
            if(!choice.mergeTarget.isEmpty()){
                var old=choice.mergeTarget;var ids=new TreeSet<>(PartnerSelectionService.scopeIds(old.get("phoneModelIds")));ids.addAll(choice.added);
                var pairs=new ArrayList<>(maps(old.get("selectedPairs")));for(var pair:choice.pairs)if(choice.added.contains(number(pair.get("phoneModelId"))))pairs.add(pair);
                String targetRow=text(old.get("rowId"));selectionCommand(commands,targetRow,old.get("rowVersion"),Map.of("phoneModelIds",new ArrayList<>(ids),"modelText",chosenNames(row,new ArrayList<>(ids)),"selectedPairs",pairs));resultRows.add(targetRow);
                origins.add(Map.of("targetRowId",targetRow,"sourceKind",kind,"recordId",record,"rowId",rowId,"rowVersion",row.get("rowVersion"),"recordVersion",row.get("recordVersion"),"phoneModelIds",choice.added,"sourceOriginalIds",PartnerSelectionService.scopeIds(row.get("_catalogSourceOriginalIds"))));continue;
            }
            var refs=store.canonicalFiles(record,row.get("refs"));var originalIds=PartnerSelectionService.scopeIds(row.get("_catalogSourceOriginalIds"));
            var originals=store.canonicalFiles(record,originalIds.stream().map(id->Map.<String,Object>of("id",id)).toList());materials.inherit(target,refs);
            var modelSheetRefs=store.canonicalFiles(record,row.get("modelSheetRefs"));if(!modelSheetRefs.isEmpty())materials.inherit(target,modelSheetRefs);
            if(!originals.isEmpty()){materials.inherit(target,originals);for(long original:originalIds)store.jdbc.update("update plm_partner_file set purpose='cooperation_cost' where record_id=? and attachment_id=? and purpose<>'financial'",target,original);}
            {String targetRow=key("catalog-choice:"+target+":"+kind+":"+record+":"+rowId+":"+request);var changes=copyFields(row);
                // A new immutable origin rebinds inherited unquoted facts after the source choice was validated.
                if(kind.equals("selection")&&text(row.get("quoteVersion")).isEmpty())changes.put("pairings",maps(changes.get("pairings")).stream().map(pair->{var fact=new LinkedHashMap<>(pair);fact.remove("sourceContext");return fact;}).toList());
                changes.put("phoneModelIds",choice.added);changes.put("modelText",choice.styleOnly?choice.selectionModelText:chosenNames(row,choice.added));
                if(!choice.quantityTarget.isEmpty())changes.put("sampleQty",0L);else if(choice.sampleQty!=null)changes.put("sampleQty",choice.sampleQty);
                changes.put("selectedPairs",choice.pairs.stream().filter(p->choice.added.contains(number(p.get("phoneModelId")))).toList());
                changes.put("refs",refs);changes.put("supplierCode",supplier);changes.put("selected",true);changes.put("discarded",false);
                if(originalIds.size()==1)changes.put("sourceAttachmentId",originalIds.get(0));if(kind.equals("quote")){changes.put("quoteId",Long.toString(record));changes.put("quoteLineId",rowId);}
                var snapshot=new LinkedHashMap<String,Object>();for(String field:List.of("sourceKind","recordId","rowId","rowVersion","recordVersion","sourceCode","sourceName","supplierCode","modelText","phoneModelIds","phones","phoneScopeMode","phoneScopeDeclaration","catalogStyleId","catalogStyleCode","catalogStyleLabel","supplierModelEntries"))snapshot.put(field,row.get(field));snapshot.put("modelSheetRefs",modelSheetRefs);snapshot.put("unavailablePhoneModelIds",PartnerSelectionService.scopeIds(row.get("unavailablePhoneModelIds")));snapshot.put("unavailablePairs",maps(row.get("pairings")).stream().filter(p->"unavailable".equals(text(p.get("state")))).map(p->Map.of("phoneModelId",p.get("phoneModelId"),"colorId",p.get("colorId"))).toList());snapshot.put("sourceOriginalIds",originalIds);if(choice.styleOnly){snapshot.put("selectionIntent","style");snapshot.put("selectionModelText",choice.selectionModelText);}changes.put("catalogSourceSnapshot",snapshot);
                commands.add(Map.of("op","upsert","rowId",targetRow,"rowVersion",0,"changes",changes));resultRows.add(targetRow);
                origins.add(Map.of("targetRowId",targetRow,"sourceKind",kind,"recordId",record,"rowId",rowId,"rowVersion",row.get("rowVersion"),"recordVersion",row.get("recordVersion"),"phoneModelIds",choice.added,"sourceOriginalIds",originalIds));
            }
        }
        if(!commands.isEmpty())targetHeader=selections.patchCatalogRows(target,Map.of("version",targetHeader.get("version"),"requestId","cat-rows-"+key(store.access.actorId()+":"+request),"operations",commands));
        var selected=new LinkedHashMap<String,Object>();for(String field:List.of("id","code","name","version"))selected.put(field,targetHeader.get(field));
        var result=new LinkedHashMap<String,Object>();result.put("selection",selected);result.put("rowIds",new ArrayList<>(resultRows));result.put("addedCount",plan.preview.get("addedCount"));result.put("updatedCount",plan.preview.get("updatedCount"));result.put("reusedCount",plan.preview.get("reusedCount"));result.put("newPhoneCount",plan.preview.get("newPhoneCount"));result.put("items",plan.preview.get("items"));result.put("replayed",false);
        store.remember("catalog_add",request,target);store.event(target,"catalog_add",Map.of("fingerprint",fingerprint,"result",result,"origins",origins,"createdRowIds",commands.stream().filter(op->number(op.get("rowVersion"))==0).map(op->op.get("rowId")).toList()),eventKey);return result;
    }

    private void selectionCommand(List<Map<String,Object>> commands,String row,Object version,Map<String,Object> changes){for(var command:commands)if(row.equals(text(command.get("rowId")))){map(command.get("changes")).putAll(changes);return;}commands.add(new LinkedHashMap<>(Map.of("op","upsert","rowId",row,"rowVersion",version,"changes",new LinkedHashMap<>(changes))));}

    private String chosenNames(Map<String,Object> source,List<Long> ids){var names=new ArrayList<String>();for(long id:ids)names.add(maps(source.get("phones")).stream().filter(p->number(p.get("phoneModelId"))==id).map(p->text(p.getOrDefault("displayName",p.get("modelName")))).findFirst().orElseThrow(()->invalid("所选型号主数据已失效")));return String.join(" / ",names);}

    private static List<String> messages(Object value){return value instanceof List<?> list?list.stream().map(PartnerValues::text).toList():List.of();}
    private String digest(Object value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(signature(store.json,value).getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}

    private void requireTargetSupplier(long target, Map<String, Object> header, String supplier) {
        require(text(header.get("supplierCode")).isEmpty() || supplier.equals(text(header.get("supplierCode"))), "目标选品表属于其他供应商");
        require(!Boolean.TRUE.equals(store.jdbc.queryForObject("select exists(select 1 from plm_partner_selection_row where record_id=? and coalesce(data->>'supplierCode','')<>'' and data->>'supplierCode'<>?)", Boolean.class, target, supplier)), "目标选品表含其他供应商，请另建本供应商的选品表");
    }

    Map<String, Object> copyFields(Map<String, Object> row) {
        var out = new LinkedHashMap<String, Object>();
        for (String field : COPY_FIELDS) if (row.containsKey(field)) out.put(field, row.get(field));
        // Adoption and sample intentions/facts are never copied into a newly selected candidate.
        return out;
    }

    private Map<String, Object> pageResult(List<Map<String, Object>> rows, long total, long page, long size) {
        var result = new LinkedHashMap<String, Object>();
        result.put("content", rows); result.put("totalElements", total); result.put("page", page);
        result.put("size", size); result.put("totalPages", (total + size - 1) / size);
        result.put("canWrite", permissions.engineering()); result.put("canReadCost", permissions.costs());
        return result;
    }

    private static long page(Map<String, String> query) { return Math.max(1, number(query.getOrDefault("page", "1"))); }
    private static long size(Map<String, String> query) { return Math.max(1, Math.min(100, number(query.getOrDefault("size", "30")))); }
    private static long offset(long page, long size) { try { return Math.multiplyExact(page - 1, size); } catch (ArithmeticException e) { throw invalid("页码超出范围"); } }
    private static String key(String value) { return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)).toString(); }
}
