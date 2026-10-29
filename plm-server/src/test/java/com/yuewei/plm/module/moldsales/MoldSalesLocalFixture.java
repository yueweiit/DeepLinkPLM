package com.yuewei.plm.module.moldsales;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuewei.plm.common.exception.BusinessException;
import com.yuewei.plm.common.security.CurrentUser;
import com.yuewei.plm.module.operationlog.service.OperationLogCreateCommand;
import com.yuewei.plm.module.operationlog.service.OperationLogService;
import com.yuewei.plm.module.pilot.PilotAccessService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

abstract class MoldSalesLocalFixture {
    static String databaseUrl(){return System.getenv("PLM_SALES_ARCHIVE_TEST_DB_URL")!=null?System.getenv("PLM_SALES_ARCHIVE_TEST_DB_URL"):System.getenv("PLM_MOLD_COST_TEST_DB_URL");}
    static String databasePassword(){return System.getenv("PLM_SALES_ARCHIVE_TEST_DB_URL")!=null?System.getenv("PLM_SALES_ARCHIVE_TEST_DB_PASSWORD"):System.getenv("PLM_MOLD_COST_TEST_DB_PASSWORD");}
    static boolean databaseConfigured(){String url=databaseUrl();return url!=null&&url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/(plm_mold_cost_qa|plm_sales_history_a8tr4pjg|plm_sales_policy_qa)(\\?currentSchema=plm)?");}
    JdbcTemplate admin,jdbc;MoldSalesService service;PilotAccessService access;OperationLogService audit;
    String schema;boolean financeRead=true,financeWrite=true;final Set<Long> visible=new HashSet<>();
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    final CurrentUser actor=new CurrentUser(1L,"mold-cost-qa","隔离成本测试",true);
    @BeforeEach void begin()throws Exception{
        String url=databaseUrl(),password=databasePassword();
        schema="mold_cost_qa_"+UUID.randomUUID().toString().replace("-","");
        admin=new JdbcTemplate(new DriverManagerDataSource(url,"postgres",password));admin.execute("create schema "+schema);
        var data=new DriverManagerDataSource(url.split("\\?",2)[0]+"?currentSchema="+schema,"postgres",password);jdbc=new JdbcTemplate(data);
        jdbc.execute("""
          create table plm_product(product_id bigint primary key,product_specific_code text,product_type text,parent_product_id bigint,finished_product_code text,project_kind text,color text,deleted_flag int default 0,product_name text,product_code text,status text,master_data_status text,phone_model_member_id bigint,phone_model_code text);
          create table plm_code_item(code_item_id bigint primary key,code_value text,code_name text,code_type text,deleted_flag int default 0);
          create table plm_collaboration_item(item_id bigint primary key,style_name text,mold_name text,compatible_models text,product_line_id bigint,phone_model_code_item_id bigint,phone_model_code text,pc_total numeric,tpu_total numeric,finished_total numeric,production_notes text,deleted_flag int default 0,brand_id bigint);
          create table plm_collaboration_mold_set(mold_set_id bigint primary key,item_id bigint,deleted_flag int default 0);
          create table plm_collaboration_mold_part(mold_part_id bigint primary key,mold_set_id bigint,part_type text,physical_status text,factory_arrival_date date,acceptance_status text,acceptance_date date);
          create table plm_phone_model_member(id bigint primary key,code_item_id bigint,brand_id bigint,model_name text);
          create table plm_phone_brand(id bigint primary key,name text);
          create table plm_phone_code_brand_effective(code_item_id bigint,brand_id bigint);
          create table plm_product_phone_model(product_id bigint,phone_model_id bigint);
          create table plm_collaboration_phone_member(item_id bigint,phone_model_id bigint);
          create table plm_mold_application(item_id bigint,mold_set_id bigint,phone_model_id bigint,deleted_flag int default 0);
          create table plm_master_data_source(source_kind text,source_code text,object_type text,object_id bigint,partner_spec_id bigint);
          create table plm_collaboration_phone_set_compatibility(item_id bigint,mold_set_id bigint,phone_model_id bigint,pc_confirmed boolean,tpu_confirmed boolean,pc_evidence text,tpu_evidence text);
          create table plm_mold_code_source(source_code_id bigint primary key,mold_code text,source_disabled boolean default false);
          """);
        migrate("V20261016_1600__mold_sales_costs.sql");migrate("V20261016_1810__mold_sales_reconciliation.sql");migrate("V20261026_1300__mold_group_actual_cost.sql");migrate("V20261027_1800__mold_sales_archive.sql");migrate("V20261028_1830__sales_history_classification.sql");
        jdbc.update("insert into plm_product(product_id,product_specific_code,product_type,product_name,status) values(1,'FA','product_line','骑士','published')");
        jdbc.update("insert into plm_code_item(code_item_id,code_value,code_name,code_type) values(1,'0057','Existing Phone','phone_model')");
        jdbc.update("insert into plm_collaboration_item(item_id,style_name,compatible_models,product_line_id,phone_model_code_item_id) values(101,'骑士','Phone A',1,1),(102,'骑士','Phone B',1,1)");
        jdbc.update("insert into plm_collaboration_mold_set(mold_set_id,item_id) values(1,101),(2,102)");
        jdbc.update("insert into plm_collaboration_mold_part(mold_part_id,mold_set_id,part_type,physical_status) values(1,1,'PC','confirmed'),(2,1,'TPU','confirmed'),(3,2,'PC','confirmed'),(4,2,'TPU','confirmed')");
        jdbc.update("insert into plm_mold_sales_snapshot(snapshot_id,source_hash,source_file,organization_id,captured_at,row_count,costs,source_summary,created_by) values(? ,?,'crm-sales-qa.jsonl.gz','QA',now(),1,?::jsonb,'{}','qa')",UUID.randomUUID(),"a".repeat(64),budgetJson());
        jdbc.update("insert into plm_mold_sales_line values('qa-line',(select snapshot_id from plm_mold_sales_snapshot),'qa-order','QA','closed','NFA4030005701','FA','0057',100,'eligible','{\"businessDate\":\"2025-01-01\",\"name\":\"Phone A\"}')");
        visible.addAll(List.of(101L,102L));access=mock(PilotAccessService.class);audit=mock(OperationLogService.class);
        when(access.actor()).thenReturn(actor);
        when(access.hasPermission(anyString())).thenAnswer(inv->{String p=inv.getArgument(0);return !p.equals("finance:read")&&!p.equals("finance:write")||p.equals("finance:read")&&financeRead||p.equals("finance:write")&&financeWrite;});
        doAnswer(inv->{if(!financeRead)throw new BusinessException(40103,"没有权限：finance:read");return null;}).when(access).requireFinanceRead();
        doAnswer(inv->{if(!financeWrite)throw new BusinessException(40103,"没有权限：finance:write");return null;}).when(access).requireFinanceWrite();
        doAnswer(inv->{long id=inv.getArgument(0);if(!visible.contains(id))throw new BusinessException(40103,"对象不存在或未获授权");return null;}).when(access).requireItem(anyLong());
        when(access.visibleSqlClause(anyString(),anyString())).thenAnswer(inv->inv.getArgument(0).equals("item")?"i.item_id in ("+String.join(",",visible.stream().map(Object::toString).toList())+")":"true");
        service=new MoldSalesService(jdbc,json,access,audit,new DataSourceTransactionManager(data));ReflectionTestUtils.setField(service,"root","");ReflectionTestUtils.setField(service,"exporter","");
    }
    @AfterEach void cleanup(){if(service!=null)service.close();if(admin!=null&&schema!=null&&schema.matches("mold_cost_qa_[a-f0-9]{32}"))admin.execute("drop schema "+schema+" cascade");}
    void migrate(String name)throws Exception{try(var in=getClass().getResourceAsStream("/db/migration/"+name)){jdbc.execute(new String(Objects.requireNonNull(in).readAllBytes(),StandardCharsets.UTF_8));}}
    String budgetJson(){return "{\"sourceSha256\":\"qa\",\"rows\":[{\"styleCode\":\"FA\",\"moldManufacturing\":\"700\",\"freight\":\"200\",\"customs\":\"80\",\"fuel\":\"20\",\"landedTotal\":\"1000\",\"currency\":\"CNY\",\"referenceSellingPrice\":\"8\",\"referenceUnitProductionCost\":\"3\"}]}";}
    MoldActualCostStore.Amounts amounts(String amount){return new MoldActualCostStore.Amounts(new BigDecimal(amount),new BigDecimal("100"),new BigDecimal("20"),new BigDecimal("5"));}
    MoldActualCostStore.Update request(Map<String,Object> before,MoldActualCostStore.Amounts amounts){return new MoldActualCostStore.Update(((Number)before.get("version")).longValue(),before.get("groupToken").toString(),amounts,"核对合同、运费和实际发生额");}
    @SuppressWarnings("unchecked") Map<String,Object> first(String view,String filter){var rows=(List<Map<String,Object>>)service.page(1,20,"",filter,null,null,view).get("content");return rows.isEmpty()?null:rows.get(0);}
    @SuppressWarnings("unchecked") Map<String,Object> costOf(Map<String,Object> row){return (Map<String,Object>)row.get("cost");}
    @SuppressWarnings("unchecked") Map<String,Object> actualOf(Map<String,Object> row){return (Map<String,Object>)row.get("actual");}
}
