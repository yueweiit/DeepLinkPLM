package com.yuewei.plm.module.masterdata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuewei.plm.module.bom.service.BomConfirmationState;
import java.util.concurrent.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.yuewei.plm.module.pilot.PilotAccessService;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="PLM_BOM_CATALOG_TEST_URL",matches="jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/plm_bom_catalog_it")
class BomCatalogPostgresTest {
    JdbcTemplate admin, jdbc;
    String schema;
    MasterDataCatalogService catalog;
    PilotAccessService access;

    @BeforeEach void setup() {
        String url=System.getenv("PLM_BOM_CATALOG_TEST_URL");
        admin=new JdbcTemplate(new DriverManagerDataSource(url,"postgres",System.getenv("PLM_BOM_CATALOG_TEST_PASSWORD")));
        schema="catalog_"+UUID.randomUUID().toString().replace("-","");
        admin.execute("create schema "+schema);
        var ds=new DriverManagerDataSource(url+"?currentSchema="+schema,"postgres",System.getenv("PLM_BOM_CATALOG_TEST_PASSWORD"));
        new ResourceDatabasePopulator(new ClassPathResource("masterdata/bom-catalog-fixture.sql")).execute(ds);
        jdbc=new JdbcTemplate(ds);
        access=mock(PilotAccessService.class);
        when(access.hasPermission("product:view")).thenReturn(true);
        when(access.visibleSqlClause("product","s.object_id")).thenReturn("s.object_id<>104");
        when(access.visibleSqlClause("partner","s.object_id")).thenReturn("false");
        when(access.visibleSqlClause("product","b.product_id")).thenReturn("b.product_id=100");
        catalog=new MasterDataCatalogService(jdbc,new ObjectMapper(),access,mock(MasterDataSnapshotStore.class));
    }
    @AfterEach void cleanup(){if(schema!=null)admin.execute("drop schema "+schema+" cascade");}
    @SuppressWarnings("unchecked")
    List<Map<String,Object>> records(Map<String,Object> parent,String field){return (List<Map<String,Object>>)parent.get(field);}

    String productKey(){return catalog.bomProducts(new MasterDataCatalogService.Query()).getContent().get(0).get("key").toString();}

