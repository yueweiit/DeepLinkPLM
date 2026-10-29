package com.yuewei.plm.module.moldsales;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuewei.plm.common.security.CurrentUser;
import com.yuewei.plm.module.operationlog.service.OperationLogService;
import com.yuewei.plm.module.pilot.PilotAccessService;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Read-only checks against this task's uniquely named restored clone; no records are created. */
@EnabledIfEnvironmentVariable(named="PLM_SALES_MATRIX_TEST_DB_URL",matches="jdbc:postgresql://127\\.0\\.0\\.1:15486/plm_sales_matrix_q8510crh\\?currentSchema=plm")
class MoldSalesMatrixLocalIntegrationTest {
    JdbcTemplate jdbc;MoldSalesService service;PilotAccessService access;boolean finance=true;
    final CurrentUser actor=new CurrentUser(1L,"sales-readonly-qa","隔离只读销量验收",true);
    @BeforeEach void begin(){
        var data=new DriverManagerDataSource(System.getenv("PLM_SALES_MATRIX_TEST_DB_URL"),"postgres",System.getenv("PLM_SALES_MATRIX_TEST_DB_PASSWORD"));jdbc=new JdbcTemplate(data);access=mock(PilotAccessService.class);
        when(access.actor()).thenReturn(actor);when(access.access()).thenReturn(new PilotAccessService.Access(actor,"administrator",List.of("*"),true,false));when(access.visibleSqlClause(anyString(),anyString())).thenReturn("true");when(access.hasPermission(anyString())).thenAnswer(i->!i.<String>getArgument(0).startsWith("finance:")||finance);
        service=new MoldSalesService(jdbc,new ObjectMapper().findAndRegisterModules(),access,mock(OperationLogService.class),new DataSourceTransactionManager(data));ReflectionTestUtils.setField(service,"root","");ReflectionTestUtils.setField(service,"exporter","");
    }
    @AfterEach void close(){if(service!=null)service.close();}
    @SuppressWarnings("unchecked") List<Map<String,Object>> records(Object o){return (List<Map<String,Object>>)o;}
    BigDecimal qty(Object o){return o==null?BigDecimal.ZERO:new BigDecimal(o.toString());}
    @Test void bothViewsKeepExactlyTheExistingSalesTotalAndSharedOrdersAreCountedOnce(){
        var detail=service.page(1,100,"","all",null,null,"sales",null,null);var matrix=service.matrix(1,100,"","all",null,null,"sales");
        assertThat(qty(detail.get("totalSalesQuantity"))).isEqualByComparingTo("6762322");assertThat(qty(matrix.get("totalSalesQuantity"))).isEqualByComparingTo(qty(detail.get("totalSalesQuantity")));
        var groupIds=new HashSet<Long>();BigDecimal counted=BigDecimal.ZERO;int pages=((Number)matrix.get("totalPages")).intValue();
        for(int page=1;page<=pages;page++)for(var row:records((page==1?matrix:service.matrix(page,100,"","all",null,null,"sales")).get("content")))for(var cell:records(row.get("cells"))){counted=counted.add(qty(cell.get("salesQuantity")));for(long group:MoldSalesMatrixProjection.ids(cell.get("groupIds")))assertThat(groupIds.add(group)).as("group %s occurs once",group).isTrue();}
        assertThat(counted).isEqualByComparingTo("6762322");assertThat(groupIds).hasSize(((Number)detail.get("totalElements")).intValue());
    }
    @Test void draftUncodedTuojiaIsSelectableWithoutInventingItsPhoneOrStock(){
        var options=service.options();assertThat(records(options.get("products"))).filteredOn(p->Long.valueOf(19252).equals(p.get("productLineId"))).singleElement().satisfies(p->assertThat(p).containsEntry("status","draft"));
        var selected=service.matrix(1,100,"","all",null,List.of(19252L),"sales");assertThat(selected.get("totalSalesQuantity")).isEqualTo(BigDecimal.ZERO);assertThat(records(selected.get("content"))).isEmpty();
        assertThat(jdbc.queryForMap("select status,current_step_no,timeline_current_confirmed from plm_product where product_id=19252")).containsEntry("status","draft").containsEntry("current_step_no",1).containsEntry("timeline_current_confirmed",false);
    }
    @Test void confirmedXsCorrectionAndThreeHistoricalCommonScopesSurvive(){
        var detail=service.detail(974,1,100,"sales");assertThat(records(detail.get("phoneSales"))).filteredOn(p->"0070".equals(p.get("phoneCode"))).singleElement().satisfies(p->{assertThat(p).containsEntry("historicalAlias",true).containsEntry("targetPhoneCode","0069");assertThat(p.get("modelName").toString()).contains("XS").doesNotContain("XR");});
        for(var check:Map.of("A05S",392L,"历史订单通用款",1105L).entrySet()){
            var result=service.matrix(1,100,check.getKey(),"all",null,null,"sales");if(check.getKey().equals("历史订单通用款"))assertThat(qty(result.get("totalSalesQuantity"))).isEqualByComparingTo(check.getValue().toString());
            assertThat(records(result.get("content"))).filteredOn(r->r.get("rowKey").toString().startsWith("history:")).isNotEmpty();
        }
    }
    @Test void productAndBrandFiltersKeepTheSameTotalsAcrossViews(){
        var options=service.options();var selected=MoldSalesMatrixProjection.ids(options.get("defaultProductLineIds")).stream().toList();
        for(String keyword:List.of("","骑士","A17","0069")){
            var detail=service.page(1,50,keyword,"all",null,null,"sales",null,selected);var matrix=service.matrix(1,50,keyword,"all",null,selected,"sales");assertThat(qty(matrix.get("totalSalesQuantity"))).as("keyword %s",keyword).isEqualByComparingTo(qty(detail.get("totalSalesQuantity")));
        }
        for(var brand:records(options.get("brands"))){long id=((Number)brand.get("brandId")).longValue();var detail=service.page(1,50,"","all",null,null,"sales",id,selected);var matrix=service.matrix(1,50,"","all",id,selected,"sales");assertThat(qty(matrix.get("totalSalesQuantity"))).as("brand %s",id).isEqualByComparingTo(qty(detail.get("totalSalesQuantity")));}
        assertThat(records(service.matrix(1,50,"","all",null,List.of(),"sales").get("content"))).isEmpty();
    }
    @Test void scopedDomesticUserGetsNoOtherGroupsOrFinancialAmounts(){
        finance=false;when(access.actor()).thenReturn(new CurrentUser(1L,"scoped-sales-qa","范围验收",false));when(access.access()).thenReturn(new PilotAccessService.Access(new CurrentUser(1L,"scoped-sales-qa","范围验收",false),"domestic",List.of("collaboration:view","product:view"),false,true));
        long line=jdbc.queryForObject("select product_line_id from plm_collaboration_item where item_id=974",Long.class);
        when(access.visibleSqlClause(anyString(),anyString())).thenAnswer(i->{String kind=i.getArgument(0),expression=i.getArgument(1);return kind.equals("item")?expression+"=974":kind.equals("product")?expression+"="+line:"false";});
        var result=service.matrix(1,100,"","all",null,null,"sales");for(var row:records(result.get("content")))for(var cell:records(row.get("cells"))){assertThat(MoldSalesMatrixProjection.ids(cell.get("groupIds"))).allMatch(id->id==974);assertThat(cell.get("productLineId")).isEqualTo(line);}
        assertThat(result).doesNotContainKeys("ignoredQuantity","unassignedQuantity");assertThat(result.toString()).doesNotContain("totalCost=","referenceSellingPrice=","moldManufacturing=");
    }    @Test void actualItemOnlyAndMoldOnlyAuthorizationRetainsItsRootCandidateWithoutGrantingTheRoot(){
        String schema="sales_matrix_acl_"+UUID.randomUUID().toString().replace("-","");
        jdbc.execute("create schema "+schema);try{
            jdbc.execute("create table "+schema+".plm_pilot_scope(user_id bigint,entity_type text,entity_id bigint)");
            var data=new DriverManagerDataSource(System.getenv("PLM_SALES_MATRIX_TEST_DB_URL").replace("currentSchema=plm","currentSchema="+schema+",plm"),"postgres",System.getenv("PLM_SALES_MATRIX_TEST_DB_PASSWORD"));
            service.close();jdbc=new JdbcTemplate(data);access=spy(new PilotAccessService(jdbc,new ObjectMapper()));
            var scoped=new CurrentUser(999999999L,"item-mold-only-qa","单对象权限验收",false);doReturn(new PilotAccessService.Access(scoped,"domestic",List.of("collaboration:view","product:view"),false,true)).when(access).access();
            service=new MoldSalesService(jdbc,new ObjectMapper().findAndRegisterModules(),access,mock(OperationLogService.class),new DataSourceTransactionManager(data));ReflectionTestUtils.setField(service,"root","");ReflectionTestUtils.setField(service,"exporter","");
            var mold=jdbc.queryForMap("select s.mold_set_id,s.item_id,i.product_line_id from plm_collaboration_mold_set s join plm_collaboration_item i on i.item_id=s.item_id where s.deleted_flag=0 and i.deleted_flag=0 and i.product_line_id is not null order by s.mold_set_id limit 1");
            for(String type:List.of("item","mold")){
                long item=type.equals("item")?974L:((Number)mold.get("item_id")).longValue();long target=type.equals("item")?item:((Number)mold.get("mold_set_id")).longValue();long root=jdbc.queryForObject("select product_line_id from plm_collaboration_item where item_id=?",Long.class,item);
                jdbc.update("delete from plm_pilot_scope");jdbc.update("insert into plm_pilot_scope values(?,?,?)",scoped.userId(),type,target);
                access.requireItem(item);assertThatThrownBy(()->access.requireProduct(root)).as("root is not granted by %s candidate",type).isInstanceOf(com.yuewei.plm.common.exception.BusinessException.class);
                var options=service.options();assertThat(records(options.get("products"))).filteredOn(p->Long.valueOf(root).equals(p.get("productLineId"))).hasSize(1);
                var detail=service.page(1,100,"","all",null,null,"sales",null,List.of(root));var matrix=service.matrix(1,100,"","all",null,List.of(root),"sales");assertThat(records(matrix.get("content"))).isNotEmpty();assertThat(qty(matrix.get("totalSalesQuantity"))).isEqualByComparingTo(qty(detail.get("totalSalesQuantity")));
                for(var row:records(matrix.get("content")))for(var cell:records(row.get("cells")))assertThat(MoldSalesMatrixProjection.ids(cell.get("itemIds"))).allMatch(id->id==item);
            }
        }finally{jdbc.execute("drop schema "+schema+" cascade");}
    }

}
