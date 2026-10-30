package com.yuewei.plm.module.partner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuewei.plm.common.security.CurrentUser;
import com.yuewei.plm.module.moldreference.PhoneModelReferenceService;
import com.yuewei.plm.module.operationlog.service.OperationLogService;
import com.yuewei.plm.module.pilot.PilotAccessService;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static com.yuewei.plm.module.partner.PartnerValues.*;

/** Runs only against a dedicated local QA database, never a configured application datasource. */
@EnabledIfEnvironmentVariable(named="PLM_SUPPLIER_RANGE_TEST_URL",matches="(?:jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/plm_selection_range_qa|jdbc:postgresql://127\\.0\\.0\\.1:15483/plm_foundation_crud_it\\?currentSchema=plm)")
class SupplierRangeServiceLocalIntegrationTest {
    JdbcTemplate jdbc,admin;TransactionTemplate tx;String schema;
    SupplierRangeService service;SupplierProfileService profiles;PilotAccessService access;PartnerOperationsAccess permissions;OperationLogService audit;
    @BeforeEach void setup() throws Exception {
        String url=System.getenv("PLM_SUPPLIER_RANGE_TEST_URL"),password=System.getenv("PLM_SUPPLIER_RANGE_TEST_PASSWORD");
        admin=new JdbcTemplate(new DriverManagerDataSource(url,"postgres",password));schema="range_qa_"+UUID.randomUUID().toString().replace("-","");admin.execute("create schema "+schema);
        var ds=new DriverManagerDataSource(url.split("\\?",2)[0]+"?currentSchema="+schema,"postgres",password);jdbc=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        jdbc.execute("""
          create table sys_user(user_id bigint primary key);
          insert into sys_user values(1);
          create table plm_supplier_profile(supplier_id bigserial,supplier_code varchar(64) primary key,supplier_name text,supplier_type text default 'company',status text default 'active',needs_review boolean default false,version bigint default 0,data jsonb default '{}',deleted_flag int default 0,updated_at timestamp default now(),updated_by text);
          insert into plm_supplier_profile(supplier_code,supplier_name,data) values('A','熊大','{"shortName":"原简称"}'),('B','另一供方','{}');
          create table plm_partner_event(event_id bigserial,supplier_code text,action text,data jsonb,request_id text,created_by text,actor_id bigint);
          create table plm_code_item(code_item_id bigint,code_value text,code_type text,deleted_flag int default 0);
          create table plm_phone_brand(id bigint,name text);
          create table plm_phone_model_member(id bigint,brand_id bigint,model_name text,aliases text[],code_item_id bigint);
          create table plm_attachment(attachment_id bigint primary key,file_name text,version_no text,content_type text,file_size bigint,deleted_flag int default 0);
          create table plm_partner_file(record_id bigint,attachment_id bigint,purpose text);
          create table plm_partner_file_job(kind text,source_attachment_id bigint);
          insert into plm_phone_brand values(1,'Apple');
          insert into plm_code_item values(1,'0185','phone_model',0),(2,'1211','phone_model',0);
          insert into plm_phone_model_member values(101,1,'iPhone exact',array['shared'],1),(102,1,'Other exact',array['shared'],2);
          """);
        try(var connection=ds.getConnection()){ScriptUtils.executeSqlScript(connection,new ClassPathResource("db/migration/V20261021_1210__supplier_ranges.sql"));}
        try(var input=getClass().getResourceAsStream("/db/migration/V20261026_1000__supplier_short_code_reservations.sql")){
            String sql=new String(Objects.requireNonNull(input).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);tx.executeWithoutResult(s->jdbc.execute(sql));
        }
        access=mock(PilotAccessService.class);when(access.visibleSqlClause(anyString(),anyString())).thenReturn("true");when(access.hasPermission("file:read")).thenReturn(true);when(access.actorId()).thenReturn(1L);when(access.actor()).thenReturn(new CurrentUser(1L,"engineer","工程",false));
        permissions=mock(PartnerOperationsAccess.class);when(permissions.engineering()).thenReturn(true);audit=mock(OperationLogService.class);
        var store=new PartnerStore(jdbc,new ObjectMapper(),access,audit);profiles=new SupplierProfileService(store);var phones=mock(PhoneModelReferenceService.class);
        when(phones.members(anyList(),anyBoolean())).thenAnswer(call->{Map<Long,Map<String,Object>> result=new LinkedHashMap<>();for(Object raw:(List<?>)call.getArgument(0)){long id=((Number)raw).longValue();require(Set.of(101L,102L).contains(id),"手机型号成员不存在");result.put(id,new LinkedHashMap<>(Map.of("phoneModelId",id,"phoneModelCode",id==101?"0185":"1211","modelName",id==101?"iPhone exact":"Other exact","status","enabled")));}return result;});
        service=new SupplierRangeService(store,profiles,permissions,phones);
    }
    @AfterEach void cleanup(){if(admin!=null&&schema!=null)admin.execute("drop schema "+schema+" cascade");}
    Map<String,Object> command(String request,List<Map<String,Object>> items){return new LinkedHashMap<>(Map.of("requestId",request,"reason","实际核对","items",items));}
    List<Map<String,Object>> rows(Map<String,Object> result){return maps(result.get("content"));}
    Map<String,Object> addColor(String supplier,String name){return rows(tx.execute(s->service.saveColors(supplier,command(UUID.randomUUID().toString(),List.of(Map.of("name",name)))))).get(0);}
    Map<String,Object> range(String name,String type,List<?> members){return new LinkedHashMap<>(Map.of("name",name,"type",type,"members",members,"status","active"));}
    long count(String table){return jdbc.queryForObject("select count(*) from "+table,Long.class);}
    @Test void commonPaletteInitialisationReusesGenericRecordsWithoutMergingScopedHistoryOrCreatingStandardCodes(){
        tx.execute(s->service.saveColors("A",command("scoped",List.of(Map.of("name","黑色","styleKey","original-style","configKey","default")))));
        var names=(List<?>)service.colors("A",Map.of("scope","general")).get("commonColors");assertThat(names).hasSize(24);List<Map<String,Object>> items=new ArrayList<>();for(Object name:names)items.add(Map.of("name",name));
        var first=command("palette",items);first.put("reuseExisting",true);tx.execute(s->service.saveColors("A",first));var repeated=command("palette-again",items);repeated.put("reuseExisting",true);tx.execute(s->service.saveColors("A",repeated));
        assertThat(count("plm_supplier_color")).isEqualTo(25);assertThat(count("plm_supplier_color_version")).isEqualTo(25);assertThat(count("plm_code_item")).isEqualTo(2);assertThat(service.colors("A",Map.of("scope","general")).get("totalElements")).isEqualTo(24L);
    }
    @Test void savedRowResolvesChosenColourIdsAndTypedNamesInTheExistingTransactionWithoutSupplyPairings(){
        var black=addColor("A","黑色");var store=new PartnerStore(jdbc,new ObjectMapper(),access,audit);var products=mock(PartnerProductService.class);when(products.selectedPhones(anyList(),anyList())).thenReturn(List.of());when(permissions.costs()).thenReturn(true);
        var selections=new PartnerSelectionService(store,permissions,profiles,products,mock(PartnerMaterialService.class));org.springframework.test.util.ReflectionTestUtils.setField(selections,"ranges",service);
        var input=new LinkedHashMap<String,Object>(Map.of("rowId",UUID.randomUUID().toString(),"name","测试壳","supplierCode","A","supplierColorIds",List.of(black.get("id")),"newSupplierColors",List.of("奶茶色")));
        var saved=tx.execute(s->selections.canonicalRow(9,Map.of(),input));assertThat(text(saved.get("color"))).isEqualTo("黑色、奶茶色");assertThat(PartnerSelectionService.scopeIds(saved.get("supplierColorIds"))).hasSize(2);assertThat(maps(saved.get("supplierColors"))).extracting(row->row.get("name")).containsExactly("黑色","奶茶色");assertThat(maps(saved.get("pairings"))).isEmpty();assertThat(maps(saved.get("selectedPairs"))).isEmpty();assertThat(saved).doesNotContainKey("newSupplierColors");assertThat(count("plm_code_item")).isEqualTo(2);
    }
    @Test void legacyColourTextEditClearsStaleIdsWithoutParsingProseAndRejectsForgedColourSnapshots(){
        var black=addColor("A","黑色");var store=new PartnerStore(jdbc,new ObjectMapper(),access,audit);var products=mock(PartnerProductService.class);when(products.selectedPhones(anyList(),anyList())).thenReturn(List.of());when(permissions.costs()).thenReturn(true);
        var selections=new PartnerSelectionService(store,permissions,profiles,products,mock(PartnerMaterialService.class));org.springframework.test.util.ReflectionTestUtils.setField(selections,"ranges",service);
        var saved=tx.execute(s->selections.canonicalRow(9,Map.of(),Map.of("rowId",UUID.randomUUID().toString(),"name","测试壳","supplierCode","A","supplierColorIds",List.of(black.get("id")))));
        var changed=tx.execute(s->selections.canonicalRow(9,saved,Map.of("color","白色／特殊原文")));assertThat(changed).containsEntry("color","白色／特殊原文");assertThat(PartnerSelectionService.scopeIds(changed.get("supplierColorIds"))).isEmpty();assertThat(maps(changed.get("supplierColors"))).isEmpty();assertThat(count("plm_supplier_color")).isEqualTo(1);
        assertThatThrownBy(()->tx.execute(s->selections.canonicalRow(9,saved,Map.of("supplierColors",List.of(Map.of("id",black.get("id"),"name","伪造名称")))))).hasMessageContaining("系统只读资料");
    }
    @Test void colourSelectionRejectsAnotherSupplierAndInactiveNewUseAndRollsBackAnyTypedColour(){
        var foreign=addColor("B","黑色");assertThatThrownBy(()->tx.execute(s->service.catalogColors("A",List.of(foreign.get("id")),List.of("新蓝"),List.of(),"",""))).hasMessageContaining("不属于");assertThat(jdbc.queryForObject("select count(*) from plm_supplier_color where supplier_code='A'",Long.class)).isZero();
        var old=addColor("A","旧蓝");jdbc.update("update plm_supplier_color set status='inactive' where color_id=?",old.get("id"));assertThatThrownBy(()->tx.execute(s->service.catalogColors("A",List.of(),List.of("旧蓝"),List.of(),"",""))).hasMessageContaining("停用");assertThat(count("plm_supplier_color")).isEqualTo(2);
        var retained=tx.execute(s->service.catalogColors("A",List.of(old.get("id")),List.of(),List.of(number(old.get("id"))),"",""));assertThat(retained).hasSize(1);
    }
    @Test void colorBatchIsIdempotentAndRequestIdCannotBeReusedForDifferentData(){var cmd=command("one",List.of(Map.of("name","冰透")));Map<String,Object> first=tx.execute(s->service.saveColors("A",cmd)),again=tx.execute(s->service.saveColors("A",cmd));assertThat(again).isEqualTo(first);assertThat(count("plm_supplier_color")).isEqualTo(1);assertThatThrownBy(()->tx.execute(s->service.saveColors("A",command("one",List.of(Map.of("name","黑")))))).hasMessageContaining("不同内容");}
    @Test void rangesKeepOldVersionsAndTwoSuppliersMayUseTheSameName(){var saved=rows(tx.execute(s->service.saveRanges("A",command("a",List.of(range("苹果系列","model",List.of(101))))))).get(0);tx.execute(s->service.saveRanges("B",command("b",List.of(range("苹果系列","model",List.of(102))))));var edit=new LinkedHashMap<>(saved);edit.put("members",List.of(102));tx.execute(s->service.saveRanges("A",command("edit",List.of(edit))));var old=service.resolveReferences("A",List.of(Map.of("type","model","reference",saved.get("code"),"version",1)),"","",false).get(0);assertThat(number(maps(old.get("members")).get(0).get("id"))).isEqualTo(101);assertThat(old).containsEntry("versionChanged",true);assertThat(count("plm_supplier_range_version")).isEqualTo(3);}
    @Test void phonePasteUsesExactFourDigitCodeAndPreservesAmbiguityWithoutCreatingPhones(){var p=service.preview("A",Map.of("type","model","values",List.of("0185","shared","not known","0185")));assertThat(maps(p.get("rows"))).extracting(r->r.get("state")).containsExactly("matched","ambiguous","missing","duplicate");assertThat(count("plm_phone_model_member")).isEqualTo(2);assertThat(p.toString()).contains("0185");}
    @Test void colorIdentityAndRangeNamesNeverLeakAcrossSuppliersOrApplicableScopes(){var a=addColor("A","黑");assertThatThrownBy(()->service.normalizeSelectedPairs("B",List.of(Map.of("phoneModelId",101,"colorId",a.get("id"))),false)).hasMessageContaining("不属于");tx.execute(s->service.saveRanges("A",command("r",List.of(range("常规色","color",List.of(a.get("id")))))));assertThatThrownBy(()->tx.execute(s->service.saveRanges("A",command("collision",List.of(range("常规色","color",List.of(a.get("id")))))))).hasMessageContaining("同名");}
    @Test void invalidSecondColorRollsBackEntireBatchAndNoPartialRequestOrAuditRemains(){assertThatThrownBy(()->tx.execute(s->service.saveColors("A",command("bad",List.of(Map.of("name","红"),Map.of("name","")))))).hasMessageContaining("不能为空");assertThat(count("plm_supplier_color")).isZero();assertThat(count("plm_supplier_color_version")).isZero();assertThat(count("plm_supplier_range_request")).isZero();assertThat(count("plm_partner_event")).isZero();}
    @Test void mandatoryAuditFailureRollsBackColorMetadataHistoryAndRequest(){doThrow(new IllegalStateException("audit unavailable")).when(audit).logRequired(any(),any());assertThatThrownBy(()->tx.execute(s->service.saveColors("A",command("audit",List.of(Map.of("name","蓝")))))).hasMessageContaining("audit unavailable");assertThat(count("plm_supplier_color")).isZero();assertThat(count("plm_supplier_color_version")).isZero();assertThat(count("plm_partner_event")).isZero();assertThat(count("plm_supplier_range_request")).isZero();}
    @Test void shortCodeDoesNotRenameSupplierAndCannotBeAssignedTwice(){var input=Map.<String,Object>of("shortCode","XD","version",0,"requestId","metadata","reason","核实映射");tx.execute(s->service.metadata("A",input));assertThat(service.colors("A",Map.of()).toString()).contains("shortCode=XD").contains("supplierCode=A");assertThat(jdbc.queryForObject("select data->>'shortName' from plm_supplier_profile where supplier_code='A'",String.class)).isEqualTo("原简称");assertThatThrownBy(()->tx.execute(s->service.metadata("B",input))).hasMessageContaining("已被使用");}
    Map<String,Object> shortCode(String supplier,String value,long version,String request){return tx.execute(s->service.metadata(supplier,Map.of("shortCode",value,"version",version,"requestId",request,"reason","核对真实简码")));}
    @Test void renamedAndClearedCodesRemainReservedAndTraceableWithoutChangingSupplierIdentity(){
        shortCode("A"," abc ",0,"first-code");var changed=shortCode("A","ABC2",1,"renamed-code");
        assertThat(changed).containsEntry("supplierCode","A").containsEntry("shortCode","ABC2");assertThat(changed.get("shortCodeHistory")).isEqualTo(List.of("ABC","ABC2"));
        shortCode("A","",2,"cleared-code");assertThatThrownBy(()->shortCode("B","abc",0,"try-old-code")).hasMessageContaining("历史简码保留");
        assertThat(number(service.metadata("A").get("version"))).isEqualTo(3);assertThat(count("plm_supplier_short_code_reservation")).isEqualTo(2);
        assertThat(shortCode("A","ABC",3,"restore-owned-code")).containsEntry("shortCode","ABC");
    }
    @Test void confirmedXdCannotBeChangedAndDisablingOrSoftDeletingNeverReleasesIt(){
        shortCode("A","XD",0,"confirmed-XD");assertThatThrownBy(()->shortCode("A","XD2",1,"rename-XD")).hasMessageContaining("不能清空");
        assertThatThrownBy(()->shortCode("A","",1,"clear-XD")).hasMessageContaining("不能清空");
        jdbc.update("update plm_supplier_profile set status='inactive',deleted_flag=1 where supplier_code='A'");
        assertThatThrownBy(()->shortCode("B","xD",0,"reuse-disabled-XD")).hasMessageContaining("已被使用");
        assertThat(jdbc.queryForObject("select short_code from plm_supplier_profile where supplier_code='A'",String.class)).isEqualTo("XD");
    }
    @Test void shortCodeAuditFailureRollsBackReservationProfileVersionEventsAndRequest(){
        doThrow(new IllegalStateException("audit unavailable")).when(audit).logRequired(any(),any());
        assertThatThrownBy(()->shortCode("A","NEW",0,"failed-audit-code")).hasMessageContaining("audit unavailable");
        assertThat(count("plm_supplier_short_code_reservation")).isZero();assertThat(count("plm_partner_event")).isZero();assertThat(count("plm_supplier_range_request")).isZero();
        assertThat(jdbc.queryForObject("select version from plm_supplier_profile where supplier_code='A'",Long.class)).isZero();
        assertThat(jdbc.queryForObject("select short_code from plm_supplier_profile where supplier_code='A'",String.class)).isEmpty();
    }
    @Test void newShortCodesAreValidatedWithoutArtificialLengthLimitAndReplayDoesNotReserveAgain(){
        String code="AB-"+"Z".repeat(40);var first=shortCode("A",code,0,"long-code");assertThat(shortCode("A",code,0,"long-code")).isEqualTo(first);
        assertThat(count("plm_supplier_short_code_reservation")).isEqualTo(1);assertThat(count("plm_partner_event")).isEqualTo(1);
        assertThatThrownBy(()->shortCode("B","BAD_CODE",0,"underscore")).hasMessageContaining("下划线");
        assertThatThrownBy(()->shortCode("B","中文",0,"chinese")).hasMessageContaining("中文");
    }
    @Test void twoSuppliersCannotConcurrentlyReserveSameCaseInsensitiveCode() throws Exception {
        var start=new CountDownLatch(1);var succeeded=new AtomicInteger();var conflicted=new AtomicInteger();var executor=Executors.newFixedThreadPool(2);
        try{List<Future<?>> tasks=new ArrayList<>();for(String supplier:List.of("A","B"))tasks.add(executor.submit(()->{try{start.await();shortCode(supplier,supplier.equals("A")?"SAME":"same",0,"concurrent-short-code");succeeded.incrementAndGet();}catch(Exception e){if(e.getMessage()!=null&&e.getMessage().contains("已被使用"))conflicted.incrementAndGet();else throw new RuntimeException(e);}}));start.countDown();for(var task:tasks)task.get(15,TimeUnit.SECONDS);}finally{executor.shutdownNow();}
        assertThat(succeeded.get()).isEqualTo(1);assertThat(conflicted.get()).isEqualTo(1);assertThat(count("plm_supplier_short_code_reservation")).isEqualTo(1);assertThat(count("plm_partner_event")).isEqualTo(1);
    }
    @Test void formerCodeIsRejectedAfterConcurrentOwnerRenameEvenWithoutACurrentValueCollision() throws Exception {
        shortCode("A","OLD",0,"reserve-old-code");
        var ownerUpdated=new CountDownLatch(1);var claimantReady=new CountDownLatch(1);var ownerCommitted=new CountDownLatch(1);var executor=Executors.newFixedThreadPool(2);
        try {
            Future<?> owner=executor.submit(()->{
                tx.executeWithoutResult(s->{
                    service.metadata("A",Map.of("shortCode","NEW","version",1,"requestId","rename-during-claim","reason","核实后更改简码"));
                    ownerUpdated.countDown();
                    try{assertThat(claimantReady.await(5,TimeUnit.SECONDS)).as("竞争申请者已就绪").isTrue();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}
                });
                ownerCommitted.countDown();
            });
            Future<?> claimant=executor.submit(()->{
                try {
                    assertThat(ownerUpdated.await(5,TimeUnit.SECONDS)).as("原持有人已更新但尚未提交").isTrue();claimantReady.countDown();
                    assertThat(ownerCommitted.await(5,TimeUnit.SECONDS)).as("改码事务已提交").isTrue();
                }catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}
                // No profile owns OLD now: the old current-value unique index cannot reject this claim.
                assertThat(jdbc.queryForObject("select count(*) from plm_supplier_profile where lower(short_code)='old'",Long.class)).isZero();
                assertThatThrownBy(()->shortCode("B","old",0,"claim-former-code")).hasMessageContaining("历史简码保留");
            });
            owner.get(15,TimeUnit.SECONDS);claimant.get(15,TimeUnit.SECONDS);
        }finally{executor.shutdownNow();}
        assertThat(service.metadata("A")).containsEntry("shortCode","NEW").containsEntry("version",2L);
        assertThat(service.metadata("B")).containsEntry("shortCode","").containsEntry("version",0L);
        assertThat(jdbc.queryForObject("select supplier_code from plm_supplier_short_code_reservation where short_code='OLD'",String.class)).isEqualTo("A");
        assertThat(count("plm_partner_event")).isEqualTo(2);assertThat(count("plm_supplier_range_request")).isEqualTo(2);
    }
    @Test void databaseGuardProtectsOtherWritersAndReservationsCannotBeRemoved(){
        jdbc.update("update plm_supplier_profile set short_code=' raw2 ' where supplier_code='A'");
        assertThat(jdbc.queryForObject("select short_code from plm_supplier_profile where supplier_code='A'",String.class)).isEqualTo("RAW2");
        assertThatThrownBy(()->jdbc.update("update plm_supplier_profile set short_code='RAW_3' where supplier_code='A'")).hasMessageContaining("下划线");
        assertThatThrownBy(()->jdbc.update("delete from plm_supplier_short_code_reservation where short_code='RAW2'")).hasMessageContaining("保留历史归属");
        assertThat(count("plm_supplier_short_code_reservation")).isEqualTo(1);
    }
    @Test void metadataHistoryDoesNotBypassObjectVisibilityOrWritePermission(){
        shortCode("A","AAA",0,"visible-code");when(access.visibleSqlClause("supplier_profile","p.supplier_id")).thenReturn("false");
        assertThatThrownBy(()->service.metadata("A")).hasMessageContaining("未获授权");when(access.visibleSqlClause("supplier_profile","p.supplier_id")).thenReturn("true");
        doThrow(invalid("没有工程权限")).when(permissions).engineer();assertThatThrownBy(()->shortCode("A","BBB",1,"denied-code")).hasMessageContaining("工程权限");assertThat(count("plm_supplier_short_code_reservation")).isEqualTo(1);
    }
    @Test void omittedShortCodeCannotAccidentallyClearAnExistingMapping(){
        shortCode("A","ABC",0,"initial-code");
        assertThatThrownBy(()->tx.execute(s->service.metadata("A",Map.of("version",1,"requestId","omitted-code","reason","字段缺失")))).hasMessageContaining("明确填写供应商简码");
        assertThat(service.metadata("A")).containsEntry("shortCode","ABC").containsEntry("version",1L);assertThat(count("plm_supplier_range_request")).isEqualTo(1);
    }
    @Test void supplierPageAndExistingDetailSnapshotExposeCurrentCodeAndSearchOldAliasesWithinScope(){
        shortCode("A","EARLY",0,"first-readable-code");shortCode("A","LATEST",1,"rename-readable-code");
        var oldCodePage=profiles.page(Map.of("keyword","early"));assertThat(number(oldCodePage.get("totalElements"))).isEqualTo(1);
        assertThat(rows(oldCodePage)).singleElement().satisfies(row->assertThat(row).containsEntry("supplierCode","A").containsEntry("shortCode","LATEST"));
        assertThat(rows(profiles.page(Map.of("keyword","latest")))).hasSize(1);
        var detail=profiles.decorate(List.of()).stream().filter(row->row.supplierCode().equals("A")).findFirst().orElseThrow();
        assertThat(detail.shortCode()).isEqualTo("LATEST");assertThat(new ObjectMapper().valueToTree(detail).get("shortCode").asText()).isEqualTo("LATEST");
        assertThat(rows(profiles.page(Map.of())).stream().filter(row->"B".equals(row.get("supplierCode"))).findFirst().orElseThrow()).containsEntry("shortCode","");
        when(access.visibleSqlClause("supplier_profile","p.supplier_id")).thenReturn("p.supplier_code='B'");
        assertThat(number(profiles.page(Map.of("keyword","EARLY")).get("totalElements"))).isZero();
    }
    @Test void twoConcurrentEditsWithOneBaseVersionCannotOverwriteEachOther() throws Exception {
        var c=addColor("A","原色");var start=new CountDownLatch(1);var succeeded=new AtomicInteger();var conflicted=new AtomicInteger();var executor=Executors.newFixedThreadPool(2);
        try {List<Future<?>> tasks=new ArrayList<>();for(int i=0;i<2;i++){int n=i;tasks.add(executor.submit(()->{try{start.await();var edit=new LinkedHashMap<>(c);edit.put("name","改色"+n);tx.execute(s->service.saveColors("A",command("concurrent"+n,List.of(edit))));succeeded.incrementAndGet();}catch(Exception e){if(e.getMessage()!=null&&e.getMessage().contains("已变化"))conflicted.incrementAndGet();else throw new RuntimeException(e);}}));}start.countDown();for(var future:tasks)future.get(15,TimeUnit.SECONDS);}
        finally {executor.shutdownNow();}assertThat(succeeded.get()).isEqualTo(1);assertThat(conflicted.get()).isEqualTo(1);assertThat(count("plm_supplier_color_version")).isEqualTo(2);
    }
    @Test void writePermissionFailureHappensBeforeSupplierOrColorReads(){doThrow(invalid("没有工程权限")).when(permissions).engineer();assertThatThrownBy(()->tx.execute(s->service.saveColors("A",command("deny",List.of(Map.of("name","红")))))).hasMessageContaining("工程权限");assertThat(count("plm_supplier_color")).isZero();}
    @Test void unconfirmedPasteRowsSurviveDraftAndCannotBeActivated(){var draft=range("待核对","model",List.of(101));draft.put("status","draft");draft.put("pendingValues",List.of(Map.of("raw","unknown exact","state","missing")));var saved=rows(tx.execute(s->service.saveRanges("A",command("draft",List.of(draft))))).get(0);assertThat(saved.toString()).contains("unknown exact");var edit=new LinkedHashMap<>(saved);edit.put("status","active");assertThatThrownBy(()->tx.execute(s->service.saveRanges("A",command("activate",List.of(edit))))).hasMessageContaining("未核实");assertThat(count("plm_supplier_range_version")).isEqualTo(1);}
    @Test void sameRangeNameCanBeScopedButCannotApplyToAnotherStyle(){var left=range("透明组","model",List.of(101));left.put("styleKey","left");var right=range("透明组","model",List.of(102));right.put("styleKey","right");tx.execute(s->service.saveRanges("A",command("scopes",List.of(left,right))));var resolved=service.resolveReferences("A",List.of(Map.of("type","model","reference","透明组")),"left","",false);assertThat(number(maps(resolved.get(0).get("members")).get(0).get("id"))).isEqualTo(101);assertThatThrownBy(()->service.resolveReferences("A",List.of(Map.of("type","model","reference","透明组")),"third","",false)).hasMessageContaining("不适用");}
    @Test void explicitSupplierColorCodeSeparatesSameNamedColorsAndNoGlobalColorIsCreated(){var a=new LinkedHashMap<String,Object>(Map.of("name","黑","supplierColorCode","BK-A"));var b=new LinkedHashMap<String,Object>(Map.of("name","黑","supplierColorCode","BK-B"));tx.execute(s->service.saveColors("A",command("color-codes",List.of(a,b))));var preview=service.previewColors("A",Map.of("items",List.of(b)));assertThat(maps(preview.get("rows")).get(0).get("state")).isEqualTo("matched");assertThat(maps(maps(preview.get("rows")).get(0).get("candidates"))).hasSize(1);assertThat(count("plm_code_item")).isEqualTo(2);}
    @Test void changingSupplierColorRequiresRangeReconfirmationWhileHistoricalSnapshotRemains(){var color=addColor("A","原黑");var r=rows(tx.execute(s->service.saveRanges("A",command("original-range",List.of(range("黑色组","color",List.of(color.get("id")))))))).get(0);var edit=new LinkedHashMap<>(color);edit.put("name","新黑");tx.execute(s->service.saveColors("A",command("new-color",List.of(edit))));var refs=List.of(Map.<String,Object>of("type","color","reference",r.get("code"),"version",1));assertThatThrownBy(()->service.resolveReferences("A",refs,"","",false)).hasMessageContaining("静默换色");assertThat(service.resolveReferences("A",refs,"","",true).toString()).contains("原黑").doesNotContain("新黑");}
    @Test void fourDigitStringCannotBeSilentlyConvertedToAMemberId(){assertThatThrownBy(()->tx.execute(s->service.saveRanges("A",command("not-an-id",List.of(range("错误引用","model",List.of("0185"))))))).hasMessageContaining("不能把四位");assertThat(count("plm_supplier_range")).isZero();}
    void attachment(long id,String name,String type){jdbc.update("insert into plm_attachment(attachment_id,file_name,version_no,content_type,file_size) values(?,?,'V2',?,128)",id,name,type);}
    Map<String,Object> colorWithImage(String request,long file){return rows(tx.execute(s->service.saveColors("A",command(request,List.of(Map.of("name","色样 "+request,"refs",List.of(Map.of("id",file,"name","伪造名称","type","text/plain")))))))).get(0);}
    @Test void originalColorImagesUseCanonicalMetadataButPersistOnlyExistingAttachmentIds(){
        attachment(301,"实际黑色色样.png","image/png");var color=colorWithImage("canonical",301);var refs=maps(color.get("refs"));
        assertThat(refs).hasSize(1);assertThat(number(refs.get(0).get("id"))).isEqualTo(301);assertThat(refs.get(0)).containsEntry("name","实际黑色色样.png").containsEntry("version","V2").containsEntry("type","image/png").containsEntry("contentType","image/png");
        assertThat(jdbc.queryForObject("select data->'refs' from plm_supplier_color where color_id=?",String.class,color.get("id"))).isEqualTo("[{\"id\": 301}]");
        assertThat(count("plm_attachment")).isEqualTo(1);assertThat(count("plm_partner_file")).isZero();verify(access,atLeastOnce()).requireFile(301L);
    }
    @Test void legacyIdOnlyImageReferencesAreDecoratedAndHiddenWhenFileAccessChanges(){
        attachment(301,"历史原色色样.jpg","image/jpeg");var color=addColor("A","历史原色");jdbc.update("update plm_supplier_color set data=jsonb_set(data,'{refs}','[{\"id\":301,\"name\":\"旧缓存不能泄漏\"}]') where color_id=?",color.get("id"));
        var visible=rows(service.colors("A",Map.of())).get(0);assertThat(maps(visible.get("refs")).get(0)).containsEntry("name","历史原色色样.jpg").containsEntry("type","image/jpeg");
        when(access.visibleSqlClause("file","a.attachment_id")).thenReturn("a.attachment_id<>301");assertThat(maps(rows(service.colors("A",Map.of())).get(0).get("refs"))).isEmpty();
        when(access.visibleSqlClause("file","a.attachment_id")).thenReturn("true");when(access.hasPermission("file:read")).thenReturn(false);assertThat(maps(rows(service.colors("A",Map.of())).get(0).get("refs"))).isEmpty();
    }
    @Test void unauthorizedImageInLaterItemRollsBackWholeColorBatch(){
        attachment(301,"不属于当前账号.png","image/png");doThrow(invalid("无权查看此附件")).when(access).requireFile(301L);
        var input=command("unauthorized",List.of(Map.of("name","先合法"),Map.of("name","后越权","refs",List.of(Map.of("id",301)))));
        assertThatThrownBy(()->tx.execute(s->service.saveColors("A",input))).hasMessageContaining("无权");assertThat(count("plm_supplier_color")).isZero();assertThat(count("plm_supplier_range_request")).isZero();
        doNothing().when(access).requireFile(301L);when(access.visibleSqlClause("file","a.attachment_id")).thenReturn("a.attachment_id<>301");assertThatThrownBy(()->colorWithImage("acl-filter",301)).hasMessageContaining("有权查看的图片");assertThat(count("plm_supplier_color")).isZero();
    }
    @Test void nonImagesCostFilesAndImportedOriginalsCannotBecomeOriginalColorImages(){
        attachment(301,"报告.pdf","application/pdf");attachment(302,"报价截图.png","image/png");attachment(303,"结算凭据.jpg","image/jpeg");attachment(304,"已标记原件.webp","image/webp");
        jdbc.update("insert into plm_partner_file values(10,302,'cooperation_cost'),(11,303,'financial')");jdbc.update("insert into plm_partner_file_job values('import',304)");
        for(long file:List.of(301L,302L,303L,304L))assertThatThrownBy(()->colorWithImage("restricted-"+file,file)).hasMessageContaining("不能采用");
        assertThat(count("plm_supplier_color")).isZero();assertThat(count("plm_supplier_range_request")).isZero();assertThat(count("plm_attachment")).isEqualTo(4);
    }
    @Test void frozenColorRangeAndIdempotentReplayNeverExposeRevokedImageMetadata(){
        attachment(301,"曾授权的图片.png","image/png");var color=colorWithImage("historic-image",301);var range=rows(tx.execute(s->service.saveRanges("A",command("with-image-range",List.of(range("色样组","color",List.of(color.get("id")))))))).get(0);
        when(access.visibleSqlClause("file","a.attachment_id")).thenReturn("a.attachment_id<>301");var frozen=service.resolveReferences("A",List.of(Map.of("type","color","reference",range.get("code"),"version",1)),"","",true).get(0);
        assertThat(maps(maps(frozen.get("members")).get(0).get("refs"))).isEmpty();var replay=colorWithImage("historic-image",301);assertThat(maps(replay.get("refs"))).isEmpty();assertThat(replay.toString()).doesNotContain("曾授权的图片");
    }
}
