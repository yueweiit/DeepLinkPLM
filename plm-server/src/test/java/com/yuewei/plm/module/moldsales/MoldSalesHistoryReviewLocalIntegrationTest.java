package com.yuewei.plm.module.moldsales;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuewei.plm.common.security.CurrentUser;
import java.math.BigDecimal;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@EnabledIf("com.yuewei.plm.module.moldsales.MoldSalesLocalFixture#databaseConfigured")
class MoldSalesHistoryReviewLocalIntegrationTest extends MoldSalesLocalFixture {
    @TempDir Path root;
    String archiveHash="b".repeat(64);UUID archiveId=UUID.randomUUID();
    MoldSalesHistoryReviewStore store(){return new MoldSalesHistoryReviewStore(jdbc,json,audit,new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource()))),access);}
    @BeforeEach void history(){
        jdbc.update("insert into plm_code_item values(2,'0058','Historical Phone','phone_model',0)");
        jdbc.update("insert into plm_mold_code_source values(1,'MFA100058',false)");
        jdbc.update("insert into plm_master_data_source values('product_sku','NSF4030005904','partner',42,43)");
    }
    ObjectNode line(int id,String style,String phone,String code,String name,int quantity,String company){
        var l=json.createObjectNode();l.put("sourceId",String.format("%064x",id));l.put("styleCode",style);l.put("phoneCode",phone);l.put("itemCode",code);l.put("name",name);l.put("quantity",String.valueOf(quantity));l.put("businessDate","2024-01-01");l.put("company",company);l.put("unit","Pieza");return l;
    }
    void originals(List<ObjectNode> lines){
        jdbc.update("insert into plm_mold_sales_archive_batch(batch_id,source_hash,source_file,captured_at,row_count,detail_rows,master_rows,pre2025_detail_rows,pre2025_master_rows,manifest,source_summary,created_by)values(?,?,'qa.jsonl.gz',now(),?,?,0,?,0,'[]','{}','qa')",archiveId,archiveHash,lines.size(),lines.size(),lines.size());
        for(var l:lines){var data=l.deepCopy();data.put("sourceLabel","数据来自 eccount");data.putObject("source").put("file","original.xlsx").put("sheet","原订单").put("row",l.path("sourceId").asText().hashCode()&0xffff);
            jdbc.update("insert into plm_mold_sales_archive_line values(?,?,'detail',?,'2024-01-01','2024010100001','ORIGINAL',?,?,?,?,?,'完成','eligible',?::jsonb)",l.path("sourceId").asText(),archiveId,l.path("company").asText(),l.path("itemCode").asText(),l.path("styleCode").asText(),l.path("phoneCode").asText(),new BigDecimal(l.path("quantity").asText()),l.path("unit").asText(),jsonString(data));}
    }
    String jsonString(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}}
    ObjectNode policy(List<ObjectNode> lines){
        var p=json.createObjectNode();p.put("format",1);p.put("sourceHash",archiveHash);p.put("reportHash","c".repeat(64));p.put("recommendationHash","d".repeat(64));p.put("approvalText","隔离测试确认，仅本地");p.put("costPolicy","no_physical_mold_or_cost_transfer");p.put("originalValuesPolicy","immutable_archive");
        var groups=p.putArray("groups");var sums=new HashMap<String,Integer>();var counts=new HashMap<String,Integer>();
        for(var l:lines){String key=l.path("styleCode").asText()+":"+l.path("phoneCode").asText();sums.merge(key,l.path("quantity").asInt(),Integer::sum);counts.merge(key,1,Integer::sum);}
        var categories=new HashMap<String,Object>();
        for(var e:sums.entrySet()){
            String[] key=e.getKey().split(":");String kind=key[1].equals("0058")?"coded_history":key[0].equals("SF")?"purchased":"pending";
            var g=groups.addObject();g.put("styleCode",key[0]);g.put("phoneCode",key[1]);g.put("classification",kind);g.put("quantity",e.getValue().toString());g.put("lineCount",counts.get(e.getKey()));var evidence=g.putObject("evidence");evidence.put("styleName",key[0]);evidence.put("phoneName","Original phone");var codes=evidence.putArray("sourceMoldCodes");if(kind.equals("coded_history"))codes.add("MFA100058");categories.put(kind,Map.of("groups",1,"rows",counts.get(e.getKey()),"quantity",e.getValue()));
        }
        p.set("bindings",json.valueToTree(lines));p.set("summary",json.valueToTree(Map.of("groups",sums.size(),"rows",lines.size(),"quantity",String.valueOf(lines.stream().mapToInt(l->l.path("quantity").asInt()).sum()),"categories",categories)));return p;
    }
    List<ObjectNode> normal(){return new ArrayList<>(List.of(line(1,"FA","0058","NFA4030005801","Historical A",50,"YW"),line(2,"SF","0059","NSF4030005904","Purchased glass",70,"Lemos"),line(3,"FA","0060","NFA4030006001","Old Phone",80,"UV")));}
    MoldSalesHistoryReviewStore.ApplyRequest prepared(ObjectNode data)throws Exception{
        Files.createDirectories(root.resolve("history-policies"));byte[] bytes=json.writeValueAsBytes(data);String sha=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));String name="history-policy-"+sha+".json";Files.write(root.resolve("history-policies").resolve(name),bytes);return new MoldSalesHistoryReviewStore.ApplyRequest(name,sha,"本地隔离测试依据，不影响生产");
    }
    Map<String,Object> applied(ObjectNode p)throws Exception{return store().apply(root.toString(),prepared(p),Set.of(),actor);}
    String archiveBefore(){return jdbc.queryForObject("select jsonb_agg(to_jsonb(a)order by source_id)::text from plm_mold_sales_archive_line a",String.class);}
    void empty(){for(String table:List.of("plm_mold_sales_history_batch","plm_mold_sales_history_group","plm_mold_sales_history_line"))assertThat(jdbc.queryForObject("select count(*)from "+table,Long.class)).isZero();}
    @Test void classifiesExactSourceRowsButNeverChangesPhysicalGroupsCostsProductionOrRawOrders()throws Exception{
        var lines=normal();originals(lines);String original=archiveBefore();var before=first("sales","all");var p=policy(lines);var binding=(ObjectNode)p.path("bindings").get(2);binding.put("displayName","Existing Phone, display only");binding.put("displayPhoneCode","0057");binding.put("mappingReason","原名对照，不证明共模");binding.put("scope","display_only");
        var result=applied(p);assertThat(result).containsEntry("applied",true).containsEntry("alreadyApplied",false);assertThat(archiveBefore()).isEqualTo(original);assertThat(first("sales","all")).isEqualTo(before);assertThat(service.unmatched("sales")).isEmpty();assertThat(jdbc.queryForObject("select count(*)from plm_mold_sales_reconciliation",Long.class)).isZero();assertThat(jdbc.queryForObject("select count(*)from plm_collaboration_item",Long.class)).isEqualTo(2L);assertThat(jdbc.queryForObject("select count(*)from plm_collaboration_mold_set",Long.class)).isEqualTo(2L);
        var page=service.historyReviewPage(1,2,"","all");assertThat(page).containsEntry("totalElements",3).containsEntry("totalPages",2);assertThat((List<?>)page.get("content")).hasSize(2);assertThat(service.historyReviewPage(1,30,"0057","all")).containsEntry("totalElements",1);assertThat(service.historyReviewPage(1,30,"","purchased")).containsEntry("totalElements",1);
        var detail=store().detail("FA","0060",1,20);var order=(Map<?,?>)((List<?>)detail.get("orders")).get(0);assertThat(order.get("phoneCode")).isEqualTo("0060");assertThat(order.get("displayPhoneCode")).isEqualTo("0057");assertThat(order.get("originalName")).isEqualTo("Old Phone");assertThat(order.get("itemCode")).isEqualTo("NFA4030006001");assertThat(order.get("sourceFile")).isEqualTo("original.xlsx");verify(audit).logRequired(argThat(c->c.getAction().equals("MOLD_SALES_HISTORY_CLASSIFY")),eq(actor));
    }
    @Test void duplicateApprovalIsIdempotentEvenAfterSourceCatalogueLaterChanges()throws Exception{
        var lines=normal();originals(lines);var request=prepared(policy(lines));var s=store();s.apply(root.toString(),request,Set.of(),actor);jdbc.update("update plm_mold_code_source set source_disabled=true");assertThat(s.apply(root.toString(),request,Set.of(),actor)).containsEntry("alreadyApplied",true);assertThat(jdbc.queryForObject("select count(*)from plm_mold_sales_history_line",Long.class)).isEqualTo(3L);verify(audit,times(1)).logRequired(any(),eq(actor));
    }
    @Test void changedOriginalQuantityOrNameRejectsTheWholePlan()throws Exception{
        var lines=normal();originals(lines);var p=policy(lines);((ObjectNode)p.path("bindings").get(2)).put("quantity","81");assertThatThrownBy(()->applied(p)).hasMessageContaining("原订单数量已变化");empty();var changed=policy(lines);((ObjectNode)changed.path("bindings").get(2)).put("name","Wrong name");assertThatThrownBy(()->applied(changed)).hasMessageContaining("原订单或编码已变化");empty();verifyNoInteractions(audit);
    }
    @Test void missingOrPartialPurchasedMappingAndNewSelfSkuCannotBeSilentlySkipped()throws Exception{
        var lines=normal();originals(lines);var p=policy(lines);jdbc.update("delete from plm_master_data_source");assertThatThrownBy(()->applied(p)).hasMessageContaining("外购来源未完全对应");empty();jdbc.update("insert into plm_master_data_source values('product_sku','NSF4030005904','partner',42,43)");jdbc.update("insert into plm_product(product_id,product_type,parent_product_id,finished_product_code,color)values(3,'sku',1,'NSF4030005904','white')");assertThatThrownBy(()->applied(p)).hasMessageContaining("出现自有SKU");empty();
    }
    @Test void invalidFullMoldCodeOrNonUniquePhoneIdentityRejectsHistoricalCoding()throws Exception{
        var lines=normal();originals(lines);var p=policy(lines);jdbc.update("update plm_mold_code_source set source_disabled=true");assertThatThrownBy(()->applied(p)).hasMessageContaining("原模具编码已停用");empty();jdbc.update("update plm_mold_code_source set source_disabled=false");jdbc.update("insert into plm_code_item values(3,'0058','Duplicate','phone_model',0)");assertThatThrownBy(()->applied(p)).hasMessageContaining("不唯一");empty();
    }
    @Test void displayMappingCannotReferenceMissingIdentityAndPhysicalScopeIsRejected()throws Exception{
        var lines=normal();originals(lines);var p=policy(lines);var l=(ObjectNode)p.path("bindings").get(2);l.put("displayPhoneCode","9999");l.put("displayName","No model");l.put("mappingReason","Display only");l.put("scope","display_only");assertThatThrownBy(()->applied(p)).hasMessageContaining("型号对照目标已变化");empty();l.put("scope","physical_mold");assertThatThrownBy(()->applied(p)).hasMessageContaining("不得用于实物或成本");empty();
    }
    @Test void auditFailureRollsBackEveryThousandRowChunkWithoutChangingOriginalArchive()throws Exception{
        var lines=new ArrayList<ObjectNode>();for(int i=1;i<=1001;i++)lines.add(line(i,"FA","0060","NFA4030006001","Old Phone",1,"YW"));originals(lines);String original=archiveBefore();var p=policy(lines);doThrow(new IllegalStateException("audit unavailable")).when(audit).logRequired(any(),eq(actor));assertThatThrownBy(()->applied(p)).hasMessageContaining("audit unavailable");empty();assertThat(archiveBefore()).isEqualTo(original);
    }
    @Test void anotherPlanCannotReplaceApprovedClassificationAndGlobalPermissionsAreRequired()throws Exception{
        var lines=normal();originals(lines);var p=policy(lines);applied(p);p.put("approvalText","another approval");assertThatThrownBy(()->applied(p)).hasMessageContaining("不能覆盖");when(access.actor()).thenReturn(new CurrentUser(2L,"scoped","Scoped",false));doThrow(new com.yuewei.plm.common.exception.BusinessException(40103,"管理员权限不足")).when(access).requireAdministrator();assertThatThrownBy(()->service.historyReviewPage(1,30,"","all")).hasMessageContaining("管理员权限不足");
    }
    @Test void candidateConnectionsHaveATraceableNameAndDisappearAfterPoolClose() {
        String tag="plm-unified-history-20261028";
        var context=new org.springframework.boot.test.context.runner.ApplicationContextRunner()
            .withInitializer(new org.springframework.boot.test.context.ConfigDataApplicationContextInitializer())
            .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration.class))
            .withPropertyValues("spring.profiles.active=dev","spring.datasource.url="+System.getenv("PLM_SALES_ARCHIVE_TEST_DB_URL"),
                "spring.datasource.username=postgres","spring.datasource.password="+System.getenv("PLM_SALES_ARCHIVE_TEST_DB_PASSWORD"),
                "spring.datasource.hikari.maximum-pool-size=2");
        context.run(c->{
            assertThat(c.getStartupFailure()).isNull();
            var source=c.getBean(javax.sql.DataSource.class);
            var traced=new org.springframework.jdbc.core.JdbcTemplate(source);
            assertThat(traced.queryForObject("select current_database()",String.class)).isEqualTo("plm_sales_policy_qa");
            assertThat(traced.queryForObject("select current_setting('application_name')",String.class)).isEqualTo(tag);
            assertThat(traced.queryForObject("select count(*)from pg_stat_activity where datname=current_database() and application_name=?",Long.class,tag)).isPositive();
        });
        assertThat(jdbc.queryForObject("select count(*)from pg_stat_activity where datname=current_database() and application_name=?",Long.class,tag)).isZero();
    }

}
