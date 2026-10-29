package com.yuewei.plm.module.moldsales;

import com.fasterxml.jackson.databind.*;
import com.yuewei.plm.common.exception.BusinessException;
import com.yuewei.plm.common.security.CurrentUser;
import com.yuewei.plm.common.util.ProductBusinessCodeGenerator;
import com.yuewei.plm.module.moldreference.PhoneProductEvidence;
import com.yuewei.plm.module.operationlog.service.*;
import com.yuewei.plm.module.pilot.PilotAccessService;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Immutable source-line classifications. Never called by physical groups or costing. */
public final class MoldSalesHistoryReviewStore {
    public record SourceRequest(String file,String sha256) {}
    public record ApplyRequest(String file,String sha256,String reason) {}
    private record Policy(JsonNode data,Map<String,JsonNode> groups,Map<String,JsonNode> lines) {}
    private static final Set<String> KINDS=Set.of("coded_history","purchased","pending");
    private final JdbcTemplate jdbc;private final ObjectMapper json;private final OperationLogService audit;private final TransactionTemplate tx;private final PilotAccessService access;
    MoldSalesHistoryReviewStore(JdbcTemplate jdbc,ObjectMapper json,OperationLogService audit,TransactionTemplate tx,PilotAccessService access){this.jdbc=jdbc;this.json=json;this.audit=audit;this.tx=tx;this.access=access;}
    private static void require(boolean ok,String message){if(!ok)throw new BusinessException(40001,message);}
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}}
    private JsonNode node(Object value){try{return json.readTree(value.toString());}catch(Exception e){throw new IllegalStateException(e);}}
    private static String text(Object value){return Objects.toString(value,"");}
    private static String key(String style,String phone){return style+":"+phone;}
    private static BigDecimal amount(Object value){return new BigDecimal(value.toString());}
    private static long count(JsonNode n,String field){require(n.path(field).canConvertToLong()&&n.path(field).asLong()>0,"历史处理计划计数无效");return n.path(field).asLong();}
    private static BigDecimal quantity(JsonNode n){BigDecimal q;try{q=new BigDecimal(n.path("quantity").asText());}catch(Exception e){throw new BusinessException(40001,"历史处理计划数量无效");}require(q.signum()>0&&q.stripTrailingZeros().scale()<=0,"历史处理计划数量不是正整数");return q;}
    static Path source(String root,SourceRequest r)throws IOException{
        require(root!=null&&!root.isBlank()&&r!=null&&r.sha256()!=null&&r.sha256().matches("[a-f0-9]{64}")&&("history-policy-"+r.sha256()+".json").equals(r.file()),"历史处理来源无效");
        Path folder=Path.of(root).toAbsolutePath().normalize().resolve("history-policies");Path file=folder.resolve(r.file());
        require(!Files.isSymbolicLink(folder)&&!Files.isSymbolicLink(file)&&Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS),"历史处理来源暂不可用");
        Path actual=file.toRealPath();require(actual.getParent().equals(folder.toRealPath())&&Files.size(actual)<=64L*1024*1024,"历史处理来源不在指定目录或超出可用资源");return actual;
    }
    private Policy load(String root,SourceRequest request){
        try{
            byte[] bytes=Files.readAllBytes(source(root,request));require(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(request.sha256()),"历史处理文件校验不符");
            JsonNode p=json.readTree(bytes);require(p.path("format").asInt()==1&&p.path("groups").isArray()&&p.path("bindings").isArray()&&p.path("costPolicy").asText().equals("no_physical_mold_or_cost_transfer")&&p.path("originalValuesPolicy").asText().equals("immutable_archive"),"历史处理计划格式或成本边界无效");
            for(String field:List.of("sourceHash","reportHash","recommendationHash"))require(p.path(field).asText().matches("[a-f0-9]{64}"),"历史处理计划缺少来源校验");
            require(!p.path("approvalText").asText().isBlank(),"历史处理计划缺少用户确认依据");
            var groups=new LinkedHashMap<String,JsonNode>();for(var g:p.path("groups")){
                String s=g.path("styleCode").asText(),phone=g.path("phoneCode").asText();require(s.matches("[A-Z]{2}")&&phone.matches("[0-9]{4}")&&KINDS.contains(g.path("classification").asText())&&g.path("evidence").isObject(),"历史处理分组身份或分类无效");quantity(g);count(g,"lineCount");require(groups.put(key(s,phone),g)==null,"历史处理分组重复");
            }
            var lines=new LinkedHashMap<String,JsonNode>();for(var l:p.path("bindings")){
                String id=l.path("sourceId").asText();require(id.matches("[a-f0-9]{64}")&&groups.containsKey(key(l.path("styleCode").asText(),l.path("phoneCode").asText()))&&l.path("itemCode").isTextual()&&l.path("name").isTextual(),"历史原单绑定无效");quantity(l);require(lines.put(id,l)==null,"历史原单行重复，不可重复计数");
                if(l.has("displayName")){require(l.path("scope").asText().equals("display_only")&&!l.path("displayName").asText().isBlank()&&!l.path("mappingReason").asText().isBlank(),"型号对照不得用于实物或成本关联");require(l.path("displayPhoneCode").isNull()||l.path("displayPhoneCode").asText().matches("[0-9]{4}"),"型号对照编码无效");}
            }
            require(groups.size()==count(p.path("summary"),"groups")&&lines.size()==count(p.path("summary"),"rows"),"历史处理计划不完整");return new Policy(p,groups,lines);
        }catch(IOException|NoSuchAlgorithmException e){throw new BusinessException(40001,"历史处理来源读取失败，请核对预备资料");}
    }
    Map<String,Object> metadata(){
        var rows=jdbc.queryForList("select batch_id::text as \"batchId\",policy_hash as \"policyHash\",archive_hash as \"sourceHash\",summary::text,created_at::text as \"appliedAt\",created_by as \"appliedBy\" from plm_mold_sales_history_batch");
        if(rows.isEmpty())return Map.of("applied",false);
        var out=new LinkedHashMap<String,Object>(rows.get(0));out.put("applied",true);out.put("summary",node(out.get("summary")));return out;
    }
    List<Map<String,Object>> sources(String root){
        if(root==null||root.isBlank())return List.of();Path folder=Path.of(root).resolve("history-policies");if(!Files.isDirectory(folder)||Files.isSymbolicLink(folder))return List.of();
        var list=new ArrayList<Map<String,Object>>();var current=metadata();
        try(var files=Files.newDirectoryStream(folder,"history-policy-*.json")){for(Path file:files){String name=file.getFileName().toString();if(!name.matches("history-policy-[a-f0-9]{64}\\.json"))continue;var request=new SourceRequest(name,name.substring(15,79));var p=load(root,request);var row=summary(p);row.put("file",name);row.put("sha256",request.sha256());row.put("applied",request.sha256().equals(current.get("policyHash")));list.add(row);}}
        catch(IOException e){throw new BusinessException(40001,"历史处理来源暂不可用");}return list;
    }
    Map<String,Object> preview(String root,SourceRequest request,Set<String> profiles){var p=load(root,request);validate(p,profiles);var out=summary(p);out.put("file",request.file());out.put("sha256",request.sha256());out.put("applied",request.sha256().equals(metadata().get("policyHash")));return out;}
    void checkSource(String root,SourceRequest request){load(root,request);}
    private Map<String,Object> summary(Policy p){var out=new LinkedHashMap<String,Object>();out.put("summary",p.data.path("summary"));out.put("sourceHash",p.data.path("sourceHash").asText());out.put("approvalText",p.data.path("approvalText").asText());out.put("originalValuesPreserved",true);out.put("physicalMoldsAndCostsUnchanged",true);return out;}
    private void validate(Policy p,Set<String> profiles){
        require(jdbc.queryForObject("select count(*) from plm_mold_sales_archive_batch where source_hash=?",Long.class,p.data.path("sourceHash").asText())==1,"历史订单来源已变化，请重新核对");
        var args=new ArrayList<Object>();for(var g:p.groups.values()){args.add(g.path("styleCode").asText());args.add(g.path("phoneCode").asText());}
        String pairs=String.join(",",Collections.nCopies(p.groups.size(),"(?,?)"));
        var actual=jdbc.queryForList("select substring(source_id,9) id,style_code,phone_code,item_code,coalesce(source_data->>'name','') name,quantity,business_date::text business_date,company,unit from plm_mold_sales_fact where source_kind='eccount' and "+MoldSalesRules.factEligibleSql(profiles)+" and (style_code,phone_code) in ("+pairs+")",args.toArray());
        require(actual.size()==p.lines.size(),"有效历史原单范围已变化，本批未处理");
        var totals=new HashMap<String,BigDecimal>();var counts=new HashMap<String,Long>();var seen=new HashSet<String>();var itemCodes=new HashMap<String,Set<String>>();
        var codeCounts=new HashMap<String,Long>();for(var r:jdbc.queryForList("select code_value,count(*) n from plm_code_item where deleted_flag=0 and code_type='phone_model' group by code_value"))codeCounts.put(text(r.get("code_value")),((Number)r.get("n")).longValue());
        for(var r:actual){
            var expected=p.lines.get(text(r.get("id")));require(expected!=null&&seen.add(text(r.get("id"))),"原单行与批准范围不一致");
            for(var pair:List.of(new String[]{"style_code","styleCode"},new String[]{"phone_code","phoneCode"},new String[]{"item_code","itemCode"},new String[]{"name","name"},new String[]{"business_date","businessDate"},new String[]{"company","company"},new String[]{"unit","unit"}))require(text(r.get(pair[0])).equals(expected.path(pair[1]).asText("")),"原订单或编码已变化，本批未处理");
            require(amount(r.get("quantity")).compareTo(quantity(expected))==0,"原订单数量已变化，本批未处理");
            if(expected.has("displayName")&&!expected.path("displayPhoneCode").isNull())require(codeCounts.getOrDefault(expected.path("displayPhoneCode").asText(),0L)==1,"型号对照目标已变化，请重新核对");
            String k=key(text(r.get("style_code")),text(r.get("phone_code")));totals.merge(k,amount(r.get("quantity")),BigDecimal::add);counts.merge(k,1L,Long::sum);itemCodes.computeIfAbsent(k,ignored->new HashSet<>()).add(text(r.get("item_code")));
        }
        var roots=new HashMap<String,Long>();for(var r:jdbc.queryForList("select product_specific_code,count(*) n from plm_product where deleted_flag=0 and product_type='product_line' group by product_specific_code"))roots.put(text(r.get("product_specific_code")),((Number)r.get("n")).longValue());
        Set<String> moldCodes=new HashSet<>(jdbc.queryForList("select upper(btrim(mold_code)) from plm_mold_code_source where not source_disabled",String.class));
        Set<String> partnerCodes=new HashSet<>(jdbc.queryForList("select distinct source_code from plm_master_data_source where source_kind='product_sku' and object_type='partner'",String.class));
        Set<String> selfCodes=new HashSet<>(jdbc.queryForList("select distinct p.finished_product_code from plm_product p where p.deleted_flag=0 and "+PhoneProductEvidence.historicalSkuSql("p"),String.class));
        var byKind=new TreeMap<String,BigDecimal>();var kindGroups=new HashMap<String,Long>();var kindRows=new HashMap<String,Long>();var parser=new ProductBusinessCodeGenerator();
        for(var g:p.groups.values()){
            String s=g.path("styleCode").asText(),phone=g.path("phoneCode").asText(),k=key(s,phone),kind=g.path("classification").asText();
            require(counts.getOrDefault(k,0L)==count(g,"lineCount")&&totals.getOrDefault(k,BigDecimal.ZERO).compareTo(quantity(g))==0,"分组原单数量不符，本批未处理");
            if(kind.equals("coded_history")){
                require(roots.getOrDefault(s,0L)==1&&codeCounts.getOrDefault(phone,0L)==1,"历史编码的款式或手机资料不唯一");var basis=g.path("evidence").path("sourceMoldCodes");require(basis.isArray()&&!basis.isEmpty(),"缺少完整原模具编码依据");
                for(var code:basis){String value=code.asText();require(ProductBusinessCodeGenerator.isCompleteMoldCode(value)&&moldCodes.contains(value),"原模具编码已停用或变化");var parts=parser.parseMoldCode(value,null,List.of(),null);require(parts.productSpecificCode().equals(s)&&parts.phoneModelCode().equals(phone)&&Set.of("10","20").contains(value.substring(3,5)),"模具编码与历史分组矛盾，不能自动整理");}
            }else if(kind.equals("purchased")){for(String code:itemCodes.getOrDefault(k,Set.of()))require(partnerCodes.contains(code)&&!selfCodes.contains(code),"外购来源未完全对应或出现自有SKU，本批未处理");}
            byKind.merge(kind,quantity(g),BigDecimal::add);kindGroups.merge(kind,1L,Long::sum);kindRows.merge(kind,count(g,"lineCount"),Long::sum);
        }
        require(totals.values().stream().reduce(BigDecimal.ZERO,BigDecimal::add).compareTo(quantity(p.data.path("summary")))==0,"总订单件数不符，本批未处理");
        for(String kind:KINDS){var c=p.data.path("summary").path("categories").path(kind);require(kindGroups.getOrDefault(kind,0L)==c.path("groups").asLong()&&kindRows.getOrDefault(kind,0L)==c.path("rows").asLong()&&byKind.getOrDefault(kind,BigDecimal.ZERO).compareTo(new BigDecimal(c.path("quantity").asText("0")))==0,"历史分类汇总不符");}
    }
    Map<String,Object> apply(String root,ApplyRequest request,Set<String> profiles,CurrentUser actor){
        require(request.reason()!=null&&!request.reason().isBlank()&&request.reason().length()<=1000,"请填写本次处理依据");var source=new SourceRequest(request.file(),request.sha256());var p=load(root,source);
        return tx.execute(ignored->{
            jdbc.execute("select pg_advisory_xact_lock(202610281830)");var current=metadata();if(Boolean.TRUE.equals(current.get("applied"))){require(request.sha256().equals(current.get("policyHash")),"已有历史处理批次，不能覆盖；请另行核对差异");var duplicate=new LinkedHashMap<>(current);duplicate.put("alreadyApplied",true);return duplicate;}
            jdbc.execute("set local lock_timeout='3s'");jdbc.execute("set local statement_timeout='90s'");jdbc.execute("lock table plm_mold_sales_archive_line,plm_mold_code_source,plm_code_item,plm_product,plm_master_data_source in share mode");
            access.requireAdministrator();validate(p,profiles);UUID id=UUID.randomUUID();
            jdbc.update("insert into plm_mold_sales_history_batch(batch_id,policy_hash,archive_hash,report_hash,recommendation_hash,source_file,approval_text,reason,summary,created_by) values(?,?,?,?,?,?,?,?,?::jsonb,?)",id,request.sha256(),p.data.path("sourceHash").asText(),p.data.path("reportHash").asText(),p.data.path("recommendationHash").asText(),request.file(),p.data.path("approvalText").asText(),request.reason().strip(),encode(p.data.path("summary")),actor.username());
            for(var g:p.groups.values())jdbc.update("insert into plm_mold_sales_history_group values(?,?,?,?,?,?,?::jsonb)",id,g.path("styleCode").asText(),g.path("phoneCode").asText(),g.path("classification").asText(),quantity(g),count(g,"lineCount"),encode(g.path("evidence")));
            var pending=new ArrayList<JsonNode>();for(var line:p.lines.values()){pending.add(line);if(pending.size()==1000){writeLines(id,pending);pending.clear();}}if(!pending.isEmpty())writeLines(id,pending);
            access.requireAdministrator();audit.logRequired(OperationLogCreateCommand.builder().action("MOLD_SALES_HISTORY_CLASSIFY").businessType("MOLD_SALES").businessId(id.toString()).detailJson(encode(Map.of("policyHash",request.sha256(),"sourceHash",p.data.path("sourceHash").asText(),"summary",p.data.path("summary"),"reason",request.reason().strip(),"originalOrdersAndCodesPreserved",true,"physicalMoldsAndCostsUnchanged",true))).build(),actor);
            var out=new LinkedHashMap<>(metadata());out.put("alreadyApplied",false);return out;
        });
    }
    private void writeLines(UUID id,List<JsonNode> rows){jdbc.update("insert into plm_mold_sales_history_line select x.\"sourceId\",?,x.\"styleCode\",x.\"phoneCode\",x.\"displayPhoneCode\",x.\"displayName\",x.\"mappingReason\" from jsonb_to_recordset(?::jsonb) as x(\"sourceId\" text,\"styleCode\" text,\"phoneCode\" text,\"displayPhoneCode\" text,\"displayName\" text,\"mappingReason\" text)",id,encode(rows));}
    Map<String,Object> page(int page,int size,String keyword,String classification){
        require(page>0&&size>0&&size<=100&&(classification.equals("all")||KINDS.contains(classification)),"历史资料分页或分类无效");
        var args=new ArrayList<Object>();String where="true";if(!classification.equals("all")){where+=" and g.classification=?";args.add(classification);}
        if(keyword!=null&&!keyword.isBlank()){where+=" and (position(lower(?) in lower(g.style_code||' '||g.phone_code||' '||g.evidence::text))>0 or exists(select 1 from plm_mold_sales_history_line l where l.batch_id=g.batch_id and l.style_code=g.style_code and l.phone_code=g.phone_code and position(lower(?) in lower(coalesce(l.display_phone_code,'')||' '||coalesce(l.display_name,'')))>0))";args.add(keyword.strip());args.add(keyword.strip());}
        long count=jdbc.queryForObject("select count(*) from plm_mold_sales_history_group g where "+where,Long.class,args.toArray());
        args.add(size);args.add(((long)page-1)*size);
        var rows=jdbc.queryForList("select g.style_code as \"styleCode\",g.phone_code as \"phoneCode\",g.classification,g.quantity,g.line_count as \"lineCount\",g.evidence::text from plm_mold_sales_history_group g where "+where+" order by g.quantity desc,g.style_code,g.phone_code limit ? offset ?",args.toArray());
        rows.forEach(r->r.put("evidence",node(r.get("evidence"))));var result=new LinkedHashMap<>(metadata());result.put("content",rows);result.put("page",page);result.put("size",size);result.put("totalElements",count);result.put("totalPages",(count+size-1)/size);return result;
    }
    Map<String,Object> detail(String style,String phone,int page,int size){
        require(style!=null&&style.matches("[A-Z]{2}")&&phone!=null&&phone.matches("[0-9]{4}")&&page>0&&size>0&&size<=100,"历史订单分组或分页无效");
        var group=jdbc.queryForList("select classification,quantity,line_count as \"lineCount\",evidence::text from plm_mold_sales_history_group where style_code=? and phone_code=?",style,phone);require(group.size()==1,"历史分组不存在");group.get(0).put("evidence",node(group.get(0).get("evidence")));
        var rows=jdbc.queryForList("select a.source_id as \"sourceId\",a.order_no as \"orderNo\",a.business_date::text as \"orderDate\",a.company,a.item_code as \"itemCode\",a.phone_code as \"phoneCode\",a.quantity,a.unit,coalesce(a.source_data->>'name','') as \"originalName\",l.display_phone_code as \"displayPhoneCode\",l.display_name as \"displayName\",l.mapping_reason as \"mappingReason\",a.source_data->'source'->>'file' as \"sourceFile\",a.source_data->'source'->>'sheet' as \"sourceSheet\",a.source_data->'source'->>'row' as \"sourceRow\" from plm_mold_sales_history_line l join plm_mold_sales_archive_line a using(source_id) where l.style_code=? and l.phone_code=? order by a.business_date desc,a.source_id limit ? offset ?",style,phone,size,((long)page-1)*size);
        return Map.of("group",group.get(0),"orders",rows,"page",page,"size",size,"totalElements",group.get(0).get("lineCount"),"sourceLabel","数据来自 eccount","mappingScope","display_only");
    }
}
