package com.yuewei.plm.module.marketwatch;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuewei.plm.common.exception.BusinessException;
import com.yuewei.plm.module.moldreference.PhoneIdentityMatcher;
import com.yuewei.plm.module.moldreference.PhoneModelCoverageService;
import com.yuewei.plm.module.moldreference.PhoneModelReferenceService;
import com.yuewei.plm.module.operationlog.service.OperationLogCreateCommand;
import com.yuewei.plm.module.operationlog.service.OperationLogService;
import com.yuewei.plm.module.pilot.PilotAccessService;
import jakarta.servlet.http.HttpServletRequest;
import java.sql.Array;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.LinkedMultiValueMap;
import static com.yuewei.plm.module.marketwatch.MarketWatchTypes.*;

/** One read projection of the crawler, joined to existing PLM identities and business objects. */
@Service @RequiredArgsConstructor
public class MarketWatchWorkflowService {
    private final MarketWatchClient client;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final PhoneModelReferenceService references;
    private final PhoneModelCoverageService coverage;
    private final PilotAccessService access;
    private final OperationLogService audit;
    private final PlatformTransactionManager transactions;
    private final AtomicBoolean refreshing=new AtomicBoolean();

    private void read() {
        access.requireDomesticView();access.requirePermission("collaboration:view");
    }
    private boolean canMatch() {
        return access.actor().allPermissions()||jdbc.queryForObject("select count(*) from plm_collaboration_assignment where user_id=? and field_group in ('market','engineering')",Long.class,access.actorId())>0;
    }
    public Map<String,Object> refreshPrices(boolean start){read();if(!client.enabled())throw new BusinessException(40001,"市场来源同步已关闭，保留现有报价缓存");if(start&&!canMatch())throw new BusinessException(40103,"仅市场、工程职责可补采报价");return client.priceRefresh(start);}
    public Map<String,Object> refresh() {read();if(!client.enabled())throw new BusinessException(40001,"市场来源同步已关闭，仍可查看缓存及手动申请");return refreshInternal();}
    /** Reuses the current identity resolver against cached sources; never changes a manual decision. */
    public Map<String,Object> reconcile(HttpServletRequest request) {
        read();if(!canMatch())throw new BusinessException(40103,"只有市场、工程职责或管理员可以关联已有资料");
        return new TransactionTemplate(transactions).execute(tx->{
            jdbc.execute("select pg_advisory_xact_lock(66817400)");
            var brands=references.matchingBrands();var phones=references.matchingPhones();var changed=new ArrayList<Map<String,Object>>();var created=new ArrayList<Map<String,Object>>();
            if(access.canMaintainEngineeringData())for(var candidate:identityCandidates()){
                var member=references.createPhone(new com.yuewei.plm.module.moldreference.PhoneModelReferenceTypes.CreatePhone(number(candidate.get("brandId")),text(candidate,"modelName"),List.of(),number(candidate.get("codeItemId"))),request);
                created.add(Map.of("phoneModelId",member.get("phoneModelId"),"evidence",candidate));
            }
            if(!created.isEmpty())phones=references.matchingPhones();
            int matched=0,unmatched=0,ambiguous=0;
            for(var source:sources(true)) {
                if("manual".equals(source.get("matchOrigin")))continue;
                var match=PhoneIdentityMatcher.match(text(source,"brand"),text(source,"model"),brands,phones);
                if("matched".equals(match.state()))matched++;else if("ambiguous".equals(match.state()))ambiguous++;else unmatched++;
                if(Objects.equals(number(source.get("phoneModelId")),match.phoneModelId())&&match.state().equals(source.get("matchState")))continue;
                jdbc.update("update plm_market_watch_source_projection set phone_model_id=?,match_state=?,candidate_ids=?::bigint[],mapped_by=?,mapped_at=now() where source_kind=? and source_id=?",match.phoneModelId(),match.state(),array(match.candidateIds()),access.actor().username(),source.get("kind"),source.get("id"));
                var evidence=new LinkedHashMap<String,Object>();evidence.put("sourceKind",source.get("kind"));evidence.put("sourceId",source.get("id"));evidence.put("model",source.get("model"));evidence.put("beforePhoneModelId",source.get("phoneModelId"));evidence.put("phoneModelId",match.phoneModelId());evidence.put("state",match.state());changed.add(evidence);
            }
            if(!changed.isEmpty()||!created.isEmpty())audit.logRequired(OperationLogCreateCommand.builder().action("RECONCILE").businessType("MARKET_WATCH_PHONE").businessId("existing-data").detailJson(encode(Map.of("reason","按现有标准手机、别名及唯一编码锚定的产品型号重新关联；不推断逐套适配","changes",changed,"createdStandardPhones",created))).request(request).build(),access.actor());
            return Map.of("createdStandardPhones",created.size(),"changedSources",changed.size(),"matchedSources",matched,"unmatchedSources",unmatched,"ambiguousSources",ambiguous);
        });
    }
    /** Confirm a new uncoded identity only from independent official markets or a verified exact product offer. */
    public List<Map<String,Object>> identityCandidates(){
        read();if(!canMatch())throw new BusinessException(40103,"仅市场、工程职责可预检手机关联");
        var brands=references.matchingBrands();var phones=references.matchingPhones();var groups=new LinkedHashMap<String,List<Map<String,Object>>>();
        for(var source:sources(true)){
            if(source.get("phoneModelId")!=null||!PhoneIdentityMatcher.isSingleModel(text(source,"model")))continue;
            var found=PhoneIdentityMatcher.sourceBrands(text(source,"brand"),text(source,"model"),brands);if(found.size()!=1)continue;
            var brand=found.get(0);String key=PhoneIdentityMatcher.modelKey(text(source,"model"),brand);
            if(key.matches("(?i).*\\b[0-9]+\\s*w\\b.*")||text(source,"source_url").matches("(?i).*(charging[-_]?stand|charger|cargador|power[-_]?bank|earbuds|smart[-_]?watch|tablet).*"))continue;
            if(!com.yuewei.plm.module.moldreference.PhoneIdentityHistory.recognizedModel(key,brand))continue;
            groups.computeIfAbsent(brand.get("brandId")+":"+key.replace(" ",""),k->new ArrayList<>()).add(source);
        }
        var codes=jdbc.queryForList("select code_item_id,code_name,code_value from plm_code_item where code_type='phone_model' and deleted_flag=0 and status='enabled'");var candidates=new ArrayList<Map<String,Object>>();
        for(var sourceGroup:groups.values()){
            var source=sourceGroup.get(0);var brand=PhoneIdentityMatcher.sourceBrands(text(source,"brand"),text(source,"model"),brands).get(0);
            String key=PhoneIdentityMatcher.modelKey(text(source,"model"),brand);
            boolean existing=phones.stream().filter(p->Objects.equals(p.get("brandId"),brand.get("brandId"))).anyMatch(p->{var names=new ArrayList<String>();names.add(text(p,"modelName"));((List<?>)p.getOrDefault("aliases",List.of())).forEach(n->names.add(n.toString()));return names.stream().anyMatch(n->{String other=PhoneIdentityMatcher.modelKey(n,brand);return other.replace(" ","").equals(key.replace(" ",""))||(!other.matches(".*[45]g.*")||!key.matches(".*[45]g.*"))&&other.replaceAll(" [45]g$","").replace(" ","").equals(key.replaceAll(" [45]g$","").replace(" ",""));});});
            if(existing)continue;
            var official=sourceGroup.stream().filter(s->officialDomain(text(s,"source_url"),text(brand,"name"))).toList();
            boolean verified=sourceGroup.stream().anyMatch(s->s.get("price_evidence") instanceof Map<?,?> e&&Boolean.TRUE.equals(e.get("verified"))&&Objects.equals(e.get("model"),s.get("model"))&&officialDomain(Objects.toString(e.get("url"),""),text(brand,"name")));
            if(!verified&&official.stream().map(s->text(s,"country").isBlank()?text(s,"source_country"):text(s,"country")).filter(v->!v.isBlank()).distinct().count()<2)continue;
            var anchors=codes.stream().filter(c->{var b=PhoneIdentityMatcher.sourceBrands("",text(c,"code_name"),brands);return b.size()==1&&Objects.equals(b.get(0).get("brandId"),brand.get("brandId"))&&PhoneIdentityMatcher.isSingleModel(text(c,"code_name"))&&PhoneIdentityMatcher.identityKey(text(c,"code_name"),brand).equals(key.replace(" ",""));}).toList();
            if(anchors.size()>1)continue;
            var candidate=new LinkedHashMap<String,Object>();candidate.put("brandId",brand.get("brandId"));candidate.put("modelName",source.get("model"));candidate.put("codeItemId",anchors.isEmpty()?null:anchors.get(0).get("code_item_id"));candidate.put("phoneModelCode",anchors.isEmpty()?null:anchors.get(0).get("code_value"));candidate.put("evidence",sourceGroup.stream().map(s->Map.of("kind",s.get("kind"),"id",s.get("id"),"model",s.get("model"),"url",text(s,"source_url"),"country",text(s,"country"))).toList());candidates.add(candidate);
        }
        return candidates;
    }
    private static boolean officialDomain(String url,String brand){
        try{String host=java.net.URI.create(url).getHost();if(host==null)return false;String domain=switch(brand.toLowerCase(Locale.ROOT)){case "samsung"->"samsung.com";case "xiaomi"->"mi.com";case "honor"->"honor.com";case "huawei"->"huawei.com";case "oppo"->"oppo.com";case "vivo"->"vivo.com";case "apple"->"apple.com";default->"";};return !domain.isBlank()&&(host.equals(domain)||host.endsWith("."+domain));}catch(Exception e){return false;}
    }
    @Scheduled(fixedDelay=900000,initialDelay=15000)
    public void scheduledRefresh() {if(client.enabled())refreshInternal();}
    private Map<String,Object> refreshInternal() {
        if(!refreshing.compareAndSet(false,true))return syncStatus();
        try {
            jdbc.update("update plm_market_watch_projection_sync set last_attempt_at=now() where id=1");
            // Fetch both complete sets first. A partial upstream response must not expire cached sources.
            var snapshot=pullSnapshot();
            var brands=references.matchingBrands();var phones=references.matchingPhones();
            new TransactionTemplate(transactions).executeWithoutResult(tx->{
                jdbc.execute("select pg_advisory_xact_lock(66817400)");
                Map<String,Map<String,Object>> previous=new HashMap<>();for(var source:sources(false))previous.put(sourceKey(source),source);
                jdbc.update("update plm_market_watch_source_projection set active=false where active");
                for(var source:snapshot) {
                    String kind=Objects.toString(source.get("kind"));long id=((Number)source.get("id")).longValue();
                    var prior=previous.get(kind+":"+id);
                    boolean manual=prior!=null&&"manual".equals(prior.get("matchOrigin"))&&sameSourceIdentity(source,prior);
                    var match=PhoneIdentityMatcher.match(text(source,"brand"),text(source,"model"),brands,phones);
                    Long phoneId=manual?number(prior.get("phoneModelId")):match.phoneModelId();
                    List<Long> candidates=manual?List.of(phoneId):match.candidateIds();
                    String state=manual?"matched":match.state();
                    jdbc.update("""
                        insert into plm_market_watch_source_projection(source_kind,source_id,source_snapshot,phone_model_id,match_state,match_origin,candidate_ids,active,observed_at)
                        values(?,?,?::jsonb,?,?,?,?::bigint[],true,now())
                        on conflict(source_kind,source_id) do update set source_snapshot=excluded.source_snapshot,
                          phone_model_id=excluded.phone_model_id,match_state=excluded.match_state,match_origin=excluded.match_origin,
                          candidate_ids=excluded.candidate_ids,active=true,observed_at=now()
                        """,kind,id,encode(source),phoneId,state,manual?"manual":"automatic",array(candidates));
                }
                jdbc.update("update plm_market_watch_projection_sync set last_success_at=now(),last_error=null where id=1");
            });
        } catch(Exception failure) {
            jdbc.update("update plm_market_watch_projection_sync set last_error='来源读取失败；保留上次数据，请重试' where id=1");
        } finally {refreshing.set(false);}
        return syncStatus();
    }
    @SuppressWarnings("unchecked")
    private List<Map<String,Object>> pullSnapshot() {
        var result=new ArrayList<Map<String,Object>>();
        for(String kind:List.of("phone","candidate")) {
            long expected=-1;var unique=new LinkedHashMap<Long,Map<String,Object>>();
            for(int page=1;page<=1000;page++) {
                var query=new LinkedMultiValueMap<String,String>();query.add("kind",kind);query.add("page",String.valueOf(page));query.add("size","100");
                var response=client.items(query);
                if(!(response.get("content") instanceof List<?> rows)||!(response.get("totalElements") instanceof Number total))throw new BusinessException(50201,"来源分页内容不完整");
                if(expected<0)expected=total.longValue();else if(expected!=total.longValue())throw new BusinessException(50201,"来源正在更新，请重试");
                for(Object raw:rows) {
                    if(!(raw instanceof Map<?,?> values)||!(values.get("id") instanceof Number id))throw new BusinessException(50201,"来源记录身份不完整");
                    var source=new LinkedHashMap<String,Object>((Map<String,Object>)values);source.put("kind",kind);
                    if(unique.put(id.longValue(),source)!=null)throw new BusinessException(50201,"来源分页变化，请重试");
                }
                if(unique.size()>=expected){if(unique.size()!=expected)throw new BusinessException(50201,"来源分页不一致");break;}
                if(rows.isEmpty()||page==1000)throw new BusinessException(50201,"来源分页不完整");
            }
            result.addAll(unique.values());
        }
        return result;
    }
    private static boolean sameSourceIdentity(Map<String,Object> a,Map<String,Object> b) {
        return PhoneIdentityMatcher.normalize(text(a,"brand")).equals(PhoneIdentityMatcher.normalize(text(b,"brand")))
            &&PhoneIdentityMatcher.normalize(text(a,"model")).equals(PhoneIdentityMatcher.normalize(text(b,"model")));
    }
    private Map<String,Object> syncStatus() {
        var result=new LinkedHashMap<String,Object>();
        var row=jdbc.queryForMap("select last_success_at,last_attempt_at,last_error from plm_market_watch_projection_sync where id=1");
        result.put("lastSuccessAt",date(row.get("last_success_at")));result.put("lastAttemptAt",date(row.get("last_attempt_at")));
        result.put("lastError",row.get("last_error"));result.put("refreshing",refreshing.get());result.put("cached",row.get("last_success_at")!=null);return result;
    }
    @SuppressWarnings("unchecked")
    private List<Map<String,Object>> sources(boolean activeOnly) {
        return jdbc.query("select source_kind,source_id,source_snapshot::text,phone_model_id,match_state,match_origin,candidate_ids,active from plm_market_watch_source_projection"+(activeOnly?" where active":"")+" order by source_kind,source_id",(rs,n)->{
            Map<String,Object> source;
            try {source=json.readValue(rs.getString("source_snapshot"),new TypeReference<LinkedHashMap<String,Object>>(){});}catch(Exception failure){throw new IllegalStateException("来源缓存无法解析",failure);}
            source.put("kind",rs.getString("source_kind"));source.put("id",rs.getLong("source_id"));source.put("phoneModelId",rs.getObject("phone_model_id"));source.put("matchState",rs.getString("match_state"));source.put("matchOrigin",rs.getString("match_origin"));source.put("active",rs.getBoolean("active"));
            Array ids=rs.getArray("candidate_ids");source.put("candidateIds",ids==null?List.of():Arrays.stream((Object[])ids.getArray()).map(v->((Number)v).longValue()).toList());if(ids!=null)ids.free();return source;
        });
    }
    /** Full-set aggregation precedes every filter/count/page, so statuses never filter only a fetched page. */
    public Map<String,Object> page(ModelQuery query) {
        read();validate(query);var sync=syncStatus();if(client.enabled()&&sync.get("lastAttemptAt")==null){refreshInternal();sync=syncStatus();}
        var rows=models(false);
        if(query.getProductLineId()!=null){access.requireProduct(query.getProductLineId());for(var row:rows)row.put("selectedProductLineId",query.getProductLineId());}
        rows.forEach(MoldOpportunityProjection::apply);
        rows=rows.stream().filter(row->matches(row,query,false)).toList();
        var stats=statistics(rows);
        rows=rows.stream().filter(row->matches(row,query,true)).toList();
        long total=rows.size();int from=(int)Math.min((query.getPage()-1L)*query.getSize(),total),until=(int)Math.min(from+(long)query.getSize(),total);
        var result=new LinkedHashMap<String,Object>();result.put("content",rows.subList(from,until));result.put("page",query.getPage());result.put("size",query.getSize());result.put("totalElements",total);result.put("totalPages",(total+query.getSize()-1)/query.getSize());result.put("stats",stats);result.put("sync",sync);result.put("capabilities",Map.of("canMatch",canMatch(),"canMaintainReference",access.canMaintainEngineeringData()));return result;
    }
    public Map<String,Object> detail(String rowKey) {
        read();var row=models(true).stream().filter(r->rowKey.equals(r.get("rowKey"))).findFirst().orElseThrow(()->new BusinessException(40401,"手机或来源记录不存在，请刷新"));
        MoldOpportunityProjection.apply(row);if(row.get("phoneModelId")!=null)row.put("sourceSnapshot",snapshot(row));return row;
    }
    @SuppressWarnings("unchecked") public Map<String,Object> summary(ModelQuery query) {
        var page=page(query);var result=new LinkedHashMap<>((Map<String,Object>)page.get("stats"));
        result.put("stats",page.get("stats"));result.put("capabilities",page.get("capabilities"));result.put("sync",page.get("sync"));return result;
    }
    public Map<String,Object> sourceDetail(String kind,long id) {
        read();return sources(false).stream().filter(s->kind.equals(s.get("kind"))&&number(s.get("id"))==id).findFirst().orElseThrow(()->new BusinessException(40401,"来源记录不存在，请刷新"));
    }
    public Map<String,Object> draftContext(String rowKey) {
        var row=detail(rowKey);if(row.get("phoneModelId")==null)throw new BusinessException(40001,"请先匹配标准手机成员，再发起草稿");
        return snapshot(row);
    }
    public Map<String,Object> sourceContext(String kind,long sourceId,long expectedPhoneId) {
        read();var source=sources(false).stream().filter(s->kind.equals(s.get("kind"))&&number(s.get("id"))==sourceId).findFirst().orElseThrow(()->new BusinessException(40401,"来源记录不存在，请先刷新开模申请市场来源"));
        if(!Objects.equals(number(source.get("phoneModelId")),expectedPhoneId))throw new BusinessException(40901,"来源关联的标准手机已变化，请重新核对");
        return draftContext("phone:"+expectedPhoneId);
    }
    private Map<String,Object> snapshot(Map<String,Object> row) {
        return Map.of("rowKey",row.get("rowKey"),"phoneModelId",row.get("phoneModelId"),"sources",row.get("sources"),"capturedAt",OffsetDateTime.now().toString());
    }
    @SuppressWarnings("unchecked")
    private List<Map<String,Object>> models(boolean includeInactive) {
        Map<Long,Map<String,Object>> phones=new LinkedHashMap<>();references.matchingPhones().forEach(phone->{var visible=new LinkedHashMap<>(phone);visible.remove("matchingAliases");phones.put(number(phone.get("phoneModelId")),visible);});
        var grouped=new LinkedHashMap<String,Map<String,Object>>();var brands=references.matchingBrands();
        for(var source:sources(!includeInactive)) {
            Long phoneId=number(source.get("phoneModelId"));var phone=phones.get(phoneId);
            var candidates=((List<Long>)source.remove("candidateIds")).stream().map(phones::get).filter(Objects::nonNull).toList();source.put("candidatePhones",candidates);
            var possibleBrands=PhoneIdentityMatcher.sourceBrands(text(source,"brand"),text(source,"model"),brands);var sourceBrand=possibleBrands.size()==1?possibleBrands.get(0):null;
            String key=phone!=null?"phone:"+phoneId:sourceBrand!=null&&PhoneIdentityMatcher.isSingleModel(text(source,"model"))?"unmatched:"+UUID.nameUUIDFromBytes((sourceBrand.get("brandId")+":"+PhoneIdentityMatcher.identityKey(text(source,"model"),sourceBrand)).getBytes(java.nio.charset.StandardCharsets.UTF_8)):"source:"+sourceKey(source);
            var row=grouped.computeIfAbsent(key,k->{
                var value=new LinkedHashMap<String,Object>();value.put("rowKey",k);value.put("phoneModelId",phone==null?null:phoneId);value.put("brandId",phone==null?(sourceBrand==null?null:sourceBrand.get("brandId")):phone.get("brandId"));value.put("brandName",phone==null?(sourceBrand==null?source.get("brand"):sourceBrand.get("name")):phone.get("brandName"));value.put("modelName",phone==null?source.get("model"):phone.get("modelName"));value.put("phoneModelCode",phone==null?null:phone.get("phoneModelCode"));value.put("codeItemId",phone==null?null:phone.get("codeItemId"));value.put("aliases",phone==null?List.of():phone.get("aliases"));value.put("matchState",phone==null?source.get("matchState"):"matched");value.put("candidatePhones",candidates);value.put("sources",new ArrayList<Map<String,Object>>());return value;
            });
            ((List<Map<String,Object>>)row.get("sources")).add(source);
        }
        var salesProducts=jdbc.queryForList("select p.product_id as \"productLineId\",p.product_name as \"productName\" from plm_product p where p.deleted_flag=0 and p.product_type='product_line' and p.sales_status='on_sale' and ("+access.visibleSqlClause("product","p.product_id")+") order by p.product_id");
        var related=coverage.batch(grouped.values().stream().map(r->number(r.get("phoneModelId"))).filter(Objects::nonNull).distinct().toList());
        for(var row:grouped.values()) {
            row.put("salesProducts",salesProducts);var phone=phones.get(number(row.get("phoneModelId")));if(phone!=null)for(String key:List.of("moldPlanStatus","moldPlanReason","moldPlanUpdatedAt","moldPlanUpdatedBy"))row.put(key,phone.get(key));
            var sources=(List<Map<String,Object>>)row.get("sources");sources.sort(Comparator.comparing(s->text(s,"last_seen_at"),Comparator.reverseOrder()));
            row.put("sourceCount",sources.size());row.put("countries",sources.stream().map(s->!text(s,"country").isBlank()?text(s,"country"):text(s,"source_country")).filter(s->!s.isBlank()).distinct().sorted().toList());
            row.put("images",sources.stream().map(s->text(s,"image_url")).filter(MarketWatchWorkflowService::imageUrl).distinct().toList());
            row.put("lastSeenAt",sources.stream().map(s->text(s,"last_seen_at")).max(String::compareTo).orElse(null));
            var coverages=row.get("phoneModelId")==null?List.<Map<String,Object>>of():related.getOrDefault(number(row.get("phoneModelId")),List.of());row.put("coverages",coverages);
            row.put("coverageStates",coverages.isEmpty()?List.of(row.get("phoneModelId")==null?"needs_review":"uncovered"):coverages.stream().flatMap(c->((List<String>)c.get("states")).stream()).distinct().toList());
            row.put("applicationCount",coverages.stream().mapToInt(c->((List<?>)c.get("applications")).size()).sum());
            row.put("productCount",coverages.stream().mapToInt(c->((List<?>)c.getOrDefault("products",List.of())).size()).sum());MarketPhonePriceProjection.apply(row);
        }
        return grouped.values().stream().sorted(Comparator.<Map<String,Object>,String>comparing(r->text(r,"lastSeenAt")).reversed().thenComparing(r->text(r,"rowKey"))).toList();
    }
    @SuppressWarnings("unchecked")
    private boolean matches(Map<String,Object> row,ModelQuery q,boolean status) {
        if(status)return MoldOpportunityProjection.matches(row,q.getOpportunityView());
        if(q.getCoverageState()!=null&&!((List<String>)row.get("coverageStates")).contains(q.getCoverageState()))return false;
        if(q.getMatchState()!=null&&!q.getMatchState().equals(row.get("matchState")))return false;
        if(q.getBrandId()!=null&&!q.getBrandId().equals(number(row.get("brandId"))))return false;
        if(q.getCountry()!=null&&!((List<String>)row.get("countries")).contains(q.getCountry()))return false;
        if(q.getKeyword()!=null&&!q.getKeyword().isBlank())return normalizeSearch(row).contains(PhoneIdentityMatcher.normalize(q.getKeyword()));
        return true;
    }
    @SuppressWarnings("unchecked")
    private String normalizeSearch(Map<String,Object> row) {
        var values=new ArrayList<String>();for(String field:List.of("brandName","modelName","phoneModelCode"))values.add(text(row,field));
        ((List<?>)row.getOrDefault("aliases",List.of())).forEach(alias->values.add(Objects.toString(alias)));
        for(var source:(List<Map<String,Object>>)row.get("sources"))for(String field:List.of("brand","model","source_name"))values.add(text(source,field));
        for(var group:(List<Map<String,Object>>)row.get("coverages")) {
            for(var product:(List<Map<String,Object>>)group.getOrDefault("products",List.of()))for(String field:List.of("productName","productCode","model","phoneModelCode"))values.add(text(product,field));
            values.add(text(group,"productLineName"));for(var mold:(List<Map<String,Object>>)group.get("molds"))for(String field:List.of("moldName","engravedCode","pcCode","tpuCode"))values.add(text(mold,field));
        }
        return PhoneIdentityMatcher.normalize(String.join(" ",values));
    }
    private Map<String,Object> statistics(List<Map<String,Object>> rows) {
        var result=new LinkedHashMap<String,Object>();result.put("total",rows.size());
        for(String state:List.of("matched","unmatched","ambiguous"))result.put(state,rows.stream().filter(r->state.equals(r.get("matchState"))).count());
        var states=new LinkedHashMap<String,Long>();for(String state:PhoneModelCoverageService.STATES)states.put(state,rows.stream().filter(r->((List<?>)r.get("coverageStates")).contains(state)).count());result.put("states",states);var opportunities=new LinkedHashMap<String,Long>();for(String view:MoldOpportunityProjection.VIEWS)opportunities.put(view,rows.stream().filter(r->MoldOpportunityProjection.matches(r,view)).count());result.put("opportunities",opportunities);return result;
    }
    public Map<String,Object> moldPlan(long phoneId,MoldPlan input,HttpServletRequest request){
        read();var actor=access.requireEngineeringMaintenance();
        return new TransactionTemplate(transactions).execute(tx->{
            jdbc.queryForList("select id from plm_phone_model_member where id=? for update",phoneId);
            var row=detail("phone:"+phoneId);
            if(!Objects.equals(row.get("moldPlanStatus"),input.originalStatus()))throw new BusinessException(40901,"开模计划已变更，请刷新后重试");
            if("not_planned".equals(input.status())&&Boolean.TRUE.equals(row.get("hasActiveExecution")))throw new BusinessException(40901,"存在未处理的审批或制作，请先在工程处理入口暂停或终止执行");
            jdbc.update("update plm_phone_model_member set mold_plan_status=?,mold_plan_reason=?,mold_plan_updated_at=now(),mold_plan_updated_by=? where id=?",input.status(),input.reason().strip(),actor.username(),phoneId);
            audit.logRequired(OperationLogCreateCommand.builder().action("MOLD_PLAN_UPDATE").businessType("PHONE_MODEL").businessId(String.valueOf(phoneId)).detailJson(encode(Map.of("before",input.originalStatus(),"after",input.status(),"reason",input.reason()))).request(request).build(),actor);
            return detail("phone:"+phoneId);
        });
    }
    public Map<String,Object> link(String kind,long id,LinkPhoneModel command,HttpServletRequest request) {
        read();if(!canMatch())throw new BusinessException(40103,"只有市场、工程职责或管理员可以匹配手机");
        references.requireEnabledModels(List.of(command.phoneModelId()));
        return new TransactionTemplate(transactions).execute(tx->{
            jdbc.execute("select pg_advisory_xact_lock(66817400)");
            var rows=jdbc.queryForList("select phone_model_id,source_snapshot::text from plm_market_watch_source_projection where source_kind=? and source_id=? for update",kind,id);
            if(rows.isEmpty())throw new BusinessException(40401,"来源记录不存在，请刷新");var prior=rows.get(0);Long previous=number(prior.get("phone_model_id"));
            if(!Objects.equals(previous,command.originalPhoneModelId()))throw new BusinessException(40901,"手机匹配已被其他人修改，请刷新核对");
            if(previous!=null&&!previous.equals(command.phoneModelId())&&(command.reason()==null||command.reason().isBlank()))throw new BusinessException(40001,"更正已关联的标准手机须填写原因");
            jdbc.update("update plm_market_watch_source_projection set phone_model_id=?,match_state='matched',match_origin='manual',candidate_ids=?::bigint[],mapped_by=?,mapped_at=now() where source_kind=? and source_id=?",command.phoneModelId(),array(List.of(command.phoneModelId())),access.actor().username(),kind,id);
            var details=new LinkedHashMap<String,Object>();details.put("sourceKind",kind);details.put("sourceId",id);details.put("beforePhoneModelId",previous);details.put("phoneModelId",command.phoneModelId());details.put("reason",command.reason());details.put("sourceSnapshot",parse(text(prior,"source_snapshot")));
            audit.logRequired(OperationLogCreateCommand.builder().action("LINK_PHONE").businessType("MARKET_WATCH_PHONE").businessId(kind+":"+id).detailJson(encode(details)).request(request).build(),access.actor());
            var result=sources(false).stream().filter(s->kind.equals(s.get("kind"))&&number(s.get("id"))==id).findFirst().orElseThrow();result.remove("candidateIds");result.put("candidatePhones",references.options(List.of(command.phoneModelId())));return result;
        });
    }
    private void validate(ModelQuery q) {if(q.getOpportunityView()!=null&&!MoldOpportunityProjection.VIEWS.contains(q.getOpportunityView())&&!Set.of("needs_confirmation","applying").contains(q.getOpportunityView()))throw new BusinessException(40001,"开模视图无效");if(q.getCountry()!=null)q.setCountry(q.getCountry().strip().toUpperCase(Locale.ROOT));if(q.getCoverageState()!=null&&q.getCoverageState().isBlank())q.setCoverageState(null);if(q.getMatchState()!=null&&q.getMatchState().isBlank())q.setMatchState(null);if(q.getPage()<1||q.getSize()<1||q.getSize()>100)throw new BusinessException(40001,"分页参数无效");if(q.getCoverageState()!=null&&!PhoneModelCoverageService.STATES.contains(q.getCoverageState()))throw new BusinessException(40001,"覆盖状态无效");}
    private static String sourceKey(Map<String,Object> source) {return source.get("kind")+":"+source.get("id");}
    private static boolean imageUrl(String value) {try{var uri=java.net.URI.create(value);return Set.of("http","https").contains(uri.getScheme())&&uri.getHost()!=null;}catch(Exception ignored){return false;}}
    private static String text(Map<String,Object> row,String key) {return Objects.toString(row.get(key),"");}
    private static Long number(Object value) {return value==null?null:((Number)value).longValue();}
    private static String date(Object value) {return value==null?null:value.toString();}
    private static String array(List<Long> ids) {return "{"+String.join(",",ids.stream().map(String::valueOf).toList())+"}";}
    private String encode(Object value) {try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("监控投影序列化失败",e);}}
    private Object parse(String value) {try{return json.readValue(value,Object.class);}catch(Exception e){throw new IllegalStateException("监控投影解析失败",e);}}
}
