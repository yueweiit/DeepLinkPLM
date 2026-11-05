package com.yuewei.plm.module.manufacturing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuewei.plm.common.security.CurrentUser;
import com.yuewei.plm.common.security.CurrentUserContext;
import com.yuewei.plm.common.util.ProductBusinessCodeGenerator;
import com.yuewei.plm.module.moldreference.PhoneModelReferenceRepository;
import com.yuewei.plm.module.moldreference.PhoneModelReferenceService;
import com.yuewei.plm.module.operationlog.service.OperationLogService;
import com.yuewei.plm.module.pilot.PilotAccessService;
import com.yuewei.plm.module.product.service.ResearchProductCodingService;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Only an empty, disposable loopback database is accepted; each test owns and removes its schema. */
@EnabledIfEnvironmentVariable(named="PLM_MANUFACTURING_TEST_URL",matches="jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/plm_manufacturing_qa")
class ManufacturingIdentityLocalIntegrationTest {
    JdbcTemplate jdbc,admin;
    TransactionTemplate tx;
    String schema;
    ManufacturingIdentityService service;
    PhoneModelReferenceService phones;
    OperationLogService audit;
    @BeforeEach void setup() throws Exception {
        String url=System.getenv("PLM_MANUFACTURING_TEST_URL"),password=System.getenv("PLM_MANUFACTURING_TEST_PASSWORD");
        admin=new JdbcTemplate(new DriverManagerDataSource(url,"postgres",password));schema="manufacturing_"+UUID.randomUUID().toString().replace("-","");admin.execute("create schema "+schema);
        var ds=new DriverManagerDataSource(url+"?currentSchema="+schema,"postgres",password);jdbc=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        jdbc.execute("""
            create table sys_user(user_id bigint primary key,username text,display_name text,all_permissions boolean,deleted_flag int default 0,status text default 'active');
            insert into sys_user values(1,'engineering-qa','工程QA',true,0,'active'),(2,'restricted-qa','受限工程QA',false,0,'active');
            create table plm_pilot_account(user_id bigint primary key,role_code text,permissions jsonb,all_objects boolean);
            insert into plm_pilot_account values(2,'engineering','["collaboration:view","product:view","project:view"]',false);
            create table plm_pilot_scope(user_id bigint,entity_type text,entity_id bigint);
            create table plm_collaboration_assignment(user_id bigint,field_group text);
            insert into plm_collaboration_assignment values(2,'engineering');
            create table plm_product(product_id bigint primary key,parent_product_id bigint,product_type text,product_name text,product_code text,phone_model_code text,
              product_specific_code text,color_code text,finished_product_code text,status text default 'released',lock_status text default 'normal',master_data_status text default 'active',deleted_flag int default 0);
            create table plm_project_requirement_form(project_id bigint primary key,hole_type text);
            insert into plm_project_requirement_form values(100,'大孔'),(200,'precision');
            insert into plm_product(product_id,parent_product_id,product_type,product_name,product_code,phone_model_code,product_specific_code,color_code,finished_product_code) values
              (50,null,'product_line','原款','NFA4030',null,'FA',null,null),
              (51,null,'product_line','其他款','NFB4030',null,'FB',null,null),
              (100,50,'model_variant','原1145制造型号','PRD-1145','1145','FA',null,null),
              (101,100,'sku','原1145黑色成品','SKU-1145-02','1145','FA','02','NFA4030114502'),
              (102,100,'sku','原1145白色成品','SKU-1145-04','1145','FA','04','NFA4030114504'),
              (200,50,'model_variant','原1218独立精孔制造型号','PRD-1218','1218','FA',null,null),
              (201,200,'sku','1218精孔成品','SKU-1218-02','1218','FA','02','NFA4030121802');
            create table plm_code_item(code_item_id bigint primary key,code_type text,code_value text,code_name text,status text default 'enabled',production_status text default 'unknown',deleted_flag int default 0);
            insert into plm_code_item(code_item_id,code_type,code_value,code_name) values(11,'phone_model','1145','IP 17 PRO MAX'),(12,'phone_model','1218','IP 18 PRO MAX');
            create table plm_phone_brand(id bigint primary key,name text,status text default 'enabled',aliases text[] default '{}',prefixes jsonb default '[]',source_system text,source_record_id text,source_snapshot jsonb default '{}',created_at timestamptz default now(),updated_at timestamptz default now());
            insert into plm_phone_brand(id,name) values(1,'Apple');
            create table plm_phone_model_member(id bigint primary key,brand_id bigint,model_name text,code_item_id bigint,status text default 'enabled',aliases text[] default '{}',source_system text,source_record_id text,source_snapshot jsonb default '{}',created_at timestamptz default now(),updated_at timestamptz default now());
            insert into plm_phone_model_member(id,brand_id,model_name,code_item_id) values(17,1,'iPhone 17 Pro Max',11),(18,1,'iPhone 18 Pro Max',12);
            create table plm_collaboration_item(item_id bigint primary key,product_line_id bigint,style_name text,mold_name text,engraved_code text,deleted_flag int default 0,master_data_status text default 'active');
            insert into plm_collaboration_item values(30,50,'原款','原大孔模','1145',0,'active');
            create table plm_collaboration_item_project(item_id bigint,project_id bigint);
            create table plm_collaboration_mold_set(mold_set_id bigint primary key,item_id bigint,set_no int,deleted_flag int default 0,master_data_status text default 'active',unique(item_id,mold_set_id));
            insert into plm_collaboration_mold_set values(40,30,1,0,'active'),(41,30,2,0,'active');
            create table plm_product_mold_code(product_mold_code_id bigint primary key,mold_code text,deleted_flag int default 0);
            create table plm_collaboration_mold_part(mold_set_id bigint,part_type text,original_mold_code text,product_mold_code_id bigint,inventory_id bigint,status text,physical_status text,factory_arrival_date date,acceptance_status text,acceptance_date date);
            insert into plm_collaboration_mold_part values(40,'PC','MFA201145',null,null,'ready','confirmed',null,'passed',null),(40,'TPU','MFA101145',null,null,'ready','confirmed',null,'passed',null),
              (41,'PC','MFA201145',null,null,'ready','confirmed',null,'passed',null),(41,'TPU','MFA101145',null,null,'ready','confirmed',null,'passed',null);
            create table plm_collaboration_phone_set_compatibility(item_id bigint,phone_model_id bigint,mold_set_id bigint,pc_confirmed boolean,tpu_confirmed boolean,pc_evidence text,tpu_evidence text);
            insert into plm_collaboration_phone_set_compatibility values(30,17,40,true,true,'17PC核对','17TPU核对'),(30,18,40,true,true,'18PC核对','18TPU核对'),(30,18,41,true,true,'第二套PC核对','第二套TPU核对');
            create table plm_collaboration_history(history_id bigserial primary key,item_id bigint,entity_type text,entity_id bigint,field_name text,before_value jsonb,after_value jsonb,reason text,actor_id bigint,actor_name text);
            """);
        try(var connection=ds.getConnection()){ScriptUtils.executeSqlScript(connection,new ClassPathResource("db/migration/V20261105_1800__manufacturing_relations.sql"));}
        CurrentUserContext.set(new CurrentUser(1L,"engineering-qa","工程QA",true));var json=new ObjectMapper().findAndRegisterModules();var access=new PilotAccessService(jdbc,json);audit=mock(OperationLogService.class);
        phones=new PhoneModelReferenceService(jdbc,json,new PhoneModelReferenceRepository(jdbc),access,audit);
        service=new ManufacturingIdentityService(jdbc,json,access,phones,audit,new ProductBusinessCodeGenerator());
    }
    @AfterEach void cleanup(){CurrentUserContext.clear();if(admin!=null&&schema!=null)admin.execute("drop schema "+schema+" cascade");}
    ManufacturingIdentityService.WriteInput input(String hole,boolean material,long set,Long version,String status){
        var fit=phones.compatibleMolds(18,50).stream().filter(row->n(row.get("moldSetId"))==set).findFirst().orElseThrow();
        return new ManufacturingIdentityService.WriteInput(version,50,30,set,18,hole,"",material,material?100L:null,material?List.of(101L,102L):List.of(),"本规格实物核对",material?"原成品和加工BOM均核对可共用":"",fit.get("compatibilityDigest").toString(),"真实工程确认",status);
    }
    Map<String,Object> create(String hole,boolean shared){return tx.execute(t->service.save(null,input(hole,shared,40,null,"active"),null));}
    ManufacturingIdentityService.ResolveInput query(String hole,Long id,Long version){return new ManufacturingIdentityService.ResolveInput(50,18,hole,"",id,version);}
    long count(String table){return jdbc.queryForObject("select count(*) from "+table,Long.class);}
    long n(Object value){return ((Number)value).longValue();}
    @Test void realScopeKeepsCustomer1218AndOriginalManufacturing1145WithoutCreatingProductsOrStock(){
        long products=count("plm_product"),sets=count("plm_collaboration_mold_set");var saved=create("large",true);long id=n(saved.get("id"));long history=count("plm_collaboration_history");
        var result=tx.execute(t->service.requireResolved(query("large",id,1L)));
        assertThat(result).containsEntry("status","reuse").containsEntry("manufacturingPhoneCode","1145").containsEntry("manufacturingProductId",100L).containsEntry("skuIds",List.of(101,102)).containsEntry("physicalCode","MS-000040").containsEntry("setNo",1);
        assertThat((Map<String,Object>)result.get("businessPhone")).containsEntry("phoneModelCode","1218");assertThat(service.resolvedSkus(result)).extracting(row->row.get("code")).containsExactly("NFA4030114502","NFA4030114504");
        assertThat(count("plm_product")).isEqualTo(products);assertThat(count("plm_collaboration_mold_set")).isEqualTo(sets);assertThat(count("plm_collaboration_history")).isEqualTo(history);assertThat(jdbc.queryForObject("select code_value from plm_code_item where code_item_id=12",String.class)).isEqualTo("1218");
        assertThat(service.batchCoverage(List.of(18L)).get(18L)).hasSize(1);assertThat(service.options(Map.of("kind","skus","productLineId","50","manufacturingProductId","100")).get("totalElements")).isEqualTo(2L);
        var access=new PilotAccessService(jdbc,new ObjectMapper());var coding=new ResearchProductCodingService(jdbc,new ObjectMapper(),null,null,null,null,null,access);
        org.springframework.test.util.ReflectionTestUtils.setField(coding,"manufacturingIdentities",service);
        var generated=tx.execute(t->coding.provisionManufacturing(50,18,null,"engineering-qa",result));
        assertThat(generated).containsEntry("status","ready").containsEntry("manufacturingPhoneCode","1145").containsEntry("codes",List.of("NFA4030114502","NFA4030114504"));assertThat((Map<String,Object>)generated.get("businessPhone")).containsEntry("phoneModelCode","1218");
        var orders=new ManufacturingOrderSnapshotService(service,access,new ObjectMapper());var order=tx.execute(t->orders.preview(new ManufacturingOrderSnapshotService.Input(50,18,"large","",id,1,101,"isolated-shop","order-18","line-1","sell-18","客户明确选择18 Pro Max")));
        var snapshot=(com.fasterxml.jackson.databind.JsonNode)order.get("snapshot");assertThat(snapshot.path("customerPhone").path("phoneModelCode").asText()).isEqualTo("1218");assertThat(snapshot.path("manufacturingSku").path("code").asText()).isEqualTo("NFA4030114502");assertThat(order).containsEntry("orderSaved",false).containsEntry("productionWriteEnabled",false);
        assertThat(count("plm_product")).isEqualTo(products);assertThat(jdbc.queryForObject("select finished_product_code from plm_product where product_id=201",String.class)).isEqualTo("NFA4030121802");
    }
    @Test void moldSharingDoesNotAuthorizeSameFinishedMaterialAndDoesNotSpreadAcrossHoleOrStructure(){
        var saved=create("large",false);var result=tx.execute(t->service.requireResolved(query("large",n(saved.get("id")),1L)));assertThat(result).containsEntry("sharedMaterial",false).containsEntry("skuIds",List.of());
        assertThatThrownBy(()->service.resolvedSkus(result)).hasMessageContaining("共用模具不等于共用成品");assertThat(service.resolve(query("precision",null,null))).containsEntry("status","pending");
        assertThat(service.resolve(new ManufacturingIdentityService.ResolveInput(50,18,"large","不同加工",null,null))).containsEntry("status","pending");assertThat(count("plm_manufacturing_relation")).isEqualTo(1);
        var fit=input("precision",false,40,null,"active");var separate=new ManufacturingIdentityService.WriteInput(null,50,30,40,18,"precision","",true,200L,List.of(201L),fit.moldEvidence(),"同模但精孔成品另有独立制造物料",fit.compatibilityDigest(),fit.reason(),"active");
        var precision=tx.execute(t->service.save(null,separate,null));var different=service.requireResolved(query("precision",n(precision.get("id")),1L));assertThat(different).containsEntry("moldSetId",40L).containsEntry("manufacturingPhoneCode","1218");assertThat(service.resolvedSkus(different)).extracting(row->row.get("code")).containsExactly("NFA4030121802");assertThat(count("plm_collaboration_mold_set")).isEqualTo(2);assertThat(count("plm_product")).isEqualTo(7);
    }
    @Test void changedVersionCompatibilityOrLifecycleCannotSilentlyContinueTheOldResolution(){
        var saved=create("large",true);long id=n(saved.get("id"));assertThat(service.resolve(query("large",id,2L))).containsEntry("status","pending");
        jdbc.update("update plm_collaboration_phone_set_compatibility set pc_evidence='已重新核对' where phone_model_id=18 and mold_set_id=40");assertThat(service.resolve(query("large",id,1L))).containsEntry("status","pending");
        var latest=tx.execute(t->service.save(id,input("large",true,40,1L,"active"),null));assertThat(latest).containsEntry("version",2L);jdbc.update("update plm_product set master_data_status='inactive' where product_id=100");assertThat(service.resolve(query("large",id,2L))).containsEntry("status","pending");
    }
    @Test void alternateSetsRequireExplicitSelectionAndInactiveRelationDoesNotApply(){
        var first=create("large",true);var second=tx.execute(t->service.save(null,input("large",true,41,null,"active"),null));assertThat(service.resolve(query("large",null,null))).containsEntry("status","pending");assertThat(service.resolve(query("large",n(first.get("id")),1L))).containsEntry("status","reuse");
        tx.execute(t->service.save(n(second.get("id")),input("large",true,41,1L,"inactive"),null));assertThat(service.resolve(query("large",null,null))).containsEntry("status","reuse").containsEntry("relationId",first.get("id"));assertThat(service.resolve(query("large",n(second.get("id")),2L))).containsEntry("status","pending");
    }
    @Test void auditFailureRollsBackRelationAndHistoryInTheOriginalTransaction(){
        doThrow(new IllegalStateException("QA audit unavailable")).when(audit).logRequired(any(),any());assertThatThrownBy(()->create("large",true)).hasMessageContaining("audit unavailable");assertThat(count("plm_manufacturing_relation")).isZero();assertThat(count("plm_collaboration_history")).isZero();
    }
    @Test void wrongProductSkuAndStaleOriginalCannotOverwriteEngineeringConfirmation(){
        var value=input("large",true,40,null,"active");var wrong=new ManufacturingIdentityService.WriteInput(null,50,30,40,18,"large","",true,100L,List.of(201L),value.moldEvidence(),value.materialEvidence(),value.compatibilityDigest(),value.reason(),"active");assertThatThrownBy(()->tx.execute(t->service.save(null,wrong,null))).hasMessageContaining("SKU不属于");assertThat(count("plm_manufacturing_relation")).isZero();
        var saved=create("large",true);assertThatThrownBy(()->create("large",true)).hasMessageContaining("已有有效制造关联");assertThatThrownBy(()->tx.execute(t->service.save(n(saved.get("id")),input("precision",true,40,99L,"active"),null))).hasMessageContaining("已变化");assertThat(service.detail(n(saved.get("id")))).containsEntry("holeType","large").containsEntry("version",1L);
        assertThatThrownBy(()->create("precision",true)).hasMessageContaining("已知孔型");assertThat(count("plm_manufacturing_relation")).isEqualTo(1);
    }
    @Test void objectScopesAndExternalRoleApplyToListsAndMutationNotOnlyButtons(){
        var saved=create("large",true);CurrentUserContext.set(new CurrentUser(2L,"restricted-qa","受限工程QA",false));assertThat(service.page(Map.of())).containsEntry("totalElements",0L);assertThatThrownBy(()->service.detail(n(saved.get("id")))).hasMessageContaining("未获授权");assertThatThrownBy(()->service.resolve(query("large",null,null))).hasMessageContaining("未获授权");
        jdbc.update("insert into plm_pilot_scope values(2,'product',50)");assertThat(service.page(Map.of())).containsEntry("totalElements",0L);jdbc.update("insert into plm_pilot_scope values(2,'product',100),(2,'product',101),(2,'product',102)");assertThat(service.page(Map.of())).containsEntry("totalElements",1L);
        jdbc.update("update plm_pilot_account set role_code='supplier' where user_id=2");assertThatThrownBy(()->service.page(Map.of())).hasMessageContaining("仅可查看");assertThat(count("plm_manufacturing_relation")).isEqualTo(1);
    }
    @Test void pageOrdersBeforePagingAndBatchCoverageDoesNotDuplicateTheManufacturingMaster(){
        var large=create("large",true);var precision=create("precision",false);var first=service.page(Map.of("page","1","size","1"));var second=service.page(Map.of("page","2","size","1"));assertThat(first).containsEntry("totalElements",2L);assertThat((List<Map<String,Object>>)first.get("content")).extracting(row->row.get("id")).containsExactly(precision.get("id"));assertThat((List<Map<String,Object>>)second.get("content")).extracting(row->row.get("id")).containsExactly(large.get("id"));assertThat(count("plm_product")).isEqualTo(7);
    }
    @Test void missingHoleOrOtherDescriptionCannotCreateANewConfirmedRelation(){
        assertThatThrownBy(()->create("",false)).hasMessageContaining("孔型");assertThatThrownBy(()->create("other",false)).hasMessageContaining("其他孔型说明");assertThat(count("plm_manufacturing_relation")).isZero();
    }
    @Test void connectorCannotImpersonateAHumanOrAllocateFromAnUnconfiguredReadIdentity(){
        CurrentUserContext.clear();assertThat(service.resolveForConnector(query("large",null,null),50)).containsEntry("status","pending").containsEntry("sharedMaterial",false);assertThatThrownBy(()->service.resolveForConnector(query("large",null,null),51)).hasMessageContaining("映射不一致");assertThat(count("plm_manufacturing_relation")).isZero();assertThat(count("plm_collaboration_history")).isZero();
    }
}