    @Test void processCatalogScopesProductsAndRetainsEachOccurrenceOfAnOperation() {
        when(access.visibleSqlClause("product","p.product_id")).thenReturn("p.product_id in (100,103)");
        jdbc.update("update plm_product set product_name='亮甲' where product_id=100");
        jdbc.update("update plm_process set status='locked' where process_id=1000");
        jdbc.update("update plm_process set product_id=104 where process_id=2000");
        jdbc.update("update plm_process set product_id=null where process_id=3000");
        jdbc.update("update plm_product set deleted_flag=1 where product_id=103");
        jdbc.update("update plm_process set product_id=103 where process_id=4000");
        jdbc.update("insert into plm_process(process_id,process_code) values(75,'OP')");
        jdbc.update("insert into plm_process(process_id,parent_process_id,sequence_no,operation_master_process_id) values (76,1000,2,75),(77,1000,3,75),(78,2000,2,75)");
        jdbc.update("""
            insert into plm_master_data_source(source_system,source_kind,source_id,object_id,object_type,source_code,source_data)
            values ('mes','process_flows','r1',1000,'process','R1','{"product_code":"A"}'),
                   ('mes','process_flows','r2',2000,'process','R2','{"product_code":"B"}'),
                   ('mes','process_flows','r3',3000,'process','R3','{}'),
                   ('mes','process_flows','r4',4000,'process','R4','{}'),
                   ('mes','operation_templates','retired',4000,'process','OP-RETIRED','{}'),
                   ('mes','mes_product','a',100,'product','A','{"product_name_es":"Arco"}'),
                   ('mes','operation_templates','op',75,'process','OP','{}')
            """);
        jdbc.update("insert into plm_master_data_source(source_system,source_kind,object_id,source_id) select 'mes','process_flow_steps',id,id::text from unnest(array[100001,76,77]) id");
        jdbc.update("insert into plm_master_data_source(source_system,source_kind,object_id,source_id,batch_hash) values ('mes','operation_templates',75,'former-op','old'),('mes','process_flows',1000,'former-route','old')");
        jdbc.update("insert into plm_process(process_id,parent_process_id,sequence_no) values(79,1000,4)");
        jdbc.update("insert into plm_process_edge(edge_id,from_process_id,to_process_id,route_id) values(31,76,77,1000),(32,77,79,1000)");
        jdbc.update("insert into plm_master_data_source(source_system,source_kind,object_id,source_id,batch_hash) values ('mes','process_flow_step_edges',31,'e1','one'),('mes','process_flow_step_edges',32,'e2','old')");
        jdbc.update("update plm_process_edge set relation_type='finish_to_start',label='旧标签',condition_json='{\"old\":true}',edge_no=10 where edge_id=31");
        jdbc.update("update plm_master_data_source set source_data='{\"relation_type\":\"start_to_start\",\"label\":null,\"condition_json\":null,\"edge_no\":50}' where source_id='e1'");
        var result=catalog.processes();
        var visible=records(result,"routes");
        assertThat(visible).hasSize(1);
        assertThat(visible.get(0)).containsEntry("sourceCode","R1").containsEntry("seriesName","亮甲")
            .containsEntry("seriesNameEs","Arco").containsEntry("routeStatus","locked").containsEntry("productId",100L);
        assertThat(visible.get(0).get("operationIds").toString()).isEqualTo("[null,75,75]");
        assertThat(records(result,"operations")).extracting(r->r.get("sourceCode")).containsExactly("OP");
        var graph=catalog.graph(1000,true);
        assertThat(records(graph,"nodes")).hasSize(3).filteredOn(r->Objects.equals(r.get("operationId"),75L)).hasSize(2);
        assertThat(records(graph,"edges")).extracting(r->r.get("id")).containsExactly(31L);
        var newEdge=records(graph,"edges").get(0);
        assertThat(newEdge).containsEntry("edgeNo",50).containsEntry("relationType","start_to_start").containsEntry("label",null);
        assertThat(newEdge.get("condition").toString()).isEqualTo("null");
        var olderGraph=records(catalog.graph(1000),"edges");
        assertThat(olderGraph).hasSize(2);
        assertThat(olderGraph.get(0)).containsEntry("relationType","finish_to_start").containsEntry("label","旧标签");
        verify(access).requirePermission("process:view");
        verify(access,times(2)).requireProcess(1000L);
    }

