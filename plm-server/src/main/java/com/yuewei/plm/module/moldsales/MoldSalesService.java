package com.yuewei.plm.module.moldsales;

import com.fasterxml.jackson.databind.*;
import com.yuewei.plm.common.exception.BusinessException;
import com.yuewei.plm.common.security.*;
import com.yuewei.plm.module.pilot.PilotAccessService;
import com.yuewei.plm.module.operationlog.service.*;
import com.yuewei.plm.module.moldreference.MoldCompatibilityPolicy;
import jakarta.annotation.PreDestroy;
import java.io.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.GZIPInputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

@Service
public class MoldSalesService {
    private final JdbcTemplate jdbc;private final ObjectMapper json;private final PilotAccessService access;
    private final OperationLogService audit;private final TransactionTemplate tx;
    private final MoldActualCostStore actualCosts;
    private final MoldSalesArchiveStore archive;
    private final MoldSalesHistoryReviewStore historyReview;
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private volatile Map<String,Object> job=Map.of("status","idle");
    @Value("${app.mold-sales.root:}") private String root;
    @Value("${app.mold-sales.exporter:}") private String exporter;
    @Value("${app.mold-sales.ssh-host:yuewei@155.138.234.129}") private String sshHost;
    @Value("${app.mold-sales.password-file:}") private String passwordFile;
    public MoldSalesService(JdbcTemplate jdbc,ObjectMapper json,PilotAccessService access,OperationLogService audit,PlatformTransactionManager transactions){this.jdbc=jdbc;this.json=json;this.access=access;this.audit=audit;this.tx=new TransactionTemplate(transactions);this.actualCosts=new MoldActualCostStore(jdbc,json,audit);this.archive=new MoldSalesArchiveStore(jdbc,json,audit,tx,access);this.historyReview=new MoldSalesHistoryReviewStore(jdbc,json,audit,tx,access);}
    @PreDestroy public void close(){executor.shutdownNow();}
    private void read(){access.requireDomesticView();access.requirePermission("collaboration:view");}
    private static void require(boolean valid,String message){if(!valid)throw new BusinessException(40001,message);}
    private String encode(Object v){try{return json.writeValueAsString(v);}catch(Exception e){throw new IllegalStateException(e);}}
    private JsonNode node(Object v){try{return json.readTree(v.toString());}catch(Exception e){throw new IllegalStateException(e);}}
    private static long number(Object v){return v instanceof Number n?n.longValue():0;}
    private static String text(Object v){return Objects.toString(v,"");}
    private Map<String,Object> latest(){var r=jdbc.queryForList("select snapshot_id as \"id\",source_hash as \"sourceHash\",source_file as \"sourceFile\",captured_at as \"capturedAt\",row_count as \"sourceRows\",costs::text,source_summary::text as summary from plm_mold_sales_snapshot order by (snapshot_id=(select snapshot_id from plm_mold_sales_line limit 1)) desc nulls last,created_at desc,snapshot_id desc limit 1");return r.isEmpty()?null:r.get(0);}
    public Map<String,Object> recalculate() throws Exception {
        read();access.requireAdministrator();require(!root.isBlank(),"CRM 本地来源尚未配置");var current=latest();require(current!=null,"尚无 CRM 来源");String filename=text(current.get("sourceFile"));require(filename.matches("crm-sales-[A-Za-z0-9-]+\\.jsonl\\.gz"),"来源文件名无效");return importSnapshot(Path.of(root).resolve("snapshots").resolve(filename),Path.of(root).resolve("cost-baseline.json"),access.actor());
    }
    public Map<String,Object> status(){
        read();var review=MoldSalesReconciliation.load(jdbc,json);return status(new MoldSalesHistoricalProjection(jdbc,json,access,groups(review),finishedProfiles(),review.ignoredKeys()));
    }
    private Map<String,Object> status(MoldSalesHistoricalProjection historical){
        var s=latest();boolean admin=access.actor().allPermissions();var history=archive.metadata();var out=new LinkedHashMap<String,Object>();
        out.put("connected",s!=null||Boolean.TRUE.equals(history.get("imported")));out.put("canSync",admin&&!root.isBlank()&&!exporter.isBlank());out.put("canRecalculate",admin&&!root.isBlank()&&s!=null&&text(s.get("sourceFile")).matches("crm-sales-[A-Za-z0-9-]+\\.jsonl\\.gz")&&Files.isRegularFile(Path.of(root).resolve("snapshots").resolve(text(s.get("sourceFile"))))&&Files.isRegularFile(Path.of(root).resolve("cost-baseline.json")));out.put("canImportArchive",admin&&!root.isBlank());out.put("canReadCost",access.hasPermission("finance:read"));out.put("canWriteCost",access.hasPermission("finance:read")&&access.hasPermission("finance:write"));out.put("capturedAt",s==null?null:s.get("capturedAt"));out.put("sourceHash",s==null?null:s.get("sourceHash"));
        String start=s==null?null:node(s.get("summary")).path("periodStart").asText();String end=s==null?null:node(s.get("summary")).path("periodEnd").asText();
        // Ordinary object-scoped users are not given global archive quantities or dates.
        if(admin&&history.get("periodStart")!=null&&(start==null||history.get("periodStart").toString().compareTo(start)<0))start=history.get("periodStart").toString();
        if(admin&&end==null&&history.get("periodEnd")!=null)end=history.get("periodEnd").toString();
        out.put("periodStart",start);out.put("periodEnd",end);out.put("archive",admin?history:archive.status(false));
        if(admin){if(historical!=null)out.put("historyReview",historical.metadata(historyReview.metadata()));out.put("canReadHistoryReview",true);out.put("sourceRows",s==null?null:s.get("sourceRows"));out.put("sourceSummary",s==null?null:node(s.get("summary")));out.put("job",job);}return out;
    }
    public List<Map<String,Object>> archiveSources(){read();access.requireAdministrator();return archive.sources(root);}
    public Map<String,Object> archiveStatus(){read();return archive.status(access.actor().allPermissions());}
    public synchronized Map<String,Object> importArchive(MoldSalesArchiveStore.ImportRequest request){
        read();access.requireAdministrator();
        try{MoldSalesArchiveStore.source(root,request);}catch(IOException failure){throw new BusinessException(40001,"历史订单来源文件不可用，请核对预备资料");}
        if("running".equals(job.get("status"))){require("archive".equals(job.get("kind"))&&request.sha256().equals(job.get("sourceHash")),"已有订单任务正在处理，请等待完成后再导入");return job;}
        var actor=access.actor();String id=UUID.randomUUID().toString();job=Map.of("id",id,"status","running","kind","archive","sourceHash",request.sha256(),"message","正在导入历史销售订单，完成前原统计保留");
        executor.submit(()->{CurrentUserContext.set(actor);try{read();access.requireAdministrator();var result=archive.importSource(root,request,actor,finishedProfiles());job=Map.of("id",id,"status","completed","kind","archive","sourceHash",request.sha256(),"message","历史销售订单已导入","result",result);}
            catch(Exception failure){boolean unknown=!(failure instanceof BusinessException);String message=unknown?"历史订单导入结果尚未确认，请刷新历史来源状态核对；不要重复追加":failure.getMessage();job=Map.of("id",id,"status","failed","kind","archive","sourceHash",request.sha256(),"resultUnknown",unknown,"message",message);org.slf4j.LoggerFactory.getLogger(getClass()).warn("Mold sales archive import failed: {}",failure.getClass().getSimpleName());}
            finally{CurrentUserContext.clear();}});return job;
    }
    private void historyRead(){read();access.requireAdministrator();}
    public List<Map<String,Object>> historyReviewSources(){historyRead();return historyReview.sources(root);}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> previewHistoryReview(MoldSalesHistoryReviewStore.SourceRequest request){historyRead();return historyReview.preview(root,request,finishedProfiles());}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> historyReviewPage(int page,int size,String keyword,String classification){historyRead();var review=MoldSalesReconciliation.load(jdbc,json);var historical=new MoldSalesHistoricalProjection(jdbc,json,access,groups(review),finishedProfiles(),review.ignoredKeys());return historical.page(page,size,keyword,classification,historyReview.metadata());}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> historyReviewDetail(String style,String phone,int page,int size){return historyReviewDetail(style,phone,null,page,size);}
    public Map<String,Object> historyReviewDetail(String style,String phone,String sourceGroupKey,int page,int size){historyRead();if(sourceGroupKey==null)return historyReview.detail(style,phone,page,size);var review=MoldSalesReconciliation.load(jdbc,json);return new MoldSalesHistoricalProjection(jdbc,json,access,groups(review),finishedProfiles(),review.ignoredKeys()).reviewDetail(sourceGroupKey,page,size);}
    public synchronized Map<String,Object> applyHistoryReview(MoldSalesHistoryReviewStore.ApplyRequest request){
        historyRead();historyReview.checkSource(root,new MoldSalesHistoryReviewStore.SourceRequest(request.file(),request.sha256()));
        if("running".equals(job.get("status"))){require("historyReview".equals(job.get("kind"))&&request.sha256().equals(job.get("sourceHash")),"已有订单任务正在处理，请等待完成");return job;}
        var actor=access.actor();String id=UUID.randomUUID().toString();job=Map.of("id",id,"status","running","kind","historyReview","sourceHash",request.sha256(),"message","正在保存已确认的历史分类和型号对照，原单及成本保留");
        executor.submit(()->{CurrentUserContext.set(actor);try{historyRead();var result=historyReview.apply(root,request,finishedProfiles(),actor);job=Map.of("id",id,"status","completed","kind","historyReview","sourceHash",request.sha256(),"message","历史分类和型号对照已保存，实物模具与成本未合并","result",result);}catch(Exception failure){boolean unknown=!(failure instanceof BusinessException);job=Map.of("id",id,"status","failed","kind","historyReview","sourceHash",request.sha256(),"resultUnknown",unknown,"message",unknown?"历史处理结果尚未确认，请刷新处理状态核对，不要重复追加":failure.getMessage());org.slf4j.LoggerFactory.getLogger(getClass()).warn("Historical sales classification failed: {}",failure.getClass().getSimpleName());}finally{CurrentUserContext.clear();}});return job;
    }
    public synchronized Map<String,Object> sync(){read();access.requireAdministrator();require(!root.isBlank()&&!exporter.isBlank(),"CRM 只读连接尚未配置");if("running".equals(job.get("status")))return job;
        var actor=access.actor();String id=UUID.randomUUID().toString();job=Map.of("id",id,"status","running","message","正在读取 CRM 有效订单与历史明细");
        executor.submit(()->{CurrentUserContext.set(actor);try{var imported=fetchAndImport(actor);job=Map.of("id",id,"status","completed","message","CRM 订单已更新","result",imported);}catch(Exception e){job=Map.of("id",id,"status","failed","message","更新未完成，原数据保留。请核对服务器连接或来源数据后重试。");org.slf4j.LoggerFactory.getLogger(getClass()).warn("Mold sales read/import failed: {}",e.getClass().getSimpleName());}finally{CurrentUserContext.clear();}});return job;
    }
    public Map<String,Object> job(){read();access.requireAdministrator();return job;}
    private Map<String,Object> fetchAndImport(CurrentUser actor)throws Exception{
        Path folder=Path.of(root).toAbsolutePath().normalize();Files.createDirectories(folder.resolve("snapshots"));
        var args=new ArrayList<>(List.of("python3",Path.of(exporter).toAbsolutePath().toString(),"--output",folder.resolve("snapshots").toString(),"--host",sshHost));
        if(!passwordFile.isBlank()){args.add("--password-file");args.add(passwordFile);}
        Path log=Files.createTempFile(folder,"read-",".log");
        try{var p=new ProcessBuilder(args).redirectError(log.toFile()).start();if(!p.waitFor(300,TimeUnit.SECONDS)){p.destroyForcibly();throw new IllegalStateException("CRM read timed out");}String response=new String(p.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);require(p.exitValue()==0,"CRM 只读取数失败");var result=json.readTree(response);String filename=result.path("file").asText();require(filename.matches("crm-sales-[A-Za-z0-9-]+\\.jsonl\\.gz"),"来源文件名无效");return importSnapshot(folder.resolve("snapshots").resolve(filename),folder.resolve("cost-baseline.json"),actor);
        }finally{Files.deleteIfExists(log);}
    }
    /** Whole replacement and mandatory audit commit together. Immutable compressed evidence stays local. */
    public Map<String,Object> importSnapshot(Path source,Path costFile,CurrentUser actor)throws Exception{
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source)));
        JsonNode costs=json.readTree(costFile.toFile());validateCosts(costs);
        return tx.execute(t->{jdbc.execute("select pg_advisory_xact_lock(202610161830)");
            var duplicate=jdbc.queryForList("select snapshot_id,source_summary::text as summary,costs::text as costs from plm_mold_sales_snapshot where source_hash=?",hash);
            if(!duplicate.isEmpty()&&MoldSalesRules.QUANTITY_POLICY.equals(node(duplicate.get(0).get("summary")).path("quantityPolicy").asText())&&jdbc.queryForObject("select count(*) from plm_mold_sales_line where snapshot_id<>?",Long.class,duplicate.get(0).get("snapshot_id"))==0){
                var prior=duplicate.get(0);if(node(prior.get("costs")).equals(costs))return Map.of("snapshotId",prior.get("snapshot_id"),"alreadyImported",true,"costsChanged",false);
                jdbc.update("update plm_mold_sales_snapshot set costs=?::jsonb where snapshot_id=?",encode(costs),prior.get("snapshot_id"));
                audit.logRequired(OperationLogCreateCommand.builder().action("MOLD_SALES_RECALCULATE").businessType("MOLD_SALES").businessId(prior.get("snapshot_id").toString()).detailJson(encode(Map.of("sourceHash",hash,"previousCosts",node(prior.get("costs")),"costs",costs,"ordersUnchanged",true))).build(),actor);
                return Map.of("snapshotId",prior.get("snapshot_id"),"alreadyImported",true,"costsChanged",true);
            }
            var profiles=finishedProfiles();UUID id=duplicate.isEmpty()?UUID.randomUUID():(UUID)duplicate.get(0).get("snapshot_id");long seen=0,stored=0;JsonNode header=null,footer=null;var counts=new TreeMap<String,BigDecimal>();var rows=new ArrayList<Map<String,Object>>();
            try(var stream=new BufferedReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(source)),java.nio.charset.StandardCharsets.UTF_8))){
                String line;while((line=stream.readLine())!=null){var r=json.readTree(line);String kind=r.path("kind").asText();
                    if(kind.equals("header")){require(header==null&&seen==0,"来源头重复");header=r;require(r.path("format").asInt()==1&&r.path("organizations").asInt()==1&&!r.path("organization").asText().isBlank(),"来源组织或格式无效");if(duplicate.isEmpty())jdbc.update("insert into plm_mold_sales_snapshot(snapshot_id,source_hash,source_file,organization_id,captured_at,row_count,costs,source_summary,created_by) values(?,?,?,?,?,?,?::jsonb,'{}',?)",id,hash,source.getFileName().toString(),r.path("organization").asText(),OffsetDateTime.parse(r.path("capturedAt").asText()),r.path("rows").asLong(),encode(costs),actor.username());jdbc.execute("delete from plm_mold_sales_line");}
                    else if(kind.equals("line")){require(header!=null&&footer==null,"来源顺序错误");var businessDate=LocalDate.parse(r.path("businessDate").asText());require("2025-01-01".equals(header.path("periodStart").asText())&&!LocalDate.parse(header.path("periodEnd").asText()).isAfter(LocalDate.now(ZoneId.of("Asia/Shanghai")))&&!businessDate.isBefore(LocalDate.of(2025,1,1))&&!businessDate.isAfter(LocalDate.parse(header.path("periodEnd").asText())),"订单业务日期不在本次统计范围");seen++;var fact=MoldSalesRules.identity(r,profiles);counts.merge(fact.eligibility(),fact.quantity(),BigDecimal::add);
                        if(fact.style()==null)continue;
                        rows.add(new LinkedHashMap<>(Map.ofEntries(Map.entry("source_id",r.path("id").asText()),Map.entry("order_id",r.path("orderId").asText()),Map.entry("order_no",r.path("orderNo").asText()),Map.entry("order_status",r.path("status").asText()),Map.entry("item_code",r.path("code").asText()),Map.entry("style_code",fact.style()),Map.entry("phone_code",fact.phone()),Map.entry("quantity",fact.quantity()),Map.entry("eligibility",fact.eligibility()),Map.entry("source_data",r))));stored++;
                        if(rows.size()>=1000){writeRows(id,rows);rows.clear();}
                    }else if(kind.equals("footer")){require(footer==null,"来源尾重复");footer=r;}else throw new IllegalStateException("Unknown source record");
                }
                require(header!=null&&footer!=null&&footer.path("complete").asBoolean()&&seen==header.path("rows").asLong(-1)&&seen==footer.path("rows").asLong(-1),"来源不完整，本批不写入");
                if(!rows.isEmpty())writeRows(id,rows);
                Map<String,Object> summary=Map.of("readRows",seen,"finishedCodeRows",stored,"quantitiesByEligibility",counts,"remoteWrites",0,"periodStart",header.path("periodStart").asText(),"periodEnd",header.path("periodEnd").asText(),"dateMissingOrders",header.path("dateMissingOrders").asLong(),"quantityPolicy",MoldSalesRules.QUANTITY_POLICY);
                jdbc.update("update plm_mold_sales_snapshot set source_summary=?::jsonb,costs=?::jsonb where snapshot_id=?",encode(summary),encode(costs),id);
                audit.logRequired(OperationLogCreateCommand.builder().action(duplicate.isEmpty()?"MOLD_SALES_IMPORT":"MOLD_SALES_RECALCULATE").businessType("MOLD_SALES").businessId(id.toString()).detailJson(encode(Map.of("sourceHash",hash,"summary",summary,"previousSummary",duplicate.isEmpty()?Map.of():node(duplicate.get(0).get("summary")),"costSource",costs.path("sourceSha256").asText()))).build(),actor);
                return Map.of("snapshotId",id,"sourceHash",hash,"summary",summary);
            }catch(IOException e){throw new IllegalStateException("来源读取失败",e);}
        });
    }
    private void writeRows(UUID id,List<Map<String,Object>> rows){jdbc.update("insert into plm_mold_sales_line select x.source_id,?,x.order_id,x.order_no,x.order_status,x.item_code,x.style_code,x.phone_code,x.quantity,x.eligibility,x.source_data from jsonb_to_recordset(?::jsonb) as x(source_id text,order_id text,order_no text,order_status text,item_code text,style_code text,phone_code text,quantity numeric,eligibility text,source_data jsonb)",id,encode(rows));}
    private static void validateCosts(JsonNode data){require(data.path("rows").isArray()&&!data.path("sourceSha256").asText().isBlank(),"缺少成本依据");Set<String> styles=new HashSet<>();for(var r:data.path("rows")){require(r.path("styleCode").asText().matches("[A-Z]{2}")&&styles.add(r.path("styleCode").asText()),"成本款式代码无效或重复");BigDecimal sum=BigDecimal.ZERO;for(String k:List.of("moldManufacturing","freight","customs","fuel")){var n=new BigDecimal(r.path(k).asText());require(n.signum()>=0,"成本不能为负数");sum=sum.add(n);}require(sum.compareTo(new BigDecimal(r.path("landedTotal").asText()))==0,"成本分项合计不一致");require("CNY".equals(r.path("currency").asText()),"成本币种须保留原表人民币");require(new BigDecimal(r.path("referenceSellingPrice").asText()).signum()>=0&&new BigDecimal(r.path("referenceUnitProductionCost").asText()).signum()>=0,"参考售价及生产成本不能为负数");}}

    private Set<String> finishedProfiles(){
        return new HashSet<>(jdbc.queryForList("select distinct substring(sku.finished_product_code,2,6) from plm_product sku left join plm_product parent on parent.product_id=sku.parent_product_id and parent.deleted_flag=0 left join plm_product line on line.product_id=case when parent.product_type='product_line' then parent.product_id else parent.parent_product_id end and line.product_type='product_line' and line.deleted_flag=0 where sku.deleted_flag=0 and "+com.yuewei.plm.module.moldreference.PhoneProductEvidence.historicalSkuSql("sku")+" and sku.finished_product_code ~ '^N[A-Z]{2}[0-9]{8}[A-Z0-9]{2}$' and substring(sku.finished_product_code,2,2)=line.product_specific_code",String.class));
    }
    private List<Map<String,Object>> groups(MoldSalesReconciliation review){
        return groups(review,false);
    }
    private List<Map<String,Object>> groups(MoldSalesReconciliation review,boolean complete){
        String sql="select i.item_id as \"itemId\",i.style_name as \"styleName\",coalesce(nullif(i.mold_name,''),i.compatible_models) as \"modelName\",p.product_specific_code as \"styleCode\",coalesce(c.code_value,i.phone_model_code) as \"phoneCode\",i.product_line_id as \"productLineId\",i.pc_total as \"pcTotal\",i.tpu_total as \"tpuTotal\",i.finished_total as \"finishedTotal\",i.production_notes as \"productionNotes\",(select min(s.opened_date) from plm_collaboration_mold_set s where s.item_id=i.item_id and s.deleted_flag=0 and "+access.visibleSqlClause("mold","s.mold_set_id")+") as \"recordedOpenedDate\" from plm_collaboration_item i left join plm_product p on p.product_id=i.product_line_id and p.deleted_flag=0 left join plm_code_item c on c.code_item_id=i.phone_model_code_item_id where i.deleted_flag=0 and "+(complete?"true":access.visibleSqlClause("item","i.item_id"))+" order by i.item_id";
        var items=jdbc.queryForList(sql);var codes=new HashMap<Long,Set<String>>();
        // Only explicitly confirmed PC+TPU compatibility can extend a group to another phone code.
        var fits=jdbc.queryForList("select cs.item_id,c.code_value from plm_collaboration_phone_set_compatibility cs join plm_collaboration_mold_set s on s.mold_set_id=cs.mold_set_id and s.item_id=cs.item_id and s.deleted_flag=0 join plm_phone_model_member m on m.id=cs.phone_model_id join plm_code_item c on c.code_item_id=m.code_item_id where cs.pc_confirmed and cs.tpu_confirmed and nullif(btrim(cs.pc_evidence),'') is not null and nullif(btrim(cs.tpu_evidence),'') is not null");
        for(var r:fits)codes.computeIfAbsent(number(r.get("item_id")),k->new TreeSet<>()).add(text(r.get("code_value")));
        var physical=new HashMap<Long,Integer>();for(var r:jdbc.queryForList("select s.item_id,count(*) n from plm_collaboration_mold_set s join plm_collaboration_mold_part pc on pc.mold_set_id=s.mold_set_id and pc.part_type='PC' join plm_collaboration_mold_part tpu on tpu.mold_set_id=s.mold_set_id and tpu.part_type='TPU' where s.deleted_flag=0 and "+MoldCompatibilityPolicy.confirmedSql("pc")+" and "+MoldCompatibilityPolicy.confirmedSql("tpu")+" and "+(complete?"true":access.visibleSqlClause("mold","s.mold_set_id"))+" group by s.item_id"))physical.put(number(r.get("item_id")),((Number)r.get("n")).intValue());
        List<Map<String,Object>> out=new ArrayList<>();for(var item:items){long id=number(item.get("itemId"));Set<String> phones=new TreeSet<>(codes.getOrDefault(id,Set.of()));String base=text(item.get("phoneCode"));if(base.matches("[0-9]{4}"))phones.add(base);String style=text(item.get("styleCode"));
            List<Map<String,Object>> overlaps=out.stream().filter(g->text(g.get("styleCode")).equals(style)&&!Collections.disjoint(strings(g.get("phoneCodes")),phones)).toList();
            Map<String,Object> g=new LinkedHashMap<>();g.put("groupId",id);g.put("styleName",item.get("styleName"));g.put("styleCode",style);g.put("productLineId",item.get("productLineId"));Set<Long> ids=new TreeSet<>(Set.of(id));Set<String> names=new LinkedHashSet<>(Set.of(text(item.get("modelName"))));int count=physical.getOrDefault(id,0);
            var records=new ArrayList<Map<String,Object>>();records.add(item);for(var old:overlaps){records.addAll(records(old.get("productionRecords")));ids.addAll(longs(old.get("itemIds")));phones.addAll(strings(old.get("phoneCodes")));names.addAll(strings(old.get("modelNames")));count+=((Number)old.get("physicalSets")).intValue();}out.removeAll(overlaps);g.put("groupId",ids.iterator().next());g.put("itemIds",ids);g.put("phoneCodes",phones);g.put("modelNames",names);g.put("physicalSets",count);long primary=id;int best=physical.getOrDefault(id,0);for(var old:overlaps){int score=((Number)old.getOrDefault("primaryPhysicalSets",0)).intValue();if(score>best||score==best&&number(old.get("groupId"))<primary){best=score;primary=number(old.get("groupId"));}}g.put("groupId",primary);g.put("sourceGroupKey","item:"+primary);g.put("productKey","product:"+number(g.get("productLineId")));g.put("primaryPhysicalSets",best);g.put("productionRecords",records);out.add(g);
        }review.apply(out);return out;
    }
    @SuppressWarnings("unchecked") private static Collection<String> strings(Object v){return (Collection<String>)v;}
    @SuppressWarnings("unchecked") private static Collection<Long> longs(Object v){return (Collection<Long>)v;}
    private String where(Map<String,Object> g,List<Object> args){args.add(g.get("styleCode"));var codes=strings(g.get("phoneCodes"));if(codes.isEmpty())return "false";args.addAll(codes);return "style_code=? and phone_code in ("+String.join(",",Collections.nCopies(codes.size(),"?"))+")";}
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> records(Object value){return (List<Map<String,Object>>)value;}
    private Map<String,Object> completeGroup(long itemId){
        access.requireItem(itemId);
        var group=groups(MoldSalesReconciliation.load(jdbc,json),true).stream().filter(g->longs(g.get("itemIds")).contains(itemId)).findFirst().orElseThrow(()->new BusinessException(40103,"对象不存在或未获授权"));
        for(long id:longs(group.get("itemIds")))access.requireItem(id);return group;
    }
    private JsonNode costBasis(Map<String,Object> group,Map<String,Object> snapshot){
        if(snapshot==null)return null;JsonNode result=null;for(var row:node(snapshot.get("costs")).path("rows"))if(row.path("styleCode").asText().equals(group.get("styleCode")))result=row;return result;
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> cost(long itemId){
        read();access.requireFinanceRead();return costSnapshot(completeGroup(itemId));
    }
    private Map<String,Object> costSnapshot(Map<String,Object> group){
        var match=actualCosts.find(group,actualCosts.rows());require(!match.conflict(),"共模归属已变化或存在多份实际成本，请先核对原成本记录，不能自动合并");
        var out=new LinkedHashMap<String,Object>();out.put("groupId",group.get("groupId"));out.put("itemIds",group.get("itemIds"));out.put("styleName",group.get("styleName"));out.put("modelNames",group.get("modelNames"));out.put("version",match.row()==null?0:match.row().get("version"));out.put("groupToken",MoldActualCostStore.token(group));out.put("canWrite",access.hasPermission("finance:write"));out.put("actual",actualCosts.actual(match.row()));
        var basis=costBasis(group,latest());out.put("budget",basis==null?null:MoldSalesRules.costs(basis,BigDecimal.ZERO,Math.max(1,((Number)group.get("physicalSets")).intValue())));out.put("history",actualCosts.history(match.row()));return out;
    }
    public Map<String,Object> updateCost(long itemId,MoldActualCostStore.Update update){
        read();access.requireFinanceRead();access.requireFinanceWrite();
        try{return tx.execute(ignored->{
            // Group membership can be edited through several existing screens. A short shared-table
            // lease blocks those writes only for this transaction; no numeric-ID guess or partial group.
            jdbc.execute("set local lock_timeout='3s'");
            jdbc.execute("set local statement_timeout='10s'");
            jdbc.execute("lock table plm_collaboration_item,plm_collaboration_mold_set,plm_collaboration_mold_part,plm_collaboration_phone_set_compatibility,plm_phone_model_member,plm_code_item,plm_product,plm_mold_sales_reconciliation,plm_mold_code_source,plm_mold_sales_line in share mode");
            var group=completeGroup(itemId);actualCosts.save(group,update,access.actor());return costSnapshot(group);
        });}catch(org.springframework.dao.DataAccessException failure){
            for(Throwable cause=failure;cause!=null;cause=cause.getCause())if(cause instanceof java.sql.SQLException sql){
                if("55P03".equals(sql.getSQLState()))throw new BusinessException(40001,"相关资料正在修改，请稍后刷新并重新核对成本");
                if("57014".equals(sql.getSQLState()))throw new BusinessException(40001,"成本保存超时，本次未保存，请刷新后重试");
            }throw failure;
        }
    }
    private static BigDecimal decimal(Object value){return value==null?null:new BigDecimal(value.toString());}
    private static Map<String,Object> sources(BigDecimal eccount,BigDecimal crm){return Map.of("eccount",eccount,"crm",crm);}
    private static Map<String,Object> sourceTotals(List<Map<String,Object>> rows){BigDecimal eccount=BigDecimal.ZERO,crm=BigDecimal.ZERO;for(var row:rows)if(row.get("sourceBreakdown") instanceof Map<?,?> b){eccount=eccount.add(decimal(b.get("eccount")));crm=crm.add(decimal(b.get("crm")));}return sources(eccount,crm);}
    private static void checkView(String view){require(Set.of("sales","production").contains(view),"统计视图无效");}
    /** One order/group calculation is shared by detail, paging and the matrix. */
    private record SalesResult(Map<String,Object> data,MoldSalesHistoricalProjection historical) {}
    private Map<String,Object> salesData(String view){return salesData(view,null).data();}
    private SalesResult salesData(String view,String detailKey){
        read();checkView(view);boolean production=view.equals("production");
        var review=MoldSalesReconciliation.load(jdbc,json);var existing=groups(review);var currentGroups=existing;
        MoldSalesHistoricalProjection.SourceScope scope=null;
        if(detailKey!=null){
            if(detailKey.startsWith("item:")){
                long itemId=Long.parseLong(detailKey.substring(5));var group=existing.stream().filter(g->longs(g.get("itemIds")).contains(itemId)).findFirst().orElseThrow(()->new BusinessException(40103,"对象不存在或未获授权"));currentGroups=List.of(group);scope=MoldSalesHistoricalProjection.SourceScope.physical(group);
            }else{scope=MoldSalesHistoricalProjection.SourceScope.history(detailKey);currentGroups=List.of();}
        }
        var factArgs=new ArrayList<Object>();String factScope=scope==null?"true":scope.sql("plm_mold_sales_fact",factArgs);
        var profiles=finishedProfiles();var eligible=MoldSalesRules.factEligibleSql(profiles);var snapshot=latest();var history=archive.metadata();boolean connected=snapshot!=null||Boolean.TRUE.equals(history.get("imported"));
        var firstOrderByKey=new HashMap<String,LocalDate>();var pendingByKey=new HashMap<String,BigDecimal>();var partialByKey=new HashMap<String,BigDecimal>();var totals=new HashMap<String,BigDecimal>();var eccountByKey=new HashMap<String,BigDecimal>();var crmByKey=new HashMap<String,BigDecimal>();var reviewByKey=new HashMap<String,Long>();
        if(connected&&!currentGroups.isEmpty())for(var r:jdbc.queryForList("select style_code,phone_code,min(business_date) as \"firstOrderDate\",sum(quantity) qty,sum(case when source_kind='eccount' then quantity else 0 end) eccount,sum(case when source_kind='crm' then quantity else 0 end) crm,sum(case when source_kind='crm' and "+MoldSalesRules.pendingDeliverySql()+" then quantity else 0 end) pending,sum(case when source_kind='crm' and "+MoldSalesRules.partialDeliverySql()+" then quantity else 0 end) partial from plm_mold_sales_fact where "+eligible+" and "+MoldSalesHistoricalProjection.PHYSICAL_FACT+" and "+factScope+" group by style_code,phone_code",factArgs.toArray())){String key=r.get("style_code")+":"+r.get("phone_code");firstOrderByKey.put(key,MoldSalesRules.date(r.get("firstOrderDate")));totals.put(key,(BigDecimal)r.get("qty"));pendingByKey.put(key,(BigDecimal)r.get("pending"));partialByKey.put(key,(BigDecimal)r.get("partial"));eccountByKey.put(key,(BigDecimal)r.get("eccount"));crmByKey.put(key,(BigDecimal)r.get("crm"));}
        if(connected&&!currentGroups.isEmpty())for(var r:jdbc.queryForList("select style_code,phone_code,count(*) n from plm_mold_sales_fact where "+MoldSalesArchiveStore.REVIEW_SQL+" and "+factScope+" group by style_code,phone_code",factArgs.toArray()))reviewByKey.put(r.get("style_code")+":"+r.get("phone_code"),number(r.get("n")));
        JsonNode costs=snapshot==null?json.createObjectNode():node(snapshot.get("costs"));
        var list=new ArrayList<Map<String,Object>>();BigDecimal total=BigDecimal.ZERO;Set<String> consumed=new HashSet<>();
        boolean canReadCost=!production&&access.hasPermission("finance:read");
        var historical=new MoldSalesHistoricalProjection(jdbc,json,access,existing,profiles,review.ignoredKeys(),scope);var completeByItem=new HashMap<Long,Map<String,Object>>();
        if(canReadCost&&!currentGroups.isEmpty())for(var group:groups(review,true))for(long id:longs(group.get("itemIds")))completeByItem.put(id,group);
        var actualRows=canReadCost&&!currentGroups.isEmpty()?actualCosts.rows():List.<Map<String,Object>>of();
        for(var g:currentGroups){
            LocalDate firstOrder=null;BigDecimal qty=BigDecimal.ZERO,pending=BigDecimal.ZERO,partial=BigDecimal.ZERO,eccount=BigDecimal.ZERO,crm=BigDecimal.ZERO;long reviewRows=0;
            for(String code:strings(g.get("phoneCodes"))){String key=g.get("styleCode")+":"+code;firstOrder=MoldSalesRules.earlier(firstOrder,firstOrderByKey.get(key));qty=qty.add(totals.getOrDefault(key,BigDecimal.ZERO));pending=pending.add(pendingByKey.getOrDefault(key,BigDecimal.ZERO));partial=partial.add(partialByKey.getOrDefault(key,BigDecimal.ZERO));eccount=eccount.add(eccountByKey.getOrDefault(key,BigDecimal.ZERO));crm=crm.add(crmByKey.getOrDefault(key,BigDecimal.ZERO));reviewRows+=reviewByKey.getOrDefault(key,0L);consumed.add(key);}
            var recorded=records(g.get("productionRecords")).stream().map(r->MoldSalesRules.date(r.get("recordedOpenedDate"))).filter(Objects::nonNull).min(LocalDate::compareTo).orElse(null);MoldSalesRules.openingDate(g,recorded,MoldSalesRules.earlier(firstOrder,historical.attributedFirstOrderDate(MoldSalesHistoricalProjection.groupKey(g))));
            var attributed=historical.attributedQuantity(MoldSalesHistoricalProjection.groupKey(g));qty=qty.add(attributed);var attributedSources=historical.attributedSources(MoldSalesHistoricalProjection.groupKey(g));eccount=eccount.add(attributedSources.get("eccount"));crm=crm.add(attributedSources.get("crm"));g.put("salesQuantity",connected?qty:null);if(connected)g.put("sourceBreakdown",sources(eccount,crm));g.put("historyReviewRows",reviewRows);g.put("unmapped",strings(g.get("phoneCodes")).isEmpty());
            BigDecimal pc=null,tpu=null,finished=null;
            for(var record:records(g.get("productionRecords"))){pc=MoldSalesRules.maximum(pc,decimal(record.get("pcTotal")));tpu=MoldSalesRules.maximum(tpu,decimal(record.get("tpuTotal")));finished=MoldSalesRules.maximum(finished,decimal(record.get("finishedTotal")));}
            var quantities=MoldSalesRules.productionTotals(!connected||Boolean.TRUE.equals(g.get("unmapped"))?null:qty,pc,tpu,finished);g.put("production",quantities);var delivery=new LinkedHashMap<String,Object>();delivery.put("pendingQuantity",!connected||Boolean.TRUE.equals(g.get("unmapped"))?null:pending);delivery.put("partialOrderQuantity",!connected||Boolean.TRUE.equals(g.get("unmapped"))?null:partial);delivery.put("historyDeliveryUnknown",eccount.signum()>0);g.put("delivery",delivery);
            JsonNode basis=null;for(var row:costs.path("rows"))if(row.path("styleCode").asText().equals(g.get("styleCode")))basis=row;
            g.put("hasCostBasis",basis!=null);
            Map<String,Object> actual=null;
            if(canReadCost){
                var full=completeByItem.get(number(g.get("groupId")));
                // A partially visible shared group never exposes a group amount or a writable cost.
                boolean whole=full!=null&&new TreeSet<>(longs(full.get("itemIds"))).equals(new TreeSet<>(longs(g.get("itemIds"))));
                g.put("canWriteCost",whole&&access.hasPermission("finance:write"));
                if(whole){var match=actualCosts.find(full,actualRows);g.put("costConflict",match.conflict());actual=actualCosts.actual(match.row());g.put("cost",MoldSalesRules.costs(basis,qty,Math.max(1,((Number)full.get("physicalSets")).intValue()),actual));}
            }
            g.put("hasActualCost",actual!=null);
            list.add(g);total=total.add(qty);
        }
        for(var g:historical.salesGroups()){
            if(detailKey!=null&&!detailKey.equals(g.get("sourceGroupKey")))continue;
            var qty=decimal(g.get("salesQuantity"));g.put("production",MoldSalesRules.productionTotals(qty,null,null,null));g.put("delivery",new LinkedHashMap<String,Object>(Map.of("historyDeliveryUnknown",true)));g.put("unmapped",false);g.put("hasActualCost",false);
            JsonNode basis=null;for(var row:costs.path("rows"))if(row.path("styleCode").asText().equals(g.get("styleCode")))basis=row;
            g.put("hasCostBasis",basis!=null);if(canReadCost&&basis!=null){var cost=MoldSalesRules.costs(basis,qty,1);cost.put("singleSetReference",true);cost.put("physicalSetsKnown",false);cost.put("costSets",null);g.put("cost",cost);}
            list.add(g);total=total.add(qty);
        }
        MoldSalesRules.sortRows(list,"quantityDesc",production);
        var result=new LinkedHashMap<String,Object>();result.put("content",list);result.put("totalSalesQuantity",connected?total:null);result.put("totalOrderQuantity",connected?total:null);if(connected)result.put("sourceBreakdown",sourceTotals(list));result.put("historyReviewRows",list.stream().mapToLong(r->number(r.get("historyReviewRows"))).sum());result.put("status",status(detailKey==null?historical:null));
        if(detailKey==null&&access.actor().allPermissions()){@SuppressWarnings("unchecked") var categories=(Map<String,Map<String,Object>>)historical.summary().get("categories");result.put("unassignedQuantity",categories.get("pending").get("quantity"));result.put("ignoredQuantity",categories.get("historical_reference").get("quantity"));}return new SalesResult(result,historical);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> page(int page,int size,String keyword,String filter,Long productLineId,Long itemId,String view){
        return pageResult(salesData(view),page,size,keyword,filter,productLineId,itemId,view,null,null,null,"phoneCodeDesc");
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> page(int page,int size,String keyword,String filter,Long productLineId,Long itemId,String view,Long brandId,List<Long> productLineIds){
        return page(page,size,keyword,filter,productLineId,itemId,view,brandId,productLineIds,null);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> page(int page,int size,String keyword,String filter,Long productLineId,Long itemId,String view,Long brandId,List<Long> productLineIds,List<String> productKeys){
        return page(page,size,keyword,filter,productLineId,itemId,view,brandId,productLineIds,productKeys,"phoneCodeDesc");
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> page(int page,int size,String keyword,String filter,Long productLineId,Long itemId,String view,Long brandId,List<Long> productLineIds,List<String> productKeys,String sort){
        checkSort(sort);var data=salesData(view);catalog().enrich(records(data.get("content")));return pageResult(data,page,size,keyword,filter,productLineId,itemId,view,brandId,productLineIds,productKeys,sort);
    }
    private Map<String,Object> pageResult(Map<String,Object> data,int page,int size,String keyword,String filter,Long productLineId,Long itemId,String view,Long brandId,List<Long> productLineIds,List<String> productKeys,String sort){
        checkPage(page,size,filter,view);
        var list=new ArrayList<>(records(data.get("content")).stream().filter(g->
            (productLineId==null||number(g.get("productLineId"))==productLineId)&&
            (productKeys==null?(productLineIds==null||productLineIds.contains(number(g.get("productLineId")))):productKeys.contains(MoldSalesHistoricalProjection.productKey(g)))&&
            (itemId==null||longs(g.get("itemIds")).contains(itemId))&&
            (brandId==null||MoldSalesMatrixProjection.ids(g.get("brandIds")).contains(brandId))&&
            MoldSalesMatrixProjection.matches(g,keyword,filter)).toList());MoldSalesRules.sortRows(list,sort,view.equals("production"));
        data.put("totalSalesQuantity",Boolean.TRUE.equals(((Map<?,?>)data.get("status")).get("connected"))?list.stream().map(g->decimal(g.get("salesQuantity"))).filter(Objects::nonNull).reduce(BigDecimal.ZERO,BigDecimal::add):null);
        data.put("totalOrderQuantity",data.get("totalSalesQuantity"));if(Boolean.TRUE.equals(((Map<?,?>)data.get("status")).get("connected")))data.put("sourceBreakdown",sourceTotals(list));data.put("historyReviewRows",list.stream().mapToLong(r->number(r.get("historyReviewRows"))).sum());return paginate(data,list,page,size);
    }
    private static void checkSort(String sort){require(Set.of("phoneCodeDesc","openedDateDesc","quantityDesc").contains(sort),"排序方式无效");}
    private static void checkPage(int page,int size,String filter,String view){
        checkView(view);require(page>0&&size>0&&size<=100,"分页参数无效");
        require((view.equals("production")?Set.of("all","withProduction","missingProduction","unmapped"):Set.of("all","withSales","missingCost","unmapped")).contains(filter),"筛选无效");
    }
    private static Map<String,Object> paginate(Map<String,Object> result,List<Map<String,Object>> rows,int page,int size){
        int from=(int)Math.min(rows.size(),((long)page-1)*size),to=(int)Math.min(rows.size(),(long)page*size);
        result.put("content",rows.subList(from,to));result.put("page",page);result.put("size",size);result.put("totalElements",rows.size());result.put("totalPages",(rows.size()+size-1)/size);return result;
    }
    private MoldSalesCatalogProjection catalog(){return new MoldSalesCatalogProjection(jdbc,access);}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> options(){
        read();var data=salesData("sales");var catalog=catalog();var groups=records(data.get("content"));catalog.enrich(groups);return catalog.options(groups);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> matrix(int page,int size,String keyword,String filter,Long brandId,List<Long> productLineIds,String view){
        return matrix(page,size,keyword,filter,brandId,productLineIds,view,null);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> matrix(int page,int size,String keyword,String filter,Long brandId,List<Long> productLineIds,String view,List<String> productKeys){
        return matrix(page,size,keyword,filter,brandId,productLineIds,view,productKeys,"phoneCodeDesc");
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> matrix(int page,int size,String keyword,String filter,Long brandId,List<Long> productLineIds,String view,List<String> productKeys,String sort){
        checkSort(sort);checkPage(page,size,filter,view);var data=salesData(view);var catalog=catalog();var groups=records(data.get("content"));catalog.enrich(groups);
        var rows=MoldSalesMatrixProjection.rows(groups,catalog.coverage(),catalog.phones(),catalog.productsIncluding(groups),productLineIds,brandId,keyword,filter,Boolean.TRUE.equals(((Map<?,?>)data.get("status")).get("connected")),productKeys);
        MoldSalesRules.sortRows(rows,sort,view.equals("production"));boolean connected=Boolean.TRUE.equals(((Map<?,?>)data.get("status")).get("connected"));var productTotals=MoldSalesMatrixProjection.productTotals(rows,connected);data.put("productTotals",productTotals);
        data.put("totalSalesQuantity",connected?productTotals.stream().map(total->decimal(total.get("salesQuantity"))).filter(Objects::nonNull).reduce(BigDecimal.ZERO,BigDecimal::add):null);data.put("totalOrderQuantity",data.get("totalSalesQuantity"));if(connected)data.put("sourceBreakdown",sourceTotals(productTotals));data.put("historyReviewRows",rows.stream().mapToLong(r->number(r.get("historyReviewRows"))).sum());return paginate(data,rows,page,size);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> detail(long itemId,int page,int size,String view){
        read();require(Set.of("sales","production").contains(view),"统计视图无效");access.requireItem(itemId);require(page>0&&size>0&&size<=100,"分页参数无效");
        var data=salesData(view,"item:"+itemId);var g=records(data.data().get("content")).get(0);var historical=data.historical();var profiles=finishedProfiles();var eligible=MoldSalesRules.factEligibleSql(profiles);var args=new ArrayList<Object>();String base=where(g,args);if(base.equals("false"))args.clear();String extra=historical.attributedSql(MoldSalesHistoricalProjection.groupKey(g),args);String where="(("+base+") and "+MoldSalesHistoricalProjection.PHYSICAL_FACT.replace("plm_mold_sales_fact.source_id","f.source_id")+") or ("+extra+")";
        long total=jdbc.queryForObject("select count(*) from plm_mold_sales_fact f where ("+where+")",Long.class,args.toArray());args.add(size);args.add((page-1)*size);
        var orders=jdbc.queryForList("select source_id as \"sourceId\",order_id as \"orderId\",order_no as \"orderNo\",order_status as \"orderStatus\",item_code as \"itemCode\",phone_code as \"phoneCode\",quantity,"+MoldSalesRules.factEligibilitySql(profiles)+" as eligibility,source_data->>'name' as name,source_data->>'spec' as specification,coalesce(source_data->'raw'->>'product','') as \"originalProductName\",source_data->>'date' as date,source_data->>'orderDate' as \"orderDate\",source_kind as source,case when source_kind='eccount' then '数据来自 eccount' else '数据来自 crm' end as \"sourceLabel\",company,unit,source_file as \"sourceFile\",source_sheet as \"sourceSheet\",source_row as \"sourceRow\",business_date as \"businessDate\" from plm_mold_sales_fact f where ("+where+") order by business_date desc nulls last,order_no,source_id limit ? offset ?",args.toArray());
        var summaryArgs=new ArrayList<>(args.subList(0,args.size()-2));var phoneSales=jdbc.queryForList("select phone_code as \"phoneCode\",sum(quantity) quantity from plm_mold_sales_fact f where "+eligible+" and ("+where+") group by phone_code order by phone_code",summaryArgs.toArray());
        for(var phone:phoneSales){var names=jdbc.queryForList("select code_name from plm_code_item where code_type='phone_model' and code_value=? and deleted_flag=0 order by code_item_id",String.class,phone.get("phoneCode"));phone.put("modelName",names.isEmpty()?"编码 "+phone.get("phoneCode"):names.get(0));
            for(var alias:(List<JsonNode>)g.getOrDefault("salesAliases",List.of()))if(alias.path("phoneCode").asText().equals(phone.get("phoneCode"))){phone.put("originalModelName",phone.get("modelName"));phone.put("modelName",alias.path("targetBasis").path("modelName").asText());phone.put("historicalAlias",true);phone.put("targetPhoneCode",alias.path("targetBasis").path("phoneCode").asText());phone.put("associationReason",alias.path("reason").asText("历史订单已核实，按目标档案统计；原始订单编码保留"));}
        }
        return Map.of("phoneSales",phoneSales,"group",g,"orders",orders,"totalElements",total,"page",page,"size",size,"status",data.data().get("status"));
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> sourceDetail(String key,int page,int size,String view){
        read();checkView(view);require(key!=null&&key.matches("[A-Za-z0-9:-]{1,200}")&&page>0&&size>0&&size<=100,"历史销量组或分页无效");
        if(key.startsWith("item:")){try{return detail(Long.parseLong(key.substring(5)),page,size,view);}catch(NumberFormatException invalid){throw new BusinessException(40001,"模具档案标识无效");}}
        var data=salesData(view,key);var all=records(data.data().get("content"));var group=all.stream().filter(g->key.equals(g.get("sourceGroupKey"))).findFirst().orElseThrow(()->new BusinessException(40103,"历史销量组不存在或未获授权"));catalog().enrich(List.of(group));
        var result=data.historical().orders(key,page,size);result.put("group",group);result.put("status",data.data().get("status"));result.put("phoneSales",List.of(Map.of("phoneCode",strings(group.get("phoneCodes")).iterator().next(),"modelName",String.join(" / ",strings(group.get("modelNames"))),"quantity",group.get("salesQuantity"))));return result;
    }
    public List<Map<String,Object>> history(){read();access.requireFinanceRead();access.requireAdministrator();return jdbc.queryForList("select snapshot_id as \"id\",captured_at as \"capturedAt\",row_count as \"sourceRows\",source_hash as \"sourceHash\",costs::text from plm_mold_sales_snapshot order by created_at desc limit 20").stream().peek(r->r.put("costs",node(r.get("costs")))).toList();}
    /** Three batch reads for the unresolved list; source IDs are evidence, never a fuzzy match. */
    private List<Map<String,Object>> repairEvidence(List<Map<String,Object>> rows){
        if(rows.isEmpty())return rows;
        var roots=new HashMap<String,List<Map<String,Object>>>();
        for(var r:jdbc.queryForList("select product_specific_code as code,product_id as id,product_name as name from plm_product where deleted_flag=0 and product_type='product_line'"))roots.computeIfAbsent(text(r.get("code")),k->new ArrayList<>()).add(r);
        var phones=new HashMap<String,List<Map<String,Object>>>();
        for(var r:jdbc.queryForList("select code_value as code,code_item_id as id,code_name as name from plm_code_item where deleted_flag=0 and code_type='phone_model'"))phones.computeIfAbsent(text(r.get("code")),k->new ArrayList<>()).add(r);
        var catalog=new HashMap<String,Map<String,Object>>();var evidenceArgs=new ArrayList<Object>();
        var keys=new LinkedHashSet<List<String>>();for(var row:rows)keys.add(List.of(text(row.get("styleCode")),text(row.get("phoneCode"))));for(var key:keys)evidenceArgs.addAll(key);
        String evidenceKeys=String.join(",",Collections.nCopies(keys.size(),"(?,?)"));
        // Enrich only the unresolved keys requested by this administrator, not the whole archive.
        for(var r:jdbc.queryForList("select l.style_code||':'||l.phone_code as key,array_to_json(array_agg(distinct l.item_code))::text as codes,array_to_json(array_agg(distinct p.product_id) filter(where p.product_id is not null))::text as skus,array_to_json(array_agg(distinct src.object_id) filter(where src.object_type='partner'))::text as partners,array_to_json(array_agg(distinct src.partner_spec_id) filter(where src.object_type='partner'))::text as specs from plm_mold_sales_fact l left join plm_product p on p.finished_product_code=l.item_code and p.deleted_flag=0 and "+com.yuewei.plm.module.moldreference.PhoneProductEvidence.historicalSkuSql("p")+" left join plm_master_data_source src on src.source_kind='product_sku' and src.source_code=l.item_code where (l.style_code,l.phone_code) in ("+evidenceKeys+") group by l.style_code,l.phone_code",evidenceArgs.toArray()))catalog.put(text(r.get("key")),r);
        for(var r:rows){
            String style=text(r.get("styleCode")),code=text(r.get("phoneCode"));var lines=roots.getOrDefault(style,List.of());var anchors=phones.getOrDefault(code,List.of());var evidence=catalog.get(style+":"+code);
            JsonNode skus=evidence==null||evidence.get("skus")==null?json.createArrayNode():node(evidence.get("skus"));JsonNode partners=evidence==null||evidence.get("partners")==null?json.createArrayNode():node(evidence.get("partners"));
            r.put("sourceItemCodes",evidence==null?json.createArrayNode():node(evidence.get("codes")));r.put("localSkuIds",skus);r.put("partnerSpecIds",evidence==null||evidence.get("specs")==null?json.createArrayNode():node(evidence.get("specs")));r.put("productLineIds",lines.stream().map(v->v.get("id")).toList());r.put("codeItemIds",anchors.stream().map(v->v.get("id")).toList());
            var missing=new ArrayList<String>();var entries=new ArrayList<Map<String,String>>();
            if(!partners.isEmpty()&&skus.isEmpty()){
                r.put("repairType","purchased");missing.add("CRM SKU 明确登记为外购产品，已有合作产品与规格关联；不应补造自有模具或部件产量");
                for(var partner:partners)entries.add(Map.of("label","查看合作产品及规格","path","/partner-products?id="+partner.asLong()));
            }else{
                r.put("repairType","self");
                if(lines.size()!=1){missing.add("款式 "+style+" 缺少唯一正式产品映射，维护一次后可整组重试");entries.add(Map.of("label","维护款式 "+style,"path","/products"));}
                if(anchors.size()!=1){missing.add("手机编码 "+code+" 缺少唯一标准编码组");entries.add(Map.of("label","核对手机编码 "+code,"path","/phone-model-codes?tab=groups&keyword="+code));}
                if(skus.isEmpty()){missing.add("订单成品编码未对应本地有效自有 SKU，需补原 SKU 与所属款式／手机 ID");entries.add(Map.of("label","补查原 SKU 资料","path","/products?archiveView=sku"));}
                else{missing.add("本地 SKU 已存在，但其手机身份、款式或现有档案归属需核实");for(var sku:skus)entries.add(Map.of("label","核对 SKU "+sku.asLong(),"path","/products/"+sku.asLong()+"/edit"));}
                missing.add("尚无可唯一确定的模具主档关联；有实物时在模具详情补原刻码、套号及来源依据");
            }
            r.put("missingEvidence",missing);r.put("repairEntries",entries);
        }
        return rows;
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public List<Map<String,Object>> unmatched(String view){
        read();checkView(view);access.requireAdministrator();var review=MoldSalesReconciliation.load(jdbc,json);var projection=new MoldSalesHistoricalProjection(jdbc,json,access,groups(review),finishedProfiles(),review.ignoredKeys());return repairEvidence(projection.pendingRows());
    }
}
