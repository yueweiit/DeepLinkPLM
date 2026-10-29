package com.yuewei.plm.module.moldsales;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuewei.plm.common.exception.BusinessException;
import com.yuewei.plm.common.security.CurrentUser;
import com.yuewei.plm.module.operationlog.service.OperationLogCreateCommand;
import com.yuewei.plm.module.operationlog.service.OperationLogService;
import com.yuewei.plm.module.pilot.PilotAccessService;
import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.zip.GZIPInputStream;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Immutable order evidence. The normal CRM refresh has no write access to this store. */
final class MoldSalesArchiveStore {
    static final String FACTS="plm_mold_sales_fact";
    static final String REVIEW_SQL="source_kind='eccount' and eligibility not in ('eligible','not_finished_stage')";
    static final LocalDate CUTOFF=LocalDate.of(2025,1,1);
    static final Set<String> COMPANIES=Set.of("YW","Lemos","UV");
    record ImportRequest(String file,String sha256){}
    private final JdbcTemplate jdbc;private final ObjectMapper json;private final OperationLogService audit;private final TransactionTemplate tx;private final PilotAccessService access;
    MoldSalesArchiveStore(JdbcTemplate jdbc,ObjectMapper json,OperationLogService audit,TransactionTemplate tx,PilotAccessService access){this.jdbc=jdbc;this.json=json;this.audit=audit;this.tx=tx;this.access=access;}
    private static void require(boolean valid,String message){if(!valid)throw new BusinessException(40001,message);}
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(IOException failure){throw new IllegalStateException(failure);}}
    private JsonNode node(Object value){try{return json.readTree(value.toString());}catch(IOException failure){throw new IllegalStateException(failure);}}
    private static long number(Object value){return value instanceof Number n?n.longValue():0;}
    private static String text(Object value){return Objects.toString(value,"");}
    private static MessageDigest digest(){try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException failure){throw new IllegalStateException(failure);}}
    private static String hash(Path source)throws IOException{var hash=digest();try(var input=Files.newInputStream(source)){byte[] buffer=new byte[1024*1024];int read;while((read=input.read(buffer))!=-1)hash.update(buffer,0,read);}return HexFormat.of().formatHex(hash.digest());}
    private static boolean validFile(String name){return name!=null&&name.matches("eccount-orders-[a-f0-9]{64}\\.jsonl\\.gz");}
    private static Path folder(String root)throws IOException{
        require(root!=null&&!root.isBlank(),"历史订单来源目录尚未配置");
        var base=Path.of(root).toRealPath();var directory=base.resolve("archive").toRealPath();
        require(directory.startsWith(base)&&Files.isDirectory(directory),"历史订单来源目录无效");return directory;
    }
    static Path source(String root,ImportRequest request)throws IOException{
        require(request!=null&&validFile(request.file())&&request.sha256()!=null&&request.sha256().matches("[a-f0-9]{64}")&&request.file().equals("eccount-orders-"+request.sha256()+".jsonl.gz"),"历史订单来源文件或校验值无效");
        var directory=folder(root);var source=directory.resolve(request.file());
        require(!Files.isSymbolicLink(source)&&Files.isRegularFile(source,LinkOption.NOFOLLOW_LINKS)&&source.toRealPath().getParent().equals(directory),"历史订单来源文件不存在或不可用");return source;
    }
    List<Map<String,Object>> sources(String root){
        if(root==null||root.isBlank())return List.of();
        try{
            Path configured=Path.of(root).toAbsolutePath().normalize().resolve("archive");if(!Files.exists(configured))return List.of();
            var out=new ArrayList<Map<String,Object>>();var imported=metadata();String importedHash=text(imported.get("sourceHash"));
            try(var files=Files.list(folder(root))){for(var file:files.filter(f->validFile(f.getFileName().toString())).sorted().toList()){
                var item=new LinkedHashMap<String,Object>();String filename=file.getFileName().toString(),sha=filename.substring("eccount-orders-".length(),"eccount-orders-".length()+64);item.put("file",filename);item.put("sha256",sha);
                try{source(root,new ImportRequest(filename,sha));try(var input=new LineReader(file,null)){JsonNode header=json.readTree(input.next());validateHeader(header);item.putAll(preview(header));item.put("prepared",true);item.put("imported",sha.equals(importedHash));}}
                catch(Exception failure){item.put("prepared",false);item.put("message","来源尚未准备完整，请重新核对导出文件");}
                out.add(item);
            }}return out;
        }catch(IOException failure){throw new BusinessException(40001,"历史订单来源目录暂不可用，请检查服务器存储");}
    }
    private static Map<String,Object> preview(JsonNode header){
        var out=new LinkedHashMap<String,Object>();for(String field:List.of("rows","detailRows","masterRows","pre2025DetailRows","pre2025MasterRows"))out.put(field,header.path(field).asLong());
        out.put("pre2025Rows",header.path("pre2025DetailRows").asLong());out.put("cutoff",CUTOFF.toString());out.put("companies",companies(header));out.put("sourceFileCount",header.path("manifest").size());return out;
    }
    Map<String,Object> metadata(){
        var batches=jdbc.queryForList("select batch_id as \"batchId\",source_hash as \"sourceHash\",source_file as file,created_at as \"importedAt\",source_summary::text as summary from plm_mold_sales_archive_batch");
        if(batches.isEmpty())return Map.of("imported",false);require(batches.size()==1,"历史订单存在多份统计批次，请先核对来源");
        var b=batches.get(0);var out=new LinkedHashMap<String,Object>();out.put("imported",true);out.put("batchId",b.get("batchId"));out.put("sourceHash",b.get("sourceHash"));out.put("file",b.get("file"));out.put("importedAt",b.get("importedAt"));
        JsonNode summary=node(b.get("summary"));summary.fields().forEachRemaining(e->out.put(e.getKey(),json.convertValue(e.getValue(),Object.class)));return out;
    }
    Map<String,Object> status(boolean administrator){
        var all=metadata();if(administrator||!Boolean.TRUE.equals(all.get("imported")))return all;
        return Map.of("imported",true,"importedAt",all.get("importedAt"));
    }
    private static List<String> companies(JsonNode header){var out=new ArrayList<String>();for(var c:header.path("companies"))out.add(c.asText());return out;}
    private static long count(JsonNode n,String field){require(n.path(field).isIntegralNumber()&&n.path(field).canConvertToLong()&&n.path(field).asLong()>=0,"历史订单行数声明无效");return n.path(field).asLong();}
    private static void validateHeader(JsonNode h){
        require(h!=null&&h.isObject()&&h.path("kind").asText().equals("header")&&h.path("format").asInt()==1&&h.path("sourceType").asText().equals("eccount")&&h.path("cutoff").asText().equals(CUTOFF.toString()),"历史订单来源格式或日期分界无效");
        long rows=count(h,"rows"),detail=count(h,"detailRows"),master=count(h,"masterRows");require(rows>0&&rows==detail+master&&count(h,"sourceRows")>=rows,"历史订单行数声明不完整");
        require(count(h,"pre2025DetailRows")<=detail&&count(h,"pre2025MasterRows")<=master,"历史订单分期行数声明无效");
        require(h.path("manifest").isArray()&&!h.path("manifest").isEmpty()&&h.path("sourceManifestSha256").asText().matches("[a-f0-9]{64}"),"历史订单缺少原件清单或清单校验值");
        var names=new HashSet<String>();for(var f:h.path("manifest")){String name=f.path("file").asText();require(!name.isBlank()&&names.add(name)&&f.path("sha256").asText().matches("[a-f0-9]{64}")&&count(f,"bytes")>0,"历史订单原件清单不完整或重复");}
        var cs=companies(h);require(cs.size()==COMPANIES.size()&&new HashSet<>(cs).equals(COMPANIES),"历史订单公司范围声明无效");
        try{OffsetDateTime.parse(h.path("capturedAt").asText());}catch(Exception failure){throw new BusinessException(40001,"历史订单采集时间无效");}
    }
    Map<String,Object> importSource(String root,ImportRequest request,CurrentUser actor,Set<String> profiles){
        access.requireAdministrator();
        try{
            var file=source(root,request);require(hash(file).equals(request.sha256()),"历史订单文件校验失败，本批未导入");
            return tx.execute(ignored->{
                jdbc.execute("select pg_advisory_xact_lock(202610271800)");
                var old=metadata();if(Boolean.TRUE.equals(old.get("imported"))){require(request.sha256().equals(old.get("sourceHash")),"已有历史订单批次；不同来源须先核对差异，不能再次叠加导入");var result=new LinkedHashMap<>(old);result.put("alreadyImported",true);return result;}
                UUID batch=UUID.randomUUID();var digest=digest();var pending=new ArrayList<Map<String,Object>>(1000);var counts=new TreeMap<String,Long>();
                long rows=0,detail=0,master=0,preDetail=0,preMaster=0,review=0;LocalDate start=null,end=null;
                JsonNode header=null,footer=null;var manifest=new HashMap<String,String>();
                try(var input=new LineReader(file,digest)){
                    String line;while((line=input.next())!=null){var r=json.readTree(line);String kind=r.path("kind").asText();
                        if(header==null){validateHeader(r);header=r;for(var f:header.path("manifest"))manifest.put(f.path("file").asText(),f.path("sha256").asText());
                            jdbc.update("insert into plm_mold_sales_archive_batch(batch_id,source_hash,source_file,captured_at,row_count,detail_rows,master_rows,pre2025_detail_rows,pre2025_master_rows,manifest,source_summary,created_by) values(?,?,?,?,?,?,?,?,?,?::jsonb,'{}',?)",batch,request.sha256(),request.file(),OffsetDateTime.parse(r.path("capturedAt").asText()),count(r,"rows"),count(r,"detailRows"),count(r,"masterRows"),count(r,"pre2025DetailRows"),count(r,"pre2025MasterRows"),encode(r.path("manifest")),actor.username());continue;}
                        require(footer==null,"历史订单尾部之后仍有数据，本批未导入");
                        if(kind.equals("footer")){footer=r;continue;}
                        require(kind.equals("line"),"历史订单包含未知记录类型");
                        var prepared=prepare(r,manifest,profiles);LocalDate day=(LocalDate)prepared.remove("businessDay");String role=text(prepared.get("record_kind"));String eligibility=text(prepared.get("eligibility"));
                        rows++;if(role.equals("detail")){detail++;if(day.isBefore(CUTOFF)){preDetail++;counts.merge(eligibility,1L,Long::sum);if(!Set.of("eligible","not_finished_stage").contains(eligibility))review++;start=start==null||day.isBefore(start)?day:start;end=end==null||day.isAfter(end)?day:end;}}else{master++;if(day.isBefore(CUTOFF))preMaster++;}
                        require(rows<=count(header,"rows"),"历史订单行数超出声明，本批未导入");pending.add(prepared);if(pending.size()==1000){write(batch,pending);pending.clear();}
                    }
                    require(header!=null&&footer!=null&&footer.path("complete").asBoolean()&&count(footer,"rows")==rows&&count(footer,"detailRows")==detail&&count(footer,"masterRows")==master&&count(header,"rows")==rows&&count(header,"detailRows")==detail&&count(header,"masterRows")==master&&count(header,"pre2025DetailRows")==preDetail&&count(header,"pre2025MasterRows")==preMaster,"历史订单不完整或分期行数不符，本批未导入");
                    require((!footer.has("pre2025DetailRows")||count(footer,"pre2025DetailRows")==preDetail)&&(!footer.has("pre2025MasterRows")||count(footer,"pre2025MasterRows")==preMaster),"历史订单尾部分期计数不符，本批未导入");
                    require(HexFormat.of().formatHex(digest.digest()).equals(request.sha256()),"历史订单在读取期间发生变化，本批未导入");
                    if(!pending.isEmpty())write(batch,pending);
                    var summary=new LinkedHashMap<String,Object>();summary.put("rows",rows);summary.put("detailRows",detail);summary.put("masterRows",master);summary.put("pre2025Rows",preDetail);summary.put("pre2025DetailRows",preDetail);summary.put("pre2025MasterRows",preMaster);summary.put("reviewRows",review);summary.put("counts",counts);summary.put("companies",companies(header));summary.put("sourceFileCount",header.path("manifest").size());summary.put("cutoff",CUTOFF.toString());summary.put("periodStart",start==null?null:start.toString());summary.put("periodEnd",end==null?null:end.toString());summary.put("sourceManifestSha256",header.path("sourceManifestSha256").asText());summary.put("quantityPolicy","three_company_registered_orders_history_v1");
                    access.requireAdministrator();
                    jdbc.update("update plm_mold_sales_archive_batch set source_summary=?::jsonb where batch_id=?",encode(summary),batch);
                    audit.logRequired(OperationLogCreateCommand.builder().action("MOLD_SALES_ARCHIVE_IMPORT").businessType("MOLD_SALES").businessId(batch.toString()).detailJson(encode(Map.of("sourceHash",request.sha256(),"sourceFile",request.file(),"summary",summary,"remoteWrites",0,"createsProductsOrProductionFacts",false))).build(),actor);
                    var result=new LinkedHashMap<String,Object>(summary);result.put("batchId",batch);result.put("sourceHash",request.sha256());result.put("alreadyImported",false);return result;
                }catch(IOException failure){throw new BusinessException(40001,"历史订单文件读取失败，本批未导入，原资料保留");}
            });
        }catch(IOException failure){throw new BusinessException(40001,"历史订单来源文件暂不可用，请核对后重试");}
    }
    private Map<String,Object> prepare(JsonNode r,Map<String,String> manifest,Set<String> profiles){
        String id=r.path("id").asText(),role=r.path("recordKind").asText(),company=r.path("company").asText(),order=r.path("internalOrderId").asText();
        require(id.matches("[a-f0-9]{64}")&&Set.of("detail","master").contains(role)&&COMPANIES.contains(company),"历史订单行身份、类型或公司无效");
        LocalDate day;try{day=LocalDate.parse(r.path("businessDate").asText());}catch(Exception failure){throw new BusinessException(40001,"历史订单业务日期无效");}
        require(!day.isAfter(LocalDate.now(ZoneId.of("Asia/Shanghai")))&&order.matches("[0-9]{13}")&&order.startsWith(day.toString().replace("-","")),"历史订单日期与内部序号不符");
        JsonNode source=r.path("source");String filename=source.path("file").asText();
        require(manifest.containsKey(filename)&&manifest.get(filename).equals(source.path("sha256").asText())&&!source.path("sheet").asText().isBlank()&&count(source,"row")>0&&source.path("canonicalFingerprint").asText().matches("[a-f0-9]{64}")&&count(source,"occurrence")>0&&count(source,"sourceCount")>0,"历史订单缺少可追溯原件行");
        require(r.path("raw").isObject()&&r.path("cancellationNote").isBoolean()&&(r.path("quantity").isNull()||r.path("quantity").isTextual()),"历史订单源值或数量格式无效");
        BigDecimal qty=null;if(!r.path("quantity").isNull())try{qty=new BigDecimal(r.path("quantity").asText());}catch(NumberFormatException failure){throw new BusinessException(40001,"历史订单数量不是明确数值");}
        var fact=MoldSalesRules.archiveIdentity(r,profiles);String eligibility=role.equals("master")?"header_record":!day.isBefore(CUTOFF)?"crm_period_reference":fact.eligibility();
        var data=(ObjectNode)r.deepCopy();data.put("sourceKind","eccount");data.put("sourceLabel","数据来自 eccount");data.put("name",r.path("raw").path("item_name").asText(""));data.put("spec",r.path("raw").path("spec").asText(""));data.put("date",r.path("raw").path("raw_date_no").asText(day.toString()));
        var out=new LinkedHashMap<String,Object>();out.put("source_id",id);out.put("record_kind",role);out.put("company",company);out.put("business_date",day.toString());out.put("internal_order_id",order);out.put("order_no",r.path("orderNo").asText(""));out.put("item_code",r.path("code").isNull()?null:r.path("code").asText());out.put("style_code",fact.style());out.put("phone_code",fact.phone());out.put("quantity",qty);out.put("unit",r.path("unit").isNull()?null:r.path("unit").asText());out.put("order_status",r.path("statuses").asText(""));out.put("eligibility",eligibility);out.put("source_data",data);out.put("businessDay",day);return out;
    }
    private void write(UUID batch,List<Map<String,Object>> rows){jdbc.update("insert into plm_mold_sales_archive_line select x.source_id,?,x.record_kind,x.company,x.business_date::date,x.internal_order_id,x.order_no,x.item_code,x.style_code,x.phone_code,x.quantity,x.unit,x.order_status,x.eligibility,x.source_data from jsonb_to_recordset(?::jsonb) as x(source_id text,record_kind text,company text,business_date text,internal_order_id text,order_no text,item_code text,style_code text,phone_code text,quantity numeric,unit text,order_status text,eligibility text,source_data jsonb)",batch,encode(rows));}

    /** Bounded line parsing without materialising the gzip or unbounded single lines. */
    private static final class LineReader implements AutoCloseable {
        private final Reader reader;private final char[] buffer=new char[16384];private int at,limit;private boolean ended;
        LineReader(Path file,MessageDigest digest)throws IOException{InputStream raw=Files.newInputStream(file);try{if(digest!=null)raw=new DigestInputStream(raw,digest);reader=new InputStreamReader(new GZIPInputStream(raw,65536),StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT));}catch(IOException failure){raw.close();throw failure;}}
        String next()throws IOException{if(ended)return null;var out=new StringBuilder(2048);while(true){if(at==limit){limit=reader.read(buffer);at=0;if(limit==-1){ended=true;return out.isEmpty()?null:strip(out);}}int start=at;while(at<limit&&buffer[at]!='\n')at++;require(out.length()+at-start<=16*1024*1024,"历史订单单行过大，请核对源文件");out.append(buffer,start,at-start);if(at<limit){at++;return strip(out);}}}
        private static String strip(StringBuilder line){if(!line.isEmpty()&&line.charAt(line.length()-1)=='\r')line.setLength(line.length()-1);return line.toString();}
        public void close()throws IOException{reader.close();}
    }
}
