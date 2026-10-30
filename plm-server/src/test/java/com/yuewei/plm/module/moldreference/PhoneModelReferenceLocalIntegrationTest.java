package com.yuewei.plm.module.moldreference;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.yuewei.plm.common.exception.BusinessException;
import com.yuewei.plm.common.security.CurrentUser;
import com.yuewei.plm.common.security.CurrentUserContext;
import com.yuewei.plm.module.operationlog.service.OperationLogCreateCommand;
import com.yuewei.plm.module.operationlog.service.OperationLogService;
import com.yuewei.plm.module.pilot.PilotAccessService;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.yuewei.plm.module.moldreference.PhoneModelReferenceTypes.*;

/** Local-only opt-in. DDL, identities and every business fixture are rolled back together. */
@EnabledIfEnvironmentVariable(named="PLM_COLLAB_TEST_DB_URL",matches="jdbc:postgresql://127\\.0\\.0\\.1:(15432/plm_data_management|15483/plm_foundation_crud_it|15504/plm_history_restoration_qa)\\?currentSchema=plm")
class PhoneModelReferenceLocalIntegrationTest {
    JdbcTemplate jdbc;DataSourceTransactionManager tx;TransactionStatus scope;
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    PhoneModelReferenceService service;OperationLogService audit;CurrentUser admin;
    String mesDisplayName;
    org.mybatis.spring.SqlSessionTemplate productionSqlSession;

    @BeforeEach void beginRollback() throws Exception {
        var ds=new DriverManagerDataSource(System.getenv("PLM_COLLAB_TEST_DB_URL"),"postgres",System.getenv("PLM_COLLAB_TEST_DB_PASSWORD"));
        jdbc=new JdbcTemplate(ds);tx=new DataSourceTransactionManager(ds);scope=tx.getTransaction(new DefaultTransactionDefinition());
        com.yuewei.plm.module.collaboration.MoldMasterTestSchema.apply(jdbc);
        jdbc.execute("alter table plm_phone_brand add column if not exists aliases text[] not null default '{}'");
        var user=jdbc.queryForList("select user_id,username,display_name from sys_user where status='active' and deleted_flag=0 and all_permissions=true order by user_id limit 1").get(0);
        admin=new CurrentUser(((Number)user.get("user_id")).longValue(),user.get("username").toString(),user.get("display_name").toString(),true);CurrentUserContext.set(admin);
        audit=mock(OperationLogService.class);service=new PhoneModelReferenceService(jdbc,json,new PhoneModelReferenceRepository(jdbc),new PilotAccessService(jdbc,json),audit);
        mesDisplayName="IT-Apple-"+UUID.randomUUID();
    }
    @AfterEach void rollback() {if(scope!=null&&!scope.isCompleted())tx.rollback(scope);CurrentUserContext.clear();}
    long id(Map<String,Object> row) {return ((Number)row.get("id")).longValue();}
    long brand() {return id(service.createBrand(new CreateBrand("品牌-"+UUID.randomUUID()),null));}
    long phone(long brand,Long code) {return id(service.createPhone(new CreatePhone(brand,"Model-"+UUID.randomUUID(),List.of(),code),null));}
    long appleBrand() {
        var existing=jdbc.queryForList("select id from plm_phone_brand where lower(name)='apple'",Long.class);
        return existing.isEmpty()?id(service.createBrand(new CreateBrand("Apple"),null)):existing.get(0);
    }
    long code(String type) {
        String value=jdbc.queryForObject("select lpad(v::text,4,'0') from generate_series(0,9999) v where not exists(select 1 from plm_code_item c where c.code_type=? and c.code_value=lpad(v::text,4,'0')) order by v limit 1",String.class,type);
        return jdbc.queryForObject("insert into plm_code_item(code_type,code_value,code_name,status,sort_order,deleted_flag) values(?,?,?,'enabled',0,0) returning code_item_id",Long.class,type,value,"Reference-"+UUID.randomUUID());
    }
    CodeSelectionBatch codeLinks(long... ids) {
        return new CodeSelectionBatch(Arrays.stream(ids).mapToObj(c->{var row=jdbc.queryForMap("select code_value,code_name from plm_code_item where code_item_id=?",c);return new CodeSelectionLink(c,row.get("code_value").toString(),row.get("code_name").toString());}).toList(),"按已维护编码及品牌补齐组选项，不确认实物与适配");
    }
    @Test void selectorSearchFindsCrossBrandTokensAndStoredAliasesWithoutRewritingIdentity() {
        String marker="Selector"+UUID.randomUUID().toString().replace("-","");
        long apple=id(service.createBrand(new CreateBrand("Apple "+marker,List.of("苹果 "+marker)),null));
        long samsung=id(service.createBrand(new CreateBrand("Samsung "+marker),null));
        long first=id(service.createPhone(new CreatePhone(apple,"iPhone 17 Pro Max",List.of("IP17PM"),code("phone_model")),null));
        long second=id(service.createPhone(new CreatePhone(samsung,"Galaxy S26 Ultra 5G",List.of(),code("phone_model")),null));
        var q=new Query();q.setSelectionSearch(true);q.setKeyword(marker+" SAMSUNG S26");
        assertThat(service.phones(q).getContent()).extracting(v->v.get("phoneModelId")).containsExactly(second);
        q.setKeyword(marker+" 17 Pro MAX");assertThat(service.phones(q).getContent()).extracting(v->v.get("phoneModelId")).containsExactly(first);
        q.setKeyword(marker+" IP17PM");assertThat(service.phones(q).getContent()).extracting(v->v.get("phoneModelId")).containsExactly(first);
        q.setKeyword(marker+" 苹果");assertThat(service.phones(q).getContent()).extracting(v->v.get("phoneModelId")).containsExactly(first);
        var row=service.phone(first);assertThat(row).containsEntry("modelName","iPhone 17 Pro Max");assertThat(row.get("brandAliases")).isEqualTo(List.of("苹果 "+marker));assertThat(row.get("phoneModelCode").toString()).hasSize(4);
        q.setSelectionSearch(false);q.setKeyword(marker+" SAMSUNG S26");assertThat(service.phones(q).getContent()).isEmpty();
    }
    @Test void selectorGenerationCannotMatchAnotherGenerationByCodeYearOrBrandSeriesAlias() {
        String marker="Search"+UUID.randomUUID().toString().replace("-","").replaceAll("[0-9]","q");
        long apple=appleBrand();var original=service.brand(apple);
        service.patchBrand(apple,patch("aliases",original.get("aliases"),List.of("IP","iPhone")),null);
        service.patchBrand(apple,patch("prefixes",original.get("prefixes"),List.of(new Prefix("IP","iPhone"),new Prefix("iPhone","iPhone"),new Prefix("iPad","iPad"))),null);
        long combined=id(service.createPhone(new CreatePhone(apple,"iPhone 13/14/15 "+marker,List.of(),null),null));
        long first=id(service.createPhone(new CreatePhone(apple,"iPhone 17 Pro Max "+marker,List.of(),null),null));
        long air=id(service.createPhone(new CreatePhone(apple,"IP17 Air "+marker,List.of(),null),null));
        long tabletCode=code("phone_model");jdbc.update("update plm_code_item set code_name='IP IPAD Pro 12.9 (2017)' where code_item_id=?",tabletCode);
        long tablet=id(service.createPhone(new CreatePhone(apple,"iPad Pro 12.9 (2017) "+marker,List.of(),tabletCode),null));
        service.createPhone(new CreatePhone(apple,"IP watch ultra "+marker,List.of(),null),null);
        String value=jdbc.queryForObject("select lpad(v::text,4,'0') from generate_series(0,9999) v where lpad(v::text,4,'0') like '%17' and not exists(select 1 from plm_code_item where code_type='phone_model' and code_value=lpad(v::text,4,'0')) order by v limit 1",String.class);
        long c=jdbc.queryForObject("insert into plm_code_item(code_type,code_value,code_name) values('phone_model',?,'iPhone 18 Pro') returning code_item_id",Long.class,value);
        long newer=id(service.createPhone(new CreatePhone(apple,"iPhone 18 Pro "+marker,List.of(),c),null));
        var q=new Query();q.setSelectionSearch(true);q.setKeyword(marker+" iPhone 17");
        assertThat(service.phones(q).getContent()).extracting(v->v.get("phoneModelId")).containsExactlyInAnyOrder(first,air).doesNotContain(tablet,newer);
        q.setKeyword(marker+" iPhone");assertThat(service.phones(q).getContent()).extracting(v->v.get("phoneModelId")).containsExactlyInAnyOrder(first,air,newer,combined);
        q.setKeyword(marker+" iPhone 14");assertThat(service.phones(q).getContent()).extracting(v->v.get("phoneModelId")).containsExactly(combined);
        q.setKeyword(marker+" iPad 12.9");assertThat(service.phones(q).getContent()).extracting(v->v.get("phoneModelId")).containsExactly(tablet);
        q.setKeyword(marker+" "+value);assertThat(service.phones(q).getContent()).extracting(v->v.get("phoneModelId")).containsExactly(newer);
        long samsung=id(service.createBrand(new CreateBrand("Samsung "+marker),null));
        long real=id(service.createPhone(new CreatePhone(samsung,"Galaxy S26 Ultra",List.of(),null),null));
        service.createPhone(new CreatePhone(samsung,"SM S266",List.of(),null),null);
        q.setKeyword(marker+" Samsung S26");assertThat(service.phones(q).getContent()).extracting(v->v.get("phoneModelId")).containsExactly(real);
    }
    @Test void selectorWildcardOnlyQueryReturnsNoRowsRatherThanEveryPhone() {
        var q=new Query();q.setSelectionSearch(true);q.setKeyword("% _ ／");
        assertThat(service.phones(q).getContent()).isEmpty();assertThat(service.phones(q).getTotalElements()).isZero();
    }
    @Test void originalCodeGroupIsSelectableByBrandWithoutSplittingHolesNetworkOrLeadingZeroAndRetryIsIdempotent() {
        long b=brand(),c=code("phone_model");String raw="SM S26 4G/S26 Pro 5G Gran agujero\nMax MX";
        jdbc.update("update plm_code_item set code_name=? where code_item_id=?",raw,c);
        jdbc.update("insert into plm_phone_code_brand(code_item_id,brand_id,created_by) values(?,?,'it')",c,b);
        long sets=jdbc.queryForObject("select count(*) from plm_collaboration_mold_set",Long.class),products=jdbc.queryForObject("select count(*) from plm_product",Long.class);
        var result=service.linkCodeSelection(codeLinks(c),null);assertThat(result).singleElement().satisfies(r->assertThat(r).containsEntry("status","created"));
        var q=new Query();q.setBrandId(b);q.setCodeItemId(c);var rows=service.phones(q).getContent();assertThat(rows).singleElement().satisfies(r->{assertThat(r).containsEntry("modelName",raw).containsEntry("codeItemId",c);assertThat(r.get("phoneModelCode").toString()).hasSize(4);assertThat(r.get("sourceSnapshot").toString()).contains("existing-phone-code-group","combinedOriginalPreserved");});
        assertThat(service.linkCodeSelection(codeLinks(c),null).get(0)).containsEntry("status","linked");assertThat(service.phones(q).getContent()).hasSize(1);
        assertThat(jdbc.queryForObject("select count(*) from plm_collaboration_mold_set",Long.class)).isEqualTo(sets);assertThat(jdbc.queryForObject("select count(*) from plm_product",Long.class)).isEqualTo(products);
    }
    @Test void crossBrandCombinedCodeRemainsTheSameOriginalGroupInEachExplicitBrand() {
        long first=brand(),second=brand(),c=code("phone_model");String raw="HW Y6/Honor 5A 4G";
        jdbc.update("update plm_code_item set code_name=? where code_item_id=?",raw,c);
        for(long b:List.of(first,second))jdbc.update("insert into plm_phone_code_brand(code_item_id,brand_id,created_by) values(?,?,'it')",c,b);
        service.linkCodeSelection(codeLinks(c),null);
        var q=new Query();q.setCodeItemId(c);assertThat(service.phones(q).getContent()).hasSize(2).allSatisfy(r->assertThat(r).containsEntry("modelName",raw).containsEntry("codeItemId",c));
    }
    @Test void existingCodeMembersAreNotDuplicatedOrRebrandedAndUnknownAccessoriesStayInCodeRegistry() {
        long b=brand(),c=code("phone_model"),p=phone(b,c),accessory=code("phone_model");
        jdbc.update("update plm_code_item set code_name='原编码组 SM S777' where code_item_id=?",c);
        var q=new Query();q.setBrandId(b);q.setKeyword("S777");assertThat(service.phones(q).getContent()).singleElement().satisfies(r->assertThat(r.get("phoneModelId")).isEqualTo(p));
        jdbc.update("update plm_code_item set code_name='挂绳 Cuerda colgante' where code_item_id=?",accessory);
        var result=service.linkCodeSelection(codeLinks(c,accessory),null);assertThat(result.stream().filter(r->r.get("codeItemId").equals(c)).findFirst().orElseThrow()).containsEntry("phoneModelIds",List.of(p));
        assertThat(result.stream().filter(r->r.get("codeItemId").equals(accessory)).findFirst().orElseThrow()).containsEntry("status","review");
        assertThat(jdbc.queryForObject("select count(*) from plm_phone_model_member where code_item_id=?",Long.class,accessory)).isZero();
    }
    @Test void codeLinkRejectsStaleSourceAndDuplicateNameOtherCodeWithoutOverwritingExistingIdentity() {
        long b=brand(),first=code("phone_model"),other=code("phone_model");String raw="Model unique "+UUID.randomUUID();
        service.createPhone(new CreatePhone(b,raw,List.of(),first),null);jdbc.update("update plm_code_item set code_name=? where code_item_id=?",raw,other);jdbc.update("insert into plm_phone_code_brand(code_item_id,brand_id,created_by) values(?,?,'it')",other,b);
        var expected=codeLinks(other);assertThat(service.linkCodeSelection(expected,null).get(0)).containsEntry("status","review");
        jdbc.update("update plm_code_item set code_name='changed' where code_item_id=?",other);
        assertThatThrownBy(()->nested(()->service.linkCodeSelection(expected,null))).hasMessageContaining("已变更");
        assertThat(jdbc.queryForObject("select count(*) from plm_phone_model_member where code_item_id=?",Long.class,other)).isZero();
    }
    @Test void codeGroupRegistrationRollsBackOnAuditFailureAndCannotBeExecutedBySelectionOnlyUsers() {
        long b=brand(),c=code("phone_model");jdbc.update("insert into plm_phone_code_brand(code_item_id,brand_id,created_by) values(?,?,'it')",c,b);var expected=codeLinks(c);
        reset(audit);when(audit.logRequired(any(),any())).thenThrow(new IllegalStateException("mandatory audit failure"));
        assertThatThrownBy(()->nested(()->service.linkCodeSelection(expected,null))).hasMessageContaining("audit failure");assertThat(jdbc.queryForObject("select count(*) from plm_phone_model_member where code_item_id=?",Long.class,c)).isZero();
        ordinary("market",null);assertThatThrownBy(()->service.linkCodeSelection(expected,null)).hasMessageContaining("工程职责");service.syncCodeSelectionAfterMaintenance(c,null);assertThat(jdbc.queryForObject("select count(*) from plm_phone_model_member where code_item_id=?",Long.class,c)).isZero();
    }
    @Test void codeCreateAndExplicitBrandMaintenanceRegisterOptionsInTheExistingTransaction() throws Exception {
        long b=brand();String brandName=service.brand(b).get("name").toString();
        var queries=codeQueries();org.springframework.test.util.ReflectionTestUtils.setField(queries,"phoneReferences",service);
        var dto=new com.yuewei.plm.module.code.dto.CodeItemSaveDTO();dto.setCodeType("phone_model");dto.setCodeName(brandName+" M5 5G");dto.setCodeValue(jdbc.queryForObject("select lpad(v::text,4,'0') from generate_series(0,9999) v where not exists(select 1 from plm_code_item where code_type='phone_model' and code_value=lpad(v::text,4,'0')) order by v limit 1",String.class));dto.setSortOrder(0);
        var created=queries.create(dto);assertThat(jdbc.queryForObject("select count(*) from plm_phone_model_member where code_item_id=?",Long.class,created.getCodeItemId())).isEqualTo(1);
        long old=code("phone_model");var metadata=production();org.springframework.test.util.ReflectionTestUtils.setField(metadata,"phoneReferences",service);metadata.phone(old,patch("brandIds",List.of(),List.of(b)),null);assertThat(jdbc.queryForObject("select count(*) from plm_phone_model_member where code_item_id=?",Long.class,old)).isEqualTo(1);
        metadata.phone(old,patch("brandIds",List.of(b),List.of(b)),null);assertThat(jdbc.queryForObject("select count(*) from plm_phone_model_member where code_item_id=?",Long.class,old)).isEqualTo(1);
    }
    Patch patch(String field,Object before,Object after) {return new Patch(Map.of(field,json.valueToTree(after)),Map.of(field,json.valueToTree(before)),"人工更正");}
    long ordinary(String group,String role) {
        Long id=jdbc.queryForObject("select max(user_id)+100 from sys_user",Long.class);
        jdbc.update("insert into sys_user(user_id,user_code,user_name,login_name,username,display_name,status,all_permissions) values(?,?,?,?,?,?,'active',false)",id,"ref-"+id,"真实工程账号","ref-"+id,"ref-"+id,"真实工程账号");
        if(group!=null)jdbc.update("insert into plm_collaboration_assignment(field_group,user_id) values(?,?)",group,id);
        if(role!=null)jdbc.update("insert into plm_pilot_account(user_id,role_code,permissions,all_objects,created_by,updated_by) values(?,?,?::jsonb,false,'it','it')",id,role,"[\"collaboration:view\"]");
        CurrentUserContext.set(new CurrentUser(id,"forged-admin","客户端伪造管理员",true));return id;
    }