    @Test void productsPageIndependentlyAndScopeModelsBySourceIdentity(){
        jdbc.update("insert into plm_master_data_source(source_system,source_kind,source_id,source_code,object_id,object_type,source_data) select source_system,source_kind,'other-sku',source_code,object_id,object_type,jsonb_set(source_data,'{productSeriesId}',to_jsonb('other-series'::text)) from plm_master_data_source where source_id='a'");
        var q=new MasterDataCatalogService.Query();q.setSize(1);
        var first=catalog.bomProducts(q);
        assertThat(first.getTotalElements()).isEqualTo(2);
        assertThat(first.getContent().get(0).get("modelCount")).isEqualTo(3L);
        q.setPage(2);String other=catalog.bomProducts(q).getContent().get(0).get("key").toString();
        assertThat(other).isNotEqualTo(first.getContent().get(0).get("key"));
        q.setPage(1);
        assertThat(records(catalog.bomGroups(q,other).getContent().get(0),"colors")).extracting(r->r.get("sourceId")).containsExactly("other-sku");
        q.setKeyword("HIDE");assertThat(catalog.bomProducts(q).getContent()).isEmpty();
    }
    @Test void pagesWholeFamiliesAndRetainsMatchingColorAndSeparateSourceIdentities(){
        var q=new MasterDataCatalogService.Query();q.setSize(1);
        var first=catalog.bomGroups(q,productKey());
        assertThat(first.getTotalElements()).isEqualTo(3);
        assertThat(records(first.getContent().get(0),"colors")).extracting(r->r.get("sourceId")).containsExactly("a","b");
        q.setPage(2);
        assertThat(records(catalog.bomGroups(q,productKey()).getContent().get(0),"colors")).extracting(r->r.get("sourceId")).containsExactly("c");
        q.setPage(8);assertThat(catalog.bomGroups(q,productKey()).getContent()).isEmpty();
        q.setPage(1);q.setKeyword("SKU01");
        assertThat(catalog.bomGroups(q,productKey()).getTotalElements()).isEqualTo(2);
        assertThat(records(catalog.bomGroups(q,productKey()).getContent().get(0),"colors")).extracting(r->r.get("matched")).containsExactly(true,false);
    }
    @Test void identifiesPendingCurrentAndSupersededVersionsWithoutMixingNodeMaterials(){
        var detail=catalog.sku("a",true);
        var versions=records(detail,"manufacturing");
        assertThat(versions).extracting(v->v.get("displayVersion"),v->v.get("engineeringState"))
            .containsExactlyInAnyOrder(tuple("PLM-007","pending"),tuple("PLM-005","released"),tuple("V1","history"));
        assertThat(records(catalog.sku("a"),"manufacturing")).extracting(r->r.get("bomId")).containsExactly(3L);
        assertThat(detail.get("source").toString()).doesNotContain("basePrice");
        for(var version:versions) for(var item:records(version,"items")) {
            assertThat(item.get("childCode")).isEqualTo("MAT"+version.get("bomRouteId"));
            assertThat(item).doesNotContainKey("unitPrice");
        }
        jdbc.update("update plm_bom_item_target set article_codes=array['SKU02'] where product_bom_route_id=10");
        assertThat(records(catalog.sku("a",true),"manufacturing")).anyMatch(v -> "history".equals(v.get("engineeringState")) && records(v,"items").isEmpty());
    }
    @Test void scopeFollowsAllRoutesOfTheBomAndOnlyNamesVisibleModels(){
        var scope=catalog.confirmationScope("a",2,20);
        assertThat(scope.get("count")).isEqualTo(4L);
        assertThat(records(scope,"articles")).extracting(a->a.get("code"),a->a.get("model"))
            .containsExactly(tuple("SKU01","Redmi"),tuple("SKU01","Redmi/Max"),tuple("SKU02","Redmi"));
        assertThat(scope.get("previousVersions")).isEqualTo(List.of("PLM-005"));
        verify(access).requirePermission("bom:publish");verify(access).requireEngineeringMaintenance();verify(access).requireProduct(100L);
        assertThatThrownBy(()->catalog.confirmationScope("a",2,21)).hasMessageContaining("版本状态");
        assertThatThrownBy(()->catalog.confirmationScope("a",1,10)).hasMessageContaining("版本状态");
        assertThatThrownBy(()->catalog.sku("d",true)).hasMessageContaining("未获授权");
    }
    @Test void equalPriorityAcrossFamiliesBlocksAmbiguousEngineeringTarget() {
        jdbc.update("update plm_product_bom_route set binding_priority=90 where product_bom_route_id=40");
        var q=new MasterDataCatalogService.Query();q.setKeyword("SKU01");
        var color=records(catalog.bomGroups(q,productKey()).getContent().get(0),"colors").get(0);
        assertThat(records(color,"recipes")).anyMatch(r -> "conflict".equals(r.get("state")));
        assertThatThrownBy(()->catalog.sku("a",true)).hasMessageContaining("同优先级");
    }
    @Test void pendingFamilyOutranksHistoryWhenThereIsNoEffectiveBinding() {
        jdbc.update("update plm_product_bom_route set route_code='S',binding_priority=100 where product_bom_route_id=10");
        var recipes=records(catalog.sku("b",true),"manufacturing");
        assertThat(recipes).anyMatch(r->Long.valueOf(21).equals(r.get("bomRouteId")) && "pending".equals(r.get("engineeringState")));
        assertThat(catalog.confirmationScope("b",2,21).get("count")).isEqualTo(4L);
    }
    @Test void removedCoverageIncludesPreviousFormulaAndHidesInaccessibleArticleNames(){
        var before=catalog.confirmationScope("a",2,20);
        jdbc.update("update plm_product_bom_route set article_codes='{SKU01,SKU02,SKU04,PRIVATE}' where product_bom_route_id=30");
        var scope=catalog.confirmationScope("a",2,20);
        assertThat(scope.get("removedCount")).isEqualTo(2L);
        assertThat(scope.get("revision")).isNotEqualTo(before.get("revision"));
        assertThat(records(scope,"removedArticles")).extracting(r->r.get("code")).containsExactly("SKU04");
        jdbc.update("update plm_product_bom_route_formal_selection set status='invalidated'");
        jdbc.update("update plm_product_bom_route set article_codes='{SKU01,OLD}' where product_bom_route_id=10");
        assertThat(catalog.confirmationScope("a",2,20).get("removedCount")).isEqualTo(1L);
    }
    @Test void scopeRequiresApplicableMaterialsAndDetectsEqualSizeChanges() {
        var revision=catalog.confirmationScope("a",2,20).get("revision");
        jdbc.update("update plm_product_bom_route set article_codes='{SKU02,SKU03,CHANGED}' where product_bom_route_id=21");
        assertThat(catalog.confirmationScope("a",2,20).get("revision")).isNotEqualTo(revision);
        jdbc.update("update plm_bom_item_target set article_codes='{SKU02}' where product_bom_route_id=20");
        assertThatThrownBy(()->catalog.confirmationScope("a",2,20)).hasMessageContaining("适用投料");
    }
    @Test void secondCandidateObservesNewFormalSelectionAfterFamilyLock() throws Exception {
        var tx=new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())));
        var original=BomConfirmationState.revision(jdbc,2);
        var started=new CountDownLatch(1);
        var executor=Executors.newSingleThreadExecutor();
        try {
            var second=tx.execute(status->{
                BomConfirmationState.lock(jdbc,3);
                var future=executor.submit(()->tx.execute(locked->{
                    started.countDown();
                    BomConfirmationState.lock(jdbc,2);
                    return BomConfirmationState.revision(jdbc,2);
                }));
                try {assertThat(started.await(3,TimeUnit.SECONDS)).isTrue();}
                catch(InterruptedException interrupted){throw new IllegalStateException(interrupted);}
                jdbc.update("update plm_product_bom_route_formal_selection set status='invalidated' where product_bom_id=3");
                return future;
            });
            assertThat(second.get(5,TimeUnit.SECONDS)).isNotEqualTo(original);
        } finally {executor.shutdownNow();}
    }
    @Test void listAndDetailUseTheImportedLanguageAndNullColorRules() {
        for(var condition:List.of("{\"color_names\":null}","{\"color_names\":[\"  MORADO \"]}","{\"exclude_color_names\":[\"Purple\"]}")){
            jdbc.update("update plm_master_data_source set source_data=jsonb_set(source_data,'{condition_json}',?::jsonb) where source_id='bind'",condition);
            var q=new MasterDataCatalogService.Query();q.setKeyword("SKU01");
            boolean expected=!condition.contains("exclude");
            assertThat(!records(records(catalog.bomGroups(q,productKey()).getContent().get(0),"colors").get(0),"recipes").isEmpty()).isEqualTo(expected);
            assertThat(!records(catalog.sku("a",true),"manufacturing").isEmpty()).isEqualTo(expected);
        }
    }
}