    @Test void sameBusinessCodeHasTwoStablePhonesWithoutCreatingCodesOrPhysicalSets() {
        long b=brand(),c=code("phone_model");long codes=jdbc.queryForObject("select count(*) from plm_code_item",Long.class),sets=jdbc.queryForObject("select count(*) from plm_collaboration_mold_set",Long.class);
        var first=service.createPhone(new CreatePhone(b,"Apple iPhone 17 Pro",List.of("17PRO"),c),null);
        var second=service.createPhone(new CreatePhone(b,"Apple iPhone 18 Pro",List.of("18PRO"),c),null);
        assertThat(id(first)).isNotEqualTo(id(second));assertThat(first.get("codeItemId")).isEqualTo(second.get("codeItemId"));
        assertThat(jdbc.queryForObject("select count(*) from plm_code_item",Long.class)).isEqualTo(codes);
        assertThat(jdbc.queryForObject("select count(*) from plm_collaboration_mold_set",Long.class)).isEqualTo(sets);
    }
    @Test void unassignedPhoneCanBeCreatedAndSearchedByBrandAliasWithoutInventingCode() {
        long b=brand();var row=service.createPhone(new CreatePhone(b,"iPhone 19 Pro",List.of("  futura  ","FUTURA","iPhone 19 Pro"),null),null);
        assertThat(row).containsEntry("codeItemId",null).containsEntry("phoneModelCode",null).containsEntry("codeLinkStatus","unassigned");assertThat(row.get("aliases")).isEqualTo(List.of("futura"));
        var q=new Query();q.setBrandId(b);q.setKeyword("FUTURA");assertThat(service.phones(q).getContent()).extracting(r->r.get("id")).containsExactly(row.get("id"));
        assertThat(service.requireEnabledModels(List.of(id(row)))).containsKey(id(row));
    }
    @Test void canonicalBrandAndBrandScopedPhoneNamesRejectCaseSpaceDuplicatesIncludingDisabled() {
        var b=service.createBrand(new CreateBrand("  标准品牌-"+UUID.randomUUID()+"  "),null);String name=b.get("name").toString();
        assertThatThrownBy(()->service.createBrand(new CreateBrand(" "+name.toLowerCase(Locale.ROOT)+" "),null)).isInstanceOf(BusinessException.class).hasMessageContaining("已存在");
        var p=service.createPhone(new CreatePhone(id(b)," iPhone 17 Pro ",List.of(),null),null);
        service.patchPhone(id(p),new Patch(Map.of("status",json.valueToTree("disabled")),Map.of("status",json.valueToTree("enabled")),"隔离测试停用"),null);
        assertThatThrownBy(()->service.createPhone(new CreatePhone(id(b),"IPHONE 17 PRO",null,null),null)).isInstanceOf(BusinessException.class).hasMessageContaining("已存在");
        assertThat(service.createPhone(new CreatePhone(brand(),"iPhone 17 Pro",List.of(),null),null)).containsEntry("modelName","iPhone 17 Pro");
    }
    @Test void sameFieldConflictsWhileDistinctFieldsMergeAndOriginalValuesAreRequired() {
        long b=brand();var first=service.createPhone(new CreatePhone(b,"Old model",List.of(),null),null);long p=id(first);
        assertThat(service.patchPhone(p,patch("modelName","Old model","Current model"),null)).containsEntry("status","saved");
        var conflict=service.patchPhone(p,patch("modelName","Old model","Stale model"),null);
        assertThat(conflict).containsEntry("status","conflict");assertThat(service.phone(p).get("modelName")).isEqualTo("Current model");
        assertThat(service.patchPhone(p,patch("aliases",List.of(),List.of("CM")),null)).containsEntry("status","saved");
        assertThat(service.phone(p)).containsEntry("modelName","Current model").containsEntry("aliases",List.of("CM"));
        assertThatThrownBy(()->service.patchPhone(p,new Patch(Map.of("status",json.valueToTree("disabled")),Map.of(),null),null)).isInstanceOf(BusinessException.class).hasMessageContaining("原值");
    }
    @Test void originalCodeIsImmutableLeadingZerosAndDisabledReferencesStayReadable() {
        long b=brand(),c=code("phone_model"),p=phone(b,c);String value=service.phone(p).get("phoneModelCode").toString();assertThat(value).hasSize(4);
        assertThatThrownBy(()->service.patchPhone(p,patch("codeItemId",c,code("phone_model")),null)).isInstanceOf(BusinessException.class).hasMessageContaining("固定保留");
        jdbc.update("update plm_code_item set status='disabled',deleted_flag=1 where code_item_id=?",c);
        assertThat(service.phone(p)).containsEntry("phoneModelCode",value).containsEntry("codeLinkStatus","missing");
        assertThat(service.members(List.of(p),true)).containsKey(p);
        assertThatThrownBy(()->service.requireEnabledModels(List.of(p))).isInstanceOf(BusinessException.class).hasMessageContaining("核对");
        assertThat(service.patchPhone(p,patch("modelName",service.phone(p).get("modelName"),"Historical model"),null)).containsEntry("status","saved");
    }
    @Test void codeLinkOnlyUsesEnabledPhoneTypeAndRejectsColourCode() {
        long b=brand();assertThatThrownBy(()->phone(b,code("color"))).isInstanceOf(BusinessException.class).hasMessageContaining("四位手机编码");
        long c=code("phone_model");jdbc.update("update plm_code_item set status='disabled' where code_item_id=?",c);
        assertThatThrownBy(()->phone(b,c)).isInstanceOf(BusinessException.class).hasMessageContaining("启用");
    }
    @Test void engineerUsesServerIdentityAndMarketAccountCannotWriteReferenceData() {
        long user=ordinary("engineering",null);var b=service.createBrand(new CreateBrand("Engineering-"+UUID.randomUUID()),null);
        assertThat(jdbc.queryForObject("select created_by from plm_phone_brand where id=?",String.class,id(b))).isEqualTo("ref-"+user);
        var captor=ArgumentCaptor.forClass(CurrentUser.class);verify(audit).logRequired(any(OperationLogCreateCommand.class),captor.capture());assertThat(captor.getValue().allPermissions()).isFalse();assertThat(captor.getValue().username()).isEqualTo("ref-"+user);
        ordinary("market",null);assertThatThrownBy(()->service.createBrand(new CreateBrand("No write"),null)).isInstanceOf(BusinessException.class).hasMessageContaining("工程职责");
    }
    @Test void supplierAndMexicoReferenceRequestsRemainForbiddenDespiteForgedAdministratorClaims() {
        ordinary(null,"supplier");assertThatThrownBy(()->service.phones(new Query())).isInstanceOf(BusinessException.class).hasMessageContaining("手机任务");
        ordinary(null,"mexico");assertThatThrownBy(()->service.brands(new Query())).isInstanceOf(BusinessException.class).hasMessageContaining("手机任务");
    }
    @Test void auditFailureRollsBackBusinessWriteInCallerTransaction() {
        var nested=new TransactionTemplate(tx);nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        String name="Audit-failure-"+UUID.randomUUID();doThrow(new IllegalStateException("mandatory audit unavailable")).when(audit).logRequired(any(),any());
        assertThatThrownBy(()->nested.execute(status->service.createBrand(new CreateBrand(name),null))).isInstanceOf(IllegalStateException.class).hasMessageContaining("audit unavailable");
        assertThat(jdbc.queryForObject("select count(*) from plm_phone_brand where name=?",Long.class,name)).isZero();
    }
    @Test void stoppedBrandAndModelRemainTraceableButCannotBeNewCompatibilityMembers() {
        long b=brand(),p=phone(b,null);service.patchBrand(b,new Patch(Map.of("status",json.valueToTree("disabled")),Map.of("status",json.valueToTree("enabled")),"隔离测试停用"),null);
        assertThat(service.members(List.of(p),true).get(p)).containsEntry("brandStatus","disabled");
        assertThatThrownBy(()->service.requireEnabledModels(List.of(p))).isInstanceOf(BusinessException.class).hasMessageContaining("启用");
        assertThatThrownBy(()->service.createPhone(new CreatePhone(b,"No new member",null,null),null)).isInstanceOf(BusinessException.class).hasMessageContaining("停用");
        assertThatThrownBy(()->service.members(Arrays.asList(p,null),true)).isInstanceOf(BusinessException.class).hasMessageContaining("无效");
    }
    PhoneModelMesSnapshotService mes() {return new PhoneModelMesSnapshotService(jdbc,json,service,new PilotAccessService(jdbc,json),audit);}
    com.yuewei.plm.module.productionmetadata.ProductionMetadataService production() {
        var target=new com.yuewei.plm.module.productionmetadata.ProductionMetadataService(jdbc,json,new com.yuewei.plm.module.productionmetadata.PhoneCodeMetadataRepository(jdbc,json),new PhoneModelReferenceRepository(jdbc),new PilotAccessService(jdbc,json),audit);
        var interceptor=new org.springframework.transaction.interceptor.TransactionInterceptor();interceptor.setTransactionManager(tx);interceptor.setTransactionAttributeSource(new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource());
        var proxy=new org.springframework.aop.framework.ProxyFactory(target);proxy.setProxyTargetClass(true);proxy.addAdvice(interceptor);
        return (com.yuewei.plm.module.productionmetadata.ProductionMetadataService)proxy.getProxy();
    }
    long product(Long parent,String type) {
        long id=jdbc.queryForObject("select max(product_id)+1 from plm_product",Long.class);
        jdbc.update("insert into plm_product(product_id,parent_product_id,product_code,product_name,product_type,version_no,status,lock_status,created_by,updated_by) values(?,?,?,?,?,'A','archived','frozen','it','it')",id,parent,"PRODUCTION-"+UUID.randomUUID(),"独立生产状态测试",type);return id;
    }
    String productionState(String table,String pk,long id) {return jdbc.queryForObject("select production_status from "+table+" where "+pk+"=?",String.class,id);}
    <T> T nested(java.util.function.Supplier<T> action) {var nested=new TransactionTemplate(tx);nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);return nested.execute(s->action.get());}
    com.yuewei.plm.module.productionmetadata.ProductionMetadataService.Item item(long id,String before,String after) {return new com.yuewei.plm.module.productionmetadata.ProductionMetadataService.Item(id,Map.of("productionStatus",json.valueToTree(after)),Map.of("productionStatus",json.valueToTree(before)));}
    com.yuewei.plm.module.code.service.CodeItemService codeQueries() throws Exception {
        var factory=new com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean();factory.setDataSource(jdbc.getDataSource());
        var config=new com.baomidou.mybatisplus.core.MybatisConfiguration();config.addMapper(com.yuewei.plm.module.code.repository.CodeItemRepository.class);config.addMapper(com.yuewei.plm.repository.ProductRepository.class);config.addMapper(com.yuewei.plm.module.operationlog.repository.OperationLogRepository.class);factory.setConfiguration(config);
        var interceptor=new com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor();interceptor.addInnerInterceptor(new com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor(com.baomidou.mybatisplus.annotation.DbType.POSTGRE_SQL));factory.setPlugins(interceptor);
        factory.afterPropertiesSet();productionSqlSession=new org.mybatis.spring.SqlSessionTemplate(factory.getObject());var result=new com.yuewei.plm.module.code.service.CodeItemService(productionSqlSession.getMapper(com.yuewei.plm.module.code.repository.CodeItemRepository.class));
        org.springframework.test.util.ReflectionTestUtils.setField(result,"lifecycle",new com.yuewei.plm.module.code.service.CodeLifecycleService(jdbc,json,new PilotAccessService(jdbc,json),audit));org.springframework.test.util.ReflectionTestUtils.setField(result,"jdbc",jdbc);org.springframework.test.util.ReflectionTestUtils.setField(result,"phoneMetadata",new com.yuewei.plm.module.productionmetadata.PhoneCodeMetadataRepository(jdbc,json));org.springframework.test.util.ReflectionTestUtils.setField(result,"phoneReferences",service);return result;
    }
    com.yuewei.plm.module.code.dto.CodeItemSaveDTO registration(long brand,Long phone,String fullName,String factoryName) {
        var dto=new com.yuewei.plm.module.code.dto.CodeItemSaveDTO();dto.setCodeType("phone_model");
        dto.setCodeValue(jdbc.queryForObject("select lpad(v::text,4,'0') from generate_series(0,9999) v where not exists(select 1 from plm_code_item c where c.code_type='phone_model' and c.code_value=lpad(v::text,4,'0')) order by v limit 1",String.class));
        dto.setCodeName(factoryName);dto.setSortOrder(0);dto.setPhoneRegistration(new NewCodePhone(brand,phone,fullName));return dto;
    }
    @Test void oneSaveAssignsNewCodeToExistingIdentityAndDerivesBrandWithoutRenamingOrCreatingMolds() throws Exception {
        long b=brand(),p=phone(b,null);var before=service.phone(p);long members=jdbc.queryForObject("select count(*)from plm_phone_model_member",Long.class),molds=jdbc.queryForObject("select count(*)from plm_collaboration_mold_set",Long.class);
        var dto=registration(b,p,null,"XH QA-LITE "+UUID.randomUUID());var codes=codeQueries();var saved=codes.create(dto);
        assertThat(saved.getCodeValue()).hasSize(4).startsWith("0");
        assertThat(service.phone(p)).containsEntry("phoneModelId",p).containsEntry("codeItemId",saved.getCodeItemId()).containsEntry("modelName",before.get("modelName")).containsEntry("aliases",before.get("aliases"));
        var q=new com.yuewei.plm.module.code.dto.CodeItemQueryDTO();q.setCodeType("phone_model");q.setBrandId(b);
        assertThat(codes.page(q).getContent()).singleElement().satisfies(r->assertThat(r.getCodeItemId()).isEqualTo(saved.getCodeItemId()));
        assertThat(jdbc.queryForObject("select count(*)from plm_phone_model_member",Long.class)).isEqualTo(members);
        assertThat(jdbc.queryForObject("select count(*)from plm_collaboration_mold_set",Long.class)).isEqualTo(molds);
    }
    @Test void oneSaveCreatesExplicitFullNameWithSeparateFactoryShortNameAndPreservesProductionDefault() throws Exception {
        long b=brand();String full="Full handset "+UUID.randomUUID(),factory="FH QA "+UUID.randomUUID();
        var saved=codeQueries().create(registration(b,null,full,factory));var q=new Query();q.setCodeItemId(saved.getCodeItemId());
        assertThat(service.phones(q).getContent()).singleElement().satisfies(r->assertThat(r).containsEntry("modelName",full).containsEntry("originalCodeName",factory).containsEntry("brandId",b).containsEntry("productionStatus","unknown"));
    }
    @Test void alreadyAssignedMemberCannotLeaveAnotherCodeBehindOrChangeItsIdentity() throws Exception {
        long b=brand(),originalCode=code("phone_model"),p=phone(b,originalCode);var dto=registration(b,p,null,"Stale request "+UUID.randomUUID());var codes=codeQueries();
        assertThatThrownBy(()->nested(()->codes.create(dto))).hasMessageContaining("已有编码");
        assertThat(jdbc.queryForObject("select count(*)from plm_code_item where code_type='phone_model'and code_value=?",Long.class,dto.getCodeValue())).isZero();
        assertThat(service.phone(p)).containsEntry("codeItemId",originalCode);
    }
    @Test void memberAuditFailureRollsBackCodeAndAssignmentTogether() throws Exception {
        long b=brand(),p=phone(b,null);var dto=registration(b,p,null,"Audit rollback "+UUID.randomUUID());var codes=codeQueries();
        doThrow(new IllegalStateException("required audit unavailable")).when(audit).logRequired(any(OperationLogCreateCommand.class),any(CurrentUser.class));
        assertThatThrownBy(()->nested(()->codes.create(dto))).hasMessageContaining("required audit unavailable");
        assertThat(service.phone(p)).containsEntry("codeItemId",null);
        assertThat(jdbc.queryForObject("select count(*)from plm_code_item where code_type='phone_model'and code_value=?",Long.class,dto.getCodeValue())).isZero();
    }
    @Test void assignmentPickerFiltersUnassignedAtTheDatabaseWithoutChangingOrdinaryReaders() {
        long b=brand(),pending=phone(b,null),assigned=phone(b,code("phone_model"));var q=new Query();q.setBrandId(b);q.setUnassignedCode(true);
        assertThat(service.phones(q).getContent()).extracting(r->r.get("phoneModelId")).containsExactly(pending);
        q.setUnassignedCode(false);assertThat(service.phones(q).getContent()).extracting(r->r.get("phoneModelId")).containsExactlyInAnyOrder(pending,assigned);
    }
    @Test void codeGroupUsesTheSharedFactorySpellingWithoutChangingTheRegisteredName() throws Exception {
        long b=appleBrand();var original=service.brand(b);
        service.patchBrand(b,patch("prefixes",original.get("prefixes"),List.of(new Prefix("IP","iPhone"))),null);
        long c=code("phone_model");jdbc.update("update plm_code_item set code_name='Apple iPhone QA Lite 5G' where code_item_id=?",c);phone(b,c);
        var q=new com.yuewei.plm.module.code.dto.CodeItemQueryDTO();q.setCodeType("phone_model");q.setBrandId(b);
        var row=codeQueries().page(q).getContent().stream().filter(r->r.getCodeItemId()==c).findFirst().orElseThrow();
        assertThat(row.getFactoryName()).isEqualTo("IP QA Lite 5G");assertThat(row.getCodeName()).isEqualTo("Apple iPhone QA Lite 5G");
        assertThat(row.getBrands()).noneSatisfy(r->assertThat(r).containsKey("prefixes"));
        assertThat(jdbc.queryForObject("select code_name from plm_code_item where code_item_id=?",String.class,c)).isEqualTo("Apple iPhone QA Lite 5G");
    }
    PhoneIdentityIssuesService identityIssues() throws Exception {
        var codes=codeQueries();audit=spy(new com.yuewei.plm.module.operationlog.service.impl.OperationLogServiceImpl(productionSqlSession.getMapper(com.yuewei.plm.module.operationlog.repository.OperationLogRepository.class)));
        var repo=new PhoneModelReferenceRepository(jdbc);var access=new PilotAccessService(jdbc,json);
        service=new PhoneModelReferenceService(jdbc,json,repo,access,audit);
        org.springframework.test.util.ReflectionTestUtils.setField(codes,"phoneReferences",service);
        var metadata=production();org.springframework.test.util.ReflectionTestUtils.setField(metadata,"phoneReferences",service);
        return new PhoneIdentityIssuesService(jdbc,json,repo,service,new com.yuewei.plm.module.productionmetadata.PhoneCodeMetadataRepository(jdbc,json),metadata,codes,new com.yuewei.plm.module.code.service.PhoneModelReviewService(jdbc,json,access,service,audit),access,audit);
    }
    @SuppressWarnings("unchecked") Map<String,Object> issue(PhoneIdentityIssuesService issues,long code) {
        var q=new PhoneIdentityIssuesService.Query();q.setState("all");q.setKeyword(jdbc.queryForObject("select code_value from plm_code_item where code_item_id=?",String.class,code));
        return ((List<Map<String,Object>>)issues.page(q).get("content")).stream().filter(r->("code:"+code).equals(r.get("key"))).findFirst().orElseThrow();
    }
    PhoneIdentityIssuesService.Selection selection(Map<String,Object> row){return new PhoneIdentityIssuesService.Selection(row.get("key").toString(),row.get("sourceDigest").toString());}
    @Test void centralIssuesClassifyExplicitAccessoriesWithAuditAndIdempotencyButDoNotGuessUnclearPhones() throws Exception {
        var issues=identityIssues();long accessory=code("phone_model"),unknown=code("phone_model");
        jdbc.update("update plm_code_item set code_name='挂绳 Cuerda colgante' where code_item_id=?",accessory);
        jdbc.update("update plm_code_item set code_name=? where code_item_id=?","TE Infinix teno pova "+UUID.randomUUID(),unknown);
        var row=issue(issues,accessory);assertThat(row).containsEntry("state","ready");assertThat(issue(issues,unknown)).containsEntry("state","pending");
        var input=new PhoneIdentityIssuesService.Resolve(List.of(selection(row)),"原文明确为挂绳，保留业务编码");
        assertThat(issues.resolve(input,null).get(0)).containsEntry("status","resolved");
        long count=jdbc.queryForObject("select count(*) from plm_operation_log where business_type='PHONE_IDENTITY_REVIEW'",Long.class);
        assertThat(issue(issues,accessory)).containsEntry("state","not_phone");assertThat(issues.resolve(input,null).get(0)).containsEntry("status","already_resolved");
        assertThat(jdbc.queryForObject("select count(*) from plm_operation_log where business_type='PHONE_IDENTITY_REVIEW'",Long.class)).isEqualTo(count);
        assertThat(jdbc.queryForObject("select count(*) from plm_phone_model_member where code_item_id in (?,?)",Long.class,accessory,unknown)).isZero();
        assertThatThrownBy(()->issues.resolve(new PhoneIdentityIssuesService.Resolve(List.of(selection(issue(issues,unknown))),"没有明确品牌不能自动处理"),null)).hasMessageContaining("缺明确依据");
    }
    @Test void centralBatchChecksEveryDigestBeforeWritingAndRollsBackWhenRequiredAuditFails() throws Exception {
        var issues=identityIssues();long first=code("phone_model"),second=code("phone_model");
        for(long c:List.of(first,second))jdbc.update("update plm_code_item set code_name=? where code_item_id=?","支架 clip "+UUID.randomUUID(),c);
        var one=selection(issue(issues,first));var two=selection(issue(issues,second));
        jdbc.update("update plm_code_item set code_name='支架 clip new' where code_item_id=?",second);
        assertThatThrownBy(()->nested(()->issues.resolve(new PhoneIdentityIssuesService.Resolve(List.of(one,two),"明确配件"),null))).hasMessageContaining("已变化");
        assertThat(issue(issues,first)).containsEntry("state","ready");
        doThrow(new IllegalStateException("required identity audit failed")).when(audit).logRequired(argThat(c->"PHONE_IDENTITY_REVIEW".equals(c.getBusinessType())),any());
        assertThatThrownBy(()->nested(()->issues.resolve(new PhoneIdentityIssuesService.Resolve(List.of(one),"明确配件"),null))).hasMessageContaining("audit failed");
        assertThat(issue(issues,first)).containsEntry("state","ready");
    }
    @Test void explicitCrmCodeAndBrandEvidenceFixClassificationWithoutChangingCodeMembersOrPhysicalFacts() throws Exception {
        var issues=identityIssues();long correct=brand(),wrong=brand(),c=code("phone_model"),p=phone(correct,c);
        String originalCode=service.phone(p).get("phoneModelCode").toString(),upstream="brand-"+UUID.randomUUID(),model="ZTE Axon60/Axon70";
        jdbc.update("update plm_phone_brand set source_system='crm',source_record_id=? where id=?",upstream,correct);
        jdbc.update("update plm_phone_model_member set model_name=?,source_system='crm',source_record_id=?,source_snapshot=?::jsonb where id=?",model,"phone-"+UUID.randomUUID(),json.writeValueAsString(Map.of("modelName",model,"modelCode",originalCode,"phoneBrandId",upstream,"status","ACTIVE")),p);
        jdbc.update("update plm_code_item set code_name=? where code_item_id=?","SM S777 "+UUID.randomUUID(),c);jdbc.update("insert into plm_phone_code_brand(code_item_id,brand_id,created_by) values(?,?,'it')",c,wrong);
        long sets=jdbc.queryForObject("select count(*) from plm_collaboration_mold_set",Long.class),products=jdbc.queryForObject("select count(*) from plm_product",Long.class);
        var before=issue(issues,c);assertThat(before).containsEntry("state","ready").containsEntry("autoAction","align_code");
        issues.resolve(new PhoneIdentityIssuesService.Resolve(List.of(selection(before)),"可信CRM原编码、品牌和完整组合型号一致"),null);
        assertThat(jdbc.queryForMap("select code_value,code_name from plm_code_item where code_item_id=?",c)).containsEntry("code_value",originalCode).containsEntry("code_name",model);
        assertThat(new com.yuewei.plm.module.productionmetadata.PhoneCodeMetadataRepository(jdbc,json).phone(c,false).get("brandIds")).isEqualTo(List.of(correct));
        assertThat(service.phone(p)).containsEntry("brandId",correct).containsEntry("codeItemId",c);
        assertThat(jdbc.queryForObject("select count(*) from plm_collaboration_mold_set",Long.class)).isEqualTo(sets);assertThat(jdbc.queryForObject("select count(*) from plm_product",Long.class)).isEqualTo(products);
        assertThat(issue(issues,c)).containsEntry("state","resolved");
    }
    @Test void untrustedSnapshotDoesNotResolveBrandConflictAndSelectionOnlyAccountCannotWrite() throws Exception {
        var issues=identityIssues();long b=brand(),wrong=brand(),c=code("phone_model"),p=phone(b,c);
        jdbc.update("insert into plm_phone_code_brand(code_item_id,brand_id,created_by) values(?,?,'it')",c,wrong);
        jdbc.update("update plm_phone_model_member set source_system='crm',source_snapshot=?::jsonb where id=?","{\"modelCode\":\"9999\",\"status\":\"ACTIVE\"}",p);
        var row=issue(issues,c);assertThat(row).containsEntry("state","pending");ordinary("market",null);
        assertThat(issue(issues,c)).containsEntry("canResolve",false);
        assertThatThrownBy(()->issues.editCode(new PhoneIdentityIssuesService.CodeEdit(c,row.get("sourceDigest").toString(),"Model",List.of(b),"人工依据"),null)).hasMessageContaining("工程职责");
    }
    @Test void verifiedBrandMergePreservesStableIdsAndLegacyMesLookupAndRejectsChangedPreview() throws Exception {
        var issues=identityIssues();long from=brand(),to=brand(),c=code("phone_model"),p=phone(from,c);String raw=service.brand(from).get("name").toString(),mes="mes-"+UUID.randomUUID();
        jdbc.update("update plm_phone_brand set source_system='mes',source_record_id=?,source_snapshot='{\"brand_code\":\"AL\"}'::jsonb where id=?",mes,from);
        jdbc.update("insert into plm_phone_code_brand(code_item_id,brand_id,created_by) values(?,?,'it')",c,from);
        var input=new PhoneIdentityIssuesService.BrandMerge(from,to,null,"明确的旧MES与CRM品牌对应依据");var plan=issues.previewBrandMerge(input);
        assertThat(plan.get("blockers")).isEqualTo(List.of());
        assertThatThrownBy(()->issues.mergeBrands(new PhoneIdentityIssuesService.BrandMerge(from,to,"0".repeat(64),input.reason()),null)).hasMessageContaining("预览后变化");
        issues.mergeBrands(new PhoneIdentityIssuesService.BrandMerge(from,to,plan.get("sourceDigest").toString(),input.reason()),null);
        assertThat(service.phone(p)).containsEntry("brandId",to).containsEntry("codeItemId",c);
        assertThat(service.brand(from)).containsEntry("status","disabled");assertThat(((List<?>)service.brand(to).get("aliases")).contains(raw)).isTrue();
        assertThat(jdbc.queryForMap("select source_system,source_record_id from plm_phone_brand where id=?",to)).containsEntry("source_system","mes").containsEntry("source_record_id",mes);
        assertThat(jdbc.queryForObject("select count(*) from plm_phone_code_brand where code_item_id=? and brand_id=?",Long.class,c,to)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from plm_phone_model_member where id=?",Long.class,p)).isEqualTo(1);
    }
    @Test void brandMergeCannotOverwriteDuplicateMembersOrIndependentLegacySourceIdentity() throws Exception {
        var issues=identityIssues();long from=brand(),to=brand();service.createPhone(new CreatePhone(from,"Same exact model",List.of(),null),null);service.createPhone(new CreatePhone(to,"Same exact model",List.of(),null),null);
        var plan=issues.previewBrandMerge(new PhoneIdentityIssuesService.BrandMerge(from,to,null,null));assertThat((List<?>)plan.get("blockers")).isNotEmpty();
        assertThatThrownBy(()->issues.mergeBrands(new PhoneIdentityIssuesService.BrandMerge(from,to,plan.get("sourceDigest").toString(),"不可覆盖原引用"),null)).hasMessageContaining("同名成员");
        assertThat(service.brand(from)).containsEntry("status","enabled");
    }
    @Test void brandMergeCanRetireOnlyUnusedLocalSameCodeDuplicatesAndAuditFailureRevertsEverything() throws Exception {
        var issues=identityIssues();long from=brand(),to=brand(),c=code("phone_model");String name="Exact model "+UUID.randomUUID();
        long unused=id(service.createPhone(new CreatePhone(from,name,List.of(),c),null)),retained=id(service.createPhone(new CreatePhone(to,name,List.of(),c),null));
        var plan=issues.previewBrandMerge(new PhoneIdentityIssuesService.BrandMerge(from,to,null,null));assertThat(plan.get("blockers")).isEqualTo(List.of());assertThat(plan.get("retiredDuplicatePhoneModelIds")).isEqualTo(List.of(unused));
        var input=new PhoneIdentityIssuesService.BrandMerge(from,to,plan.get("sourceDigest").toString(),"同一完整原名、原编码且旧本地成员没有业务引用");
        doThrow(new IllegalStateException("required identity audit failed")).when(audit).logRequired(argThat(cmd->"PHONE_IDENTITY_REVIEW".equals(cmd.getBusinessType())),any());
        assertThatThrownBy(()->nested(()->issues.mergeBrands(input,null))).hasMessageContaining("audit failed");assertThat(service.phone(unused)).containsEntry("status","enabled");assertThat(service.brand(from)).containsEntry("status","enabled");
        doCallRealMethod().when(audit).logRequired(any(),any());issues.mergeBrands(input,null);
        assertThat(service.phone(unused)).containsEntry("status","disabled").containsEntry("brandId",from).containsEntry("codeItemId",c);assertThat(service.phone(retained)).containsEntry("status","enabled").containsEntry("brandId",to).containsEntry("codeItemId",c);
    }
    @Test @SuppressWarnings("unchecked") void centralHistoryGroupsShowProductStyleOnceInsteadOfEveryColourSku() throws Exception {
        var issues=identityIssues();long b=brand(),c=code("phone_model"),p=phone(b,c),line=product(null,"product_line"),first=product(line,"model_variant"),second=product(line,"model_variant");
        String value=jdbc.queryForObject("select code_value from plm_code_item where code_item_id=?",String.class,c);
        jdbc.update("update plm_product set product_name='核对产品款式' where product_id=?",line);
        jdbc.update("update plm_product set model='Model X',phone_model_code=?,phone_model_member_id=?,product_name='Model X 黑色' where product_id=?",value,p,first);
        jdbc.update("update plm_product set model='Model X 5G',phone_model_code=?,phone_model_member_id=?,product_name='Model X 红色' where product_id=?",value,p,second);
        var q=new PhoneIdentityIssuesService.Query();q.setKeyword(value);q.setState("all");
        var row=((List<Map<String,Object>>)issues.page(q).get("content")).stream().filter(r->("history:code_many_names:"+value).equals(r.get("key"))).findFirst().orElseThrow();
        assertThat(row.get("productNames")).isEqualTo(List.of("核对产品款式"));assertThat(row.get("referenceCount")).isEqualTo(2L);assertThat(row.get("state")).isEqualTo("pending");
    }
    @Test @SuppressWarnings("unchecked") void explicitNestedBrandCorrectionPreservesCanonicalSourceIdsAndCannotBeUndoneByTheSameBadCrmName() throws Exception {
        var issues=identityIssues();long from=brand(),to=brand(),c=code("phone_model");String parent=service.brand(from).get("name").toString(),child=service.brand(to).get("name").toString(),name=child+" C75 4G",wrong=parent+" "+name;
        jdbc.update("update plm_code_item set code_name=? where code_item_id=?",name,c);long old=id(service.createPhone(new CreatePhone(to,name,List.of(),c),null)),canonical=id(service.createPhone(new CreatePhone(from,wrong,List.of(),c),null));
        var q=new PhoneIdentityIssuesService.Query();q.setState("all");q.setKeyword(service.phone(canonical).get("phoneModelCode").toString());var row=((List<Map<String,Object>>)issues.page(q).get("content")).stream().filter(r->("member:"+canonical).equals(r.get("key"))).findFirst().orElseThrow();assertThat(row).containsEntry("state","ready").containsEntry("autoAction","align_member");
        var input=new PhoneIdentityIssuesService.Resolve(List.of(selection(row)),"原编码完整子品牌与官方型号一致，旧父品牌归类有误");
        doThrow(new IllegalStateException("required identity audit failed")).when(audit).logRequired(argThat(cmd->"PHONE_IDENTITY_REVIEW".equals(cmd.getBusinessType())),any());
        assertThatThrownBy(()->nested(()->issues.resolve(input,null))).hasMessageContaining("audit failed");assertThat(service.phone(canonical)).containsEntry("brandId",from).containsEntry("modelName",wrong);assertThat(service.phone(old)).containsEntry("status","enabled");
        doCallRealMethod().when(audit).logRequired(any(),any());issues.resolve(input,null);var corrected=service.phone(canonical);assertThat(corrected).containsEntry("brandId",to).containsEntry("modelName",name).containsEntry("codeItemId",c);assertThat(service.phone(old)).containsEntry("status","disabled").containsEntry("modelName","C75 4G");assertThat(((List<?>)corrected.get("aliases")).contains(wrong)).isTrue();
        var importer=new com.yuewei.plm.module.masterdata.MasterDataImportService(jdbc,json,null,new PilotAccessService(jdbc,json),service,null,audit);
        assertThat((Boolean)org.springframework.test.util.ReflectionTestUtils.invokeMethod(importer,"verifiedPhoneOverride",canonical,from,wrong,corrected)).isTrue();
        assertThat((Boolean)org.springframework.test.util.ReflectionTestUtils.invokeMethod(importer,"verifiedPhoneOverride",canonical,from,wrong+" revised",corrected)).isFalse();
        assertThat(issues.resolve(input,null).get(0)).containsEntry("status","already_resolved");
    }
    @Test @SuppressWarnings("unchecked") void nestedBrandCorrectionCannotRetireAnExistingMemberWithBusinessReferences() throws Exception {
        var issues=identityIssues();long from=brand(),to=brand(),c=code("phone_model");String parent=service.brand(from).get("name").toString(),child=service.brand(to).get("name").toString(),name=child+" C75 4G";
        jdbc.update("update plm_code_item set code_name=? where code_item_id=?",name,c);long used=id(service.createPhone(new CreatePhone(to,name,List.of(),c),null)),wrong=id(service.createPhone(new CreatePhone(from,parent+" "+name,List.of(),c),null)),line=product(null,"product_line");jdbc.update("insert into plm_product_phone_model(product_id,phone_model_id,source_system,created_by) values(?,?,'plm','it')",line,used);
        var q=new PhoneIdentityIssuesService.Query();q.setState("all");q.setKeyword(service.phone(wrong).get("phoneModelCode").toString());var row=((List<Map<String,Object>>)issues.page(q).get("content")).stream().filter(r->("member:"+wrong).equals(r.get("key"))).findFirst().orElseThrow();assertThat(row).containsEntry("state","pending");assertThatThrownBy(()->issues.resolve(new PhoneIdentityIssuesService.Resolve(List.of(selection(row)),"业务引用不得覆盖"),null)).hasMessageContaining("缺明确依据");assertThat(service.phone(used)).containsEntry("status","enabled");
    }
    @Test void productionStateIsSharedByCodeMembersWithoutDisablingNewCompatibilityAndProductsRemainIndependent() {
        long b=brand(),c=code("phone_model"),first=phone(b,c),second=phone(b,c),unassigned=phone(b,null),line=product(null,"product_line"),sku=product(line,"sku");
        assertThat(service.phone(first)).containsEntry("productionStatus","unknown");assertThat(service.phone(unassigned)).containsEntry("productionStatus","unknown");
        production().phone(c,patch("productionStatus","unknown","discontinued"),null);
        assertThat(service.phone(first)).containsEntry("productionStatus","discontinued");assertThat(service.phone(second)).containsEntry("productionStatus","discontinued");
        assertThat(service.requireEnabledModels(List.of(first,second))).hasSize(2);assertThat(service.phone(phone(b,c))).containsEntry("productionStatus","discontinued");
        assertThat(productionState("plm_product","product_id",line)).isEqualTo("unknown");production().product(line,patch("productionStatus","unknown","discontinued"),null);
        assertThat(productionState("plm_product","product_id",sku)).isEqualTo("unknown");assertThat(jdbc.queryForMap("select status,lock_status from plm_product where product_id=?",line)).containsEntry("status","archived").containsEntry("lock_status","frozen");
        production().phone(c,patch("productionStatus","discontinued","active"),null);assertThat(service.phone(first)).containsEntry("productionStatus","active");
    }
    @Test void groupBrandsDeduplicateManualAndMemberFactsAndUnclassifiedLegacyIsNotGuessed() {
        long c=code("phone_model"),b=brand(),extra=brand();var repo=new com.yuewei.plm.module.productionmetadata.PhoneCodeMetadataRepository(jdbc,json);
        assertThat(repo.phone(c,false).get("brandIds")).isEqualTo(List.of());phone(b,c);
        production().phone(c,patch("brandIds",List.of(b),List.of(extra,b,extra)),null);
        assertThat((List<Long>)repo.phone(c,false).get("brandIds")).containsExactlyElementsOf(new java.util.TreeSet<>(List.of(b,extra)));
        assertThat(jdbc.queryForList("select brand_id from plm_phone_code_brand where code_item_id=?",Long.class,c)).containsExactly(extra);
        assertThatThrownBy(()->nested(()->production().phone(c,patch("brandIds",List.of(b,extra),List.of(extra)),null))).hasMessageContaining("成员已确认");
        var vo=com.yuewei.plm.module.code.vo.CodeItemVO.builder().codeItemId(c).codeType("phone_model").build();repo.enrich(List.of(vo));
        assertThat(vo.getBrands()).hasSize(2);assertThat(vo.getBrands()).anySatisfy(row->{assertThat(((Number)row.get("brandId")).longValue()).isEqualTo(b);assertThat(row).containsEntry("memberConfirmed",true);});
    }
    @Test void serverCodePagingOrdersFourDigitsDescendingAndCombinesBrandStateWithoutLosingUnclassified() throws Exception {
        long a=code("phone_model"),b=code("phone_model"),c=code("phone_model"),brand=brand();String tag="Ordering-"+UUID.randomUUID();
        for(long id:List.of(a,b,c))jdbc.update("update plm_code_item set code_name=?,sort_order=? where code_item_id=?",tag+id,9999-id,id);
        phone(brand,a);phone(brand,b);production().phone(b,patch("productionStatus","unknown","discontinued"),null);
        var query=new com.yuewei.plm.module.code.dto.CodeItemQueryDTO();query.setCodeType("phone_model");query.setKeyword(tag);query.setSize(1L);var codes=codeQueries();
        assertThat(codes.page(query).getContent()).extracting(v->v.getCodeItemId()).containsExactly(c);query.setPage(2L);assertThat(codes.page(query).getContent()).extracting(v->v.getCodeItemId()).containsExactly(b);
        query.setPage(1L);query.setBrandId(brand);query.setProductionStatus("discontinued");var result=codes.page(query);assertThat(result.getTotalElements()).isEqualTo(1);assertThat(result.getContent()).extracting(v->v.getCodeValue()).containsExactly(jdbc.queryForObject("select code_value from plm_code_item where code_item_id=?",String.class,b));
        query.setBrandId(null);query.setProductionStatus(null);query.setUnclassified(true);assertThat(codes.page(query).getContent()).extracting(v->v.getCodeItemId()).containsExactly(c);
        var memberQuery=new Query();memberQuery.setBrandId(brand);assertThat(service.phones(memberQuery).getContent()).extracting(v->v.get("codeItemId")).containsExactly(b,a);
        phone(brand,null);memberQuery.setProductionStatus("unknown");assertThat(service.phones(memberQuery).getContent()).extracting(v->v.get("codeItemId")).containsExactly(a,null);
    }
    @Test void colorsKeepTheirOwnSortOrderAndOrdinaryMapperSavesCannotOverwriteManualProductionFlags() throws Exception {
        long first=code("color"),second=code("color"),phone=code("phone_model");String tag="Colors-"+UUID.randomUUID();jdbc.update("update plm_code_item set code_name=?,sort_order=8 where code_item_id=?",tag+first,first);jdbc.update("update plm_code_item set code_name=?,sort_order=2 where code_item_id=?",tag+second,second);
        var query=new com.yuewei.plm.module.code.dto.CodeItemQueryDTO();query.setCodeType("color");query.setKeyword(tag);var codes=codeQueries();assertThat(codes.page(query).getContent()).extracting(v->v.getCodeItemId()).containsExactly(second,first);
        production().phone(phone,patch("productionStatus","unknown","discontinued"),null);var phoneRepo=(com.yuewei.plm.module.code.repository.CodeItemRepository)org.springframework.test.util.ReflectionTestUtils.getField(codes,"repository");var stale=phoneRepo.selectById(phone);stale.setProductionStatus("unknown");stale.setCodeName("Renamed-"+UUID.randomUUID());phoneRepo.updateById(stale);assertThat(productionState("plm_code_item","code_item_id",phone)).isEqualTo("discontinued");
        long p=product(null,"product_line");production().product(p,patch("productionStatus","unknown","active"),null);
        var productRepo=productionSqlSession.getMapper(com.yuewei.plm.repository.ProductRepository.class);var staleProduct=productRepo.selectById(p);staleProduct.setProductionStatus("unknown");staleProduct.setProductName("同步后的名称");productRepo.updateById(staleProduct);assertThat(productionState("plm_product","product_id",p)).isEqualTo("active");
        assertThat(com.yuewei.plm.repository.entity.Product.class.getDeclaredField("productionStatus").getAnnotation(com.baomidou.mybatisplus.annotation.TableField.class).updateStrategy()).isEqualTo(com.baomidou.mybatisplus.annotation.FieldStrategy.NEVER);
    }
    @Test void metadataRequiresReasonOriginalAndPermittedFieldsAndRejectsStaleUpdates() {
        long c=code("phone_model");var p=production();
        assertThatThrownBy(()->nested(()->p.phone(c,new Patch(Map.of("productionStatus",json.valueToTree("active")),Map.of("productionStatus",json.valueToTree("unknown"))," "),null))).hasMessageContaining("原因");
        assertThatThrownBy(()->nested(()->p.phone(c,new Patch(Map.of("productionStatus",json.valueToTree("active")),Map.of(),"确认"),null))).hasMessageContaining("原值");
        assertThatThrownBy(()->nested(()->p.phone(c,patch("status","enabled","disabled"),null))).hasMessageContaining("不可维护");
        p.phone(c,patch("productionStatus","unknown","active"),null);
        assertThatThrownBy(()->nested(()->p.phone(c,patch("productionStatus","unknown","discontinued"),null))).isInstanceOf(BusinessException.class).hasMessageContaining("已被修改");
        var captor=ArgumentCaptor.forClass(OperationLogCreateCommand.class);verify(audit).logRequired(captor.capture(),eq(admin));var log=captor.getValue();assertThat(log.getDetailJson()).contains("unknown","active","人工更正");assertThat(log.getBusinessType()).isEqualTo("PHONE_CODE_GROUP");
    }
    @Test void batchConflictAndAuditFailureRollbackEveryWrittenRowUnderActualTransactionalProxy() {
        long first=code("phone_model"),second=code("phone_model");var p=production();
        var conflict=new com.yuewei.plm.module.productionmetadata.ProductionMetadataService.Batch(List.of(item(first,"unknown","active"),item(second,"active","discontinued")),"批量确认");
        assertThatThrownBy(()->nested(()->p.phoneBatch(conflict,null))).isInstanceOf(BusinessException.class);assertThat(productionState("plm_code_item","code_item_id",first)).isEqualTo("unknown");
        reset(audit);var n=new java.util.concurrent.atomic.AtomicInteger();when(audit.logRequired(any(),any())).thenAnswer(call->{if(n.incrementAndGet()==2)throw new IllegalStateException("audit failure");return null;});
        var batch=new com.yuewei.plm.module.productionmetadata.ProductionMetadataService.Batch(List.of(item(first,"unknown","active"),item(second,"unknown","active")),"批量确认");
        assertThatThrownBy(()->nested(()->p.phoneBatch(batch,null))).hasMessageContaining("audit failure");for(long id:List.of(first,second))assertThat(productionState("plm_code_item","code_item_id",id)).isEqualTo("unknown");
    }
    @Test void engineeringPermissionUsesRealIdentityAndProductScopeFailureRollsBackWholeBatch() {
        long first=product(null,"product_line"),second=product(null,"product_line"),c=code("phone_model");long user=ordinary("engineering","domestic");var p=production();assertThat(p.options()).containsEntry("canMaintain",true);p.phone(c,patch("productionStatus","unknown","active"),null);
        jdbc.update("insert into plm_pilot_scope(user_id,entity_type,entity_id) values(?,'product',?)",user,first);
        var batch=new com.yuewei.plm.module.productionmetadata.ProductionMetadataService.Batch(List.of(item(first,"unknown","active"),item(second,"unknown","active")),"工程核对");assertThatThrownBy(()->nested(()->p.productBatch(batch,null))).hasMessageContaining("未获授权");assertThat(productionState("plm_product","product_id",first)).isEqualTo("unknown");
        ordinary("market",null);assertThat(p.options()).containsEntry("canMaintain",false);assertThatThrownBy(()->nested(()->p.phone(c,patch("productionStatus","active","discontinued"),null))).hasMessageContaining("工程职责");
    }
    @Test void batchRejectsOverOneHundredOrDuplicateTargetsWithoutWrites() {
        long c=code("phone_model");var p=production();var item=item(c,"unknown","active");
        assertThatThrownBy(()->nested(()->p.phoneBatch(new com.yuewei.plm.module.productionmetadata.ProductionMetadataService.Batch(Collections.nCopies(101,item),"核对"),null))).hasMessageContaining("100");
        assertThatThrownBy(()->nested(()->p.phoneBatch(new com.yuewei.plm.module.productionmetadata.ProductionMetadataService.Batch(List.of(item,item),"核对"),null))).hasMessageContaining("重复");verify(audit,never()).logRequired(any(),any());
    }
    List<com.yuewei.plm.module.productionmetadata.ProductionMetadataService.BrandReviewItem> brandReviewItems(Map<String,Object> preview,Collection<Long> ids) {
        return ((List<Map<String,Object>>)preview.get("items")).stream().filter(row->ids.contains(((Number)row.get("id")).longValue())).map(row->
            new com.yuewei.plm.module.productionmetadata.ProductionMetadataService.BrandReviewItem(((Number)row.get("id")).longValue(),row.get("codeValue").toString(),row.get("codeName").toString(),(List<Long>)row.get("originalBrandIds"),(List<Long>)row.get("brandIds"))).toList();
    }
    com.yuewei.plm.module.productionmetadata.ProductionMetadataService.BrandReviewBatch brandReviewBatch(List<com.yuewei.plm.module.productionmetadata.ProductionMetadataService.BrandReviewItem> items) {
        return new com.yuewei.plm.module.productionmetadata.ProductionMetadataService.BrandReviewBatch(items,"按维护的品牌别名核对分类，保留原型号");
    }
    @Test void oneClickBrandReviewCoversTheWholeUnclassifiedScopeOverOneHundredAndOnlyWritesBrandMetadata() {
        long b=brand();String name=service.brand(b).get("name").toString();var ids=new ArrayList<Long>();var rawNames=new HashMap<Long,String>();
        for(int i=0;i<101;i++){long c=code("phone_model");String original=name+" A"+i+" 4G/A"+i+" 5G";jdbc.update("update plm_code_item set code_name=? where code_item_id=?",original,c);ids.add(c);rawNames.put(c,original);}
        long memberCount=jdbc.queryForObject("select count(*) from plm_phone_model_member",Long.class),setCount=jdbc.queryForObject("select count(*) from plm_collaboration_mold_set",Long.class),codeCount=jdbc.queryForObject("select count(*) from plm_code_item",Long.class);
        var p=production();var preview=p.phoneBrandReview();var items=brandReviewItems(preview,ids);assertThat(items).hasSize(101);assertThat(items).allSatisfy(item->assertThat(item.brandIds()).containsExactly(b));
        reset(audit);assertThat(p.applyPhoneBrandReview(brandReviewBatch(items),null)).containsEntry("appliedCount",101);
        for(long c:ids){assertThat(new com.yuewei.plm.module.productionmetadata.PhoneCodeMetadataRepository(jdbc,json).phone(c,false)).containsEntry("brandIds",List.of(b)).containsEntry("codeName",rawNames.get(c)).containsEntry("productionStatus","unknown");}
        assertThat(jdbc.queryForObject("select count(*) from plm_phone_model_member",Long.class)).isEqualTo(memberCount);assertThat(jdbc.queryForObject("select count(*) from plm_collaboration_mold_set",Long.class)).isEqualTo(setCount);assertThat(jdbc.queryForObject("select count(*) from plm_code_item",Long.class)).isEqualTo(codeCount);
        assertThat(brandReviewItems(p.phoneBrandReview(),ids)).isEmpty();var logs=ArgumentCaptor.forClass(OperationLogCreateCommand.class);verify(audit,times(101)).logRequired(logs.capture(),eq(admin));assertThat(logs.getAllValues()).allSatisfy(log->assertThat(log.getDetailJson()).contains("explicit-brand-prefix","evidence","before","after"));
    }
    @Test void brandReviewStaleOriginalChangedRuleAndDuplicateTargetsCannotWriteAnyRow() {
        long b=brand(),first=code("phone_model"),second=code("phone_model");String name=service.brand(b).get("name").toString();for(long c:List.of(first,second))jdbc.update("update plm_code_item set code_name=? where code_item_id=?",name+" A"+c,c);
        var p=production();var items=brandReviewItems(p.phoneBrandReview(),List.of(first,second));
        jdbc.update("update plm_code_item set code_name=? where code_item_id=?",name+" A13",second);
        assertThatThrownBy(()->nested(()->p.applyPhoneBrandReview(brandReviewBatch(items),null))).hasMessageContaining("重新预览");assertThat(new com.yuewei.plm.module.productionmetadata.PhoneCodeMetadataRepository(jdbc,json).brandIds(first,false)).isEmpty();
        var refreshed=brandReviewItems(p.phoneBrandReview(),List.of(first,second));jdbc.update("update plm_phone_brand set status='disabled' where id=?",b);
        assertThatThrownBy(()->nested(()->p.applyPhoneBrandReview(brandReviewBatch(refreshed),null))).hasMessageContaining("重新预览");
        assertThatThrownBy(()->nested(()->p.applyPhoneBrandReview(brandReviewBatch(List.of(items.get(0),items.get(0))),null))).hasMessageContaining("重复");
        for(long c:List.of(first,second))assertThat(new com.yuewei.plm.module.productionmetadata.PhoneCodeMetadataRepository(jdbc,json).brandIds(c,false)).isEmpty();
    }
    @Test void brandReviewAuditFailureRollsBackEveryBrandAndMarketCannotPreviewOrApply() {
        long b=brand(),first=code("phone_model"),second=code("phone_model");String name=service.brand(b).get("name").toString();for(long c:List.of(first,second))jdbc.update("update plm_code_item set code_name=? where code_item_id=?",name+" A"+c,c);
        var p=production();var batch=brandReviewBatch(brandReviewItems(p.phoneBrandReview(),List.of(first,second)));reset(audit);var count=new java.util.concurrent.atomic.AtomicInteger();when(audit.logRequired(any(),any())).thenAnswer(call->{if(count.incrementAndGet()==2)throw new IllegalStateException("mandatory audit failure");return null;});
        assertThatThrownBy(()->nested(()->p.applyPhoneBrandReview(batch,null))).hasMessageContaining("audit failure");for(long c:List.of(first,second))assertThat(new com.yuewei.plm.module.productionmetadata.PhoneCodeMetadataRepository(jdbc,json).brandIds(c,false)).isEmpty();
        ordinary("market",null);assertThatThrownBy(()->p.phoneBrandReview()).hasMessageContaining("工程职责");assertThatThrownBy(()->nested(()->p.applyPhoneBrandReview(batch,null))).hasMessageContaining("工程职责");
    }
    long mesSource(long id) {return 900000L+id;}
    Map<Long,String> mesNames() {return Map.of(mesSource(5),mesDisplayName);}
    long mesFixturePhones(PhoneModelMesSnapshotService.Snapshot snapshot) {
        var ids=snapshot.phones().stream().map(row->row.path("id").asText()).toList();
        return jdbc.queryForObject("select count(*) from plm_phone_model_member where source_system='mes' and source_record_id in ("+String.join(",",Collections.nCopies(ids.size(),"?"))+")",Long.class,ids.toArray());
    }
    com.fasterxml.jackson.databind.JsonNode mesBrand() {
        return json.valueToTree(Map.of("id",mesSource(5),"resource_id",mesSource(5),"brand_code","IP","brand_name_en","Iphone","status","enabled"));
    }
    com.fasterxml.jackson.databind.JsonNode mesPhone(long source,String name,String code,String brand) {
        var row=new LinkedHashMap<String,Object>();row.put("id",mesSource(source));row.put("resource_id",mesSource(source));row.put("model_name",name);row.put("model_code",code);row.put("brand_code",brand);row.put("status","enabled");return json.valueToTree(row);
    }
    PhoneModelMesSnapshotService.Snapshot mesSnapshot(List<com.fasterxml.jackson.databind.JsonNode> phones) {
        return new PhoneModelMesSnapshotService.Snapshot("https://lemos-case.com/mes/master-data/phone-model-codes",List.of(mesBrand()),phones);
    }
    PhoneModelMesSnapshotService.Confirmation confirmation(Map<String,Object> preview,List<Long> phones) {
        return new PhoneModelMesSnapshotService.Confirmation(preview.get("previewDigest").toString(),List.of(mesSource(5)),phones.stream().map(this::mesSource).toList(),mesNames(),"隔离测试来源命名空间，原编码及Iphone来源快照保留");
    }
    @Test void trustedMesInitialisationPreservesDistinctOriginalCodesSourceIdsAndIsIdempotent() {
        long firstCode=code("phone_model"),secondCode=code("phone_model");
        var first=jdbc.queryForMap("select code_name,code_value from plm_code_item where code_item_id=?",firstCode);var second=jdbc.queryForMap("select code_name,code_value from plm_code_item where code_item_id=?",secondCode);
        var snapshot=mesSnapshot(List.of(mesPhone(8944,first.get("code_name").toString(),first.get("code_value").toString(),"IP"),mesPhone(8992,second.get("code_name").toString(),second.get("code_value").toString(),"IP")));
        long sets=jdbc.queryForObject("select count(*) from plm_collaboration_mold_set",Long.class);var initial=mes().preview(snapshot,mesNames());var result=mes().confirm(snapshot,confirmation(initial,List.of(8944L,8992L)),null);
        var targets=(Map<Long,Long>)result.get("phoneTargets");assertThat(service.phone(targets.get(mesSource(8944)))).containsEntry("codeItemId",firstCode).containsEntry("sourceSystem","mes").containsEntry("sourceRecordId",Long.toString(mesSource(8944)));
        assertThat(service.phone(targets.get(mesSource(8992)))).containsEntry("codeItemId",secondCode).containsEntry("brandName",mesDisplayName);
        var again=mes().preview(snapshot,mesNames());assertThat(mes().confirm(snapshot,confirmation(again,List.of(8944L,8992L)),null).get("phoneTargets")).isEqualTo(targets);
        assertThat(jdbc.queryForObject("select count(*) from plm_collaboration_mold_set",Long.class)).isEqualTo(sets);
        assertThat(jdbc.queryForObject("select source_snapshot->>'brand_name_en' from plm_phone_brand where source_system='mes' and source_record_id=?",String.class,Long.toString(mesSource(5)))).isEqualTo("Iphone");
    }
    @Test void mesSlashMissingBrandAndCodeNameMismatchAreReviewOnlyNotGuessed() {
        long c=code("phone_model");String value=jdbc.queryForObject("select code_value from plm_code_item where code_item_id=?",String.class,c);
        var snapshot=mesSnapshot(List.of(mesPhone(1,"iPhone17Pro/iPhone18Pro",value,"IP"),mesPhone(2,"iPhone19Pro",value,null),mesPhone(3,"iPhone20Pro",value,"IP")));
        var preview=mes().preview(snapshot,mesNames());var phones=(List<Map<String,Object>>)preview.get("phones");assertThat(phones).allSatisfy(row->assertThat(row.get("state")).isEqualTo("review"));
        assertThatThrownBy(()->mes().confirm(snapshot,confirmation(preview,List.of(1L)),null)).isInstanceOf(BusinessException.class).hasMessageContaining("待人工核对");
        assertThat(mesFixturePhones(snapshot)).isZero();
    }
    @Test void staleMesPreviewRefusesLocalIdentityRaceWithoutPartialInitialisation() {
        long c=code("phone_model");var code=jdbc.queryForMap("select code_name,code_value from plm_code_item where code_item_id=?",c);
        var snapshot=mesSnapshot(List.of(mesPhone(1,code.get("code_name").toString(),code.get("code_value").toString(),"IP")));var preview=mes().preview(snapshot,mesNames());
        service.createBrand(new CreateBrand(mesDisplayName),null);
        assertThatThrownBy(()->mes().confirm(snapshot,confirmation(preview,List.of(1L)),null)).isInstanceOf(BusinessException.class).hasMessageContaining("重新预览");
        assertThat(mesFixturePhones(snapshot)).isZero();
    }
    @Test void duplicateMesPhoneIdentityReviewsAllRowsInsteadOfFirstRowWins() {
        long c=code("phone_model");var code=jdbc.queryForMap("select code_name,code_value from plm_code_item where code_item_id=?",c);
        var snapshot=mesSnapshot(List.of(mesPhone(1,code.get("code_name").toString(),code.get("code_value").toString(),"IP"),mesPhone(2,code.get("code_name").toString(),code.get("code_value").toString(),"IP")));
        assertThat((List<Map<String,Object>>)mes().preview(snapshot,mesNames()).get("phones")).allSatisfy(row->assertThat(row.get("state")).isEqualTo("review"));
    }
    @Test void brandAliasesAreSearchableAuditedAndCannotClaimAnotherBrandsIdentity() {
        String alias="Alias-"+UUID.randomUUID();var first=service.createBrand(new CreateBrand("Brand-"+UUID.randomUUID(),List.of(alias)),null);
        var q=new Query();q.setKeyword(alias.toLowerCase(Locale.ROOT));assertThat(service.brands(q).getContent()).extracting(r->r.get("id")).containsExactly(first.get("id"));
        assertThatThrownBy(()->service.createBrand(new CreateBrand("Other-"+UUID.randomUUID(),List.of(alias)),null)).isInstanceOf(BusinessException.class).hasMessageContaining("别名");
        assertThatThrownBy(()->service.createBrand(new CreateBrand(alias),null)).isInstanceOf(BusinessException.class).hasMessageContaining("别名");
        assertThat(service.patchBrand(id(first),patch("aliases",List.of(alias),List.of("Renamed-"+alias)),null)).containsEntry("status","saved");
    }
    @Test void explicitPhoneSelectionAcceptsConfirmedLeadingSeriesAliasWithoutDroppingVariants() {
        long b=appleBrand();var current=service.brand(b);
        service.patchBrand(b,patch("aliases",current.get("aliases"),List.of("IP","iPhone")),null);
        long c=code("phone_model");var p=service.createPhone(new CreatePhone(b,"iPhone IT 18 Pro 5G",List.of(),c),null);
        assertThat(service.requireSelectedPhone(id(p),c,null,"IP IT 18 Pro 5G").get("phoneModelId")).isEqualTo(id(p));
        assertThatThrownBy(()->service.requireSelectedPhone(id(p),c,null,"IP IT 18 Pro 4G")).isInstanceOf(BusinessException.class).hasMessageContaining("型号名称");
    }

}
