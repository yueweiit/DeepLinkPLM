package com.yuewei.plm.module.masterdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import com.yuewei.plm.common.exception.BusinessException;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

class MasterDataSnapshotTest {
    private final ObjectMapper json=new ObjectMapper();
    @TempDir Path temporary;
    ObjectNode fixture() throws Exception {
        var root=(ObjectNode)json.readTree("""
          {"formatVersion":1,"complete":true,"organizationId":"org","capturedAt":"2026-10-16T00:00:00Z",
           "crm":{"series":[{"id":"p","organizationId":"org","seriesCode":"NHA4030","status":"ACTIVE"}],
            "brands":[{"id":"brand","organizationId":"org","brandName":"Apple"}],
            "phones":[{"id":"m1","organizationId":"org","phoneBrandId":"brand","modelName":"iPhone 11","modelCode":"0052"},
                      {"id":"m2","organizationId":"org","phoneBrandId":"brand","modelName":"iPhone 11 compatible","modelCode":"0052"}],
            "colors":[],"skus":[{"id":"s1","organizationId":"org","productSeriesId":"p","phoneModelId":"m1","itemCode":"NHA4030005202","colorNameZh":"黑色"},
                                 {"id":"s2","organizationId":"org","productSeriesId":"p","phoneModelId":"m2","itemCode":"NHA4030005202","colorNameZh":"黑色"}]},
           "mes":{"products":[],"materials":[{"id":1,"material_code":"SAME"},{"id":2,"material_code":"MODEL-OTHER"}],"item_codes":[],"item_colors":[],"operations":[{"id":1,"operation_code":"INJECTION"},{"id":2,"operation_code":"PACK"}],
            "boms":[{"id":1,"bom_code":"BOM-A","output_qty":"1.0000","is_active":true}],
            "bom_bindings":[{"id":1,"product_code":"NHA4030","bom_code":"BOM-A","status":"enabled","priority":2000,
              "condition_json":{"shared_recipe":true,"workbook_scope":{"route_codes":["ROUTE-A"],"article_codes":["NHA4030005202"]}}}],
            "routes":[{"id":1,"route_code":"ROUTE-A","product_code":"NHA4030"}],
            "route_bindings":[{"id":1,"product_code":"NHA4030","route_code":"ROUTE-A","status":"enabled","condition_json":null}],
            "nodes":[{"id":1,"route_code":"ROUTE-A","node_code":"NODE-A","operation_code":"INJECTION"},
                     {"id":2,"route_code":"ROUTE-A","node_code":"NODE-B","operation_code":"PACK"}],
            "edges":[{"id":1,"route_code":"ROUTE-A","from_node_code":"NODE-A","to_node_code":"NODE-B","relation_type":"finish_to_start"}],
            "bom_items":[{"id":1,"bom_code":"BOM-A","child_code":"SAME","quantity":"1234567890123456.12345678","unit":"g",
               "route_targets":{"ROUTE-A":{"node_code":"NODE-A","process_code":"INJECTION","process_name":"注塑"}}},
              {"id":2,"bom_code":"BOM-A","child_code":"SAME","quantity":"2","unit":"件",
               "route_targets":{"ROUTE-A":{"node_code":"NODE-B","process_code":"PACK","process_name":"组装","article_codes":["NHA4030005202"]}}},
              {"id":3,"bom_code":"BOM-A","child_code":"MODEL-OTHER","quantity":"1","unit":"件",
               "route_targets":{"ROUTE-A":{"node_code":"NODE-B","process_code":"PACK","process_name":"组装","article_codes":["OTHER"]}}}]},
           "shop":{"listedSkuIds":["s1"]},"classification":{"purchasedSeriesCodes":["NSF4030"]}}
          """);return root;
    }
    @Test void preservesDuplicateBusinessCodesAndAllPhoneIdentities() throws Exception {
        var s=new MasterDataSnapshot(fixture());assertEquals(2,s.rows("crm","skus").size());assertEquals(2,s.rows("crm","phones").size());
        assertEquals("0052",s.phone(s.rows("crm","skus").get(0)).path("modelCode").asText());
        assertTrue(s.listed(s.rows("crm","skus").get(0)));assertFalse(s.listed(s.rows("crm","skus").get(1)));
    }
    @Test void recipeKeepsRepeatedMaterialAtDifferentNodesAndFiltersModelSpecificLines() throws Exception {
        var s=new MasterDataSnapshot(fixture());var recipe=s.recipe(s.rows("crm","skus").get(0),"ROUTE-A");
        assertEquals(List.of(1,2),recipe.stream().map(i->i.path("id").asInt()).toList());
        assertEquals("1234567890123456.12345678",MasterDataSnapshot.decimal(recipe.get(0),"quantity").toPlainString());
    }
    @Test void incompleteOrganizationMissingReferenceOrDuplicateSourceIdIsRejected() throws Exception {
        for(String kind:List.of("complete","org","reference","duplicate")){
            var root=fixture();var sku=(ObjectNode)root.path("crm").path("skus").get(0);
            switch(kind){case "complete"->root.put("complete",false);case "org"->sku.put("organizationId","other");case "reference"->sku.put("phoneModelId","missing");default->((ObjectNode)root.path("crm").path("skus").get(1)).put("id","s1");}
            assertThrows(BusinessException.class,()->new MasterDataSnapshot(root));
        }
    }
    @Test void cyclesCrossRouteTargetsAndUnknownConditionsFailClosed() throws Exception {
        var cyclic=fixture();((ArrayNode)cyclic.path("mes").path("edges")).add(json.readTree("{\"id\":2,\"route_code\":\"ROUTE-A\",\"from_node_code\":\"NODE-B\",\"to_node_code\":\"NODE-A\",\"relation_type\":\"finish_to_start\"}"));
        assertThrows(BusinessException.class,()->new MasterDataSnapshot(cyclic));
        var unknown=fixture();((ObjectNode)unknown.path("mes").path("route_bindings").get(0)).set("condition_json",json.readTree("{\"unverified_filter\":[\"x\"]}"));
        assertThrows(BusinessException.class,()->new MasterDataSnapshot(unknown));
        var wrong=fixture();((ObjectNode)wrong.path("mes").path("bom_items").get(0).path("route_targets").path("ROUTE-A")).put("node_code","missing");
        assertThrows(BusinessException.class,()->new MasterDataSnapshot(wrong));
    }
    @Test void conflictingEqualPriorityBomsAreNotSelectedByRowOrder() throws Exception {
        var root=fixture();((ArrayNode)root.path("mes").path("boms")).add(json.readTree("{\"id\":2,\"bom_code\":\"BOM-B\",\"is_active\":true,\"output_qty\":\"1\"}"));
        var duplicate=root.path("mes").path("bom_bindings").get(0).deepCopy();((ObjectNode)duplicate).put("id",2).put("bom_code","BOM-B");((ArrayNode)root.path("mes").path("bom_bindings")).add(duplicate);
        var s=new MasterDataSnapshot(root);assertThrows(BusinessException.class,()->s.bomFor(s.rows("crm","skus").get(0),"ROUTE-A"));
    }
    @Test void missingOperationMaterialAndOverPrecisionFailBeforeDatabaseWrites() throws Exception {
        for(String kind:List.of("operation","material","quantity","output")){
            var root=fixture();switch(kind){case "operation"->((ArrayNode)root.path("mes").path("operations")).removeAll();case "material"->((ArrayNode)root.path("mes").path("materials")).removeAll();case "quantity"->((ObjectNode)root.path("mes").path("bom_items").get(0)).put("quantity","0.000000001");default->((ObjectNode)root.path("mes").path("boms").get(0)).put("output_qty","1.00001");}
            assertThrows(BusinessException.class,()->new MasterDataSnapshot(root));
        }
    }
    @Test void purchasedSeriesNeedsNoManufacturingBom() throws Exception {
        var root=fixture();((ObjectNode)root.path("crm").path("series").get(0)).put("seriesCode","NSF4030");
        var s=new MasterDataSnapshot(root);assertTrue(s.purchased(s.rows("crm","skus").get(0)));assertTrue(s.routesFor(s.rows("crm","skus").get(0)).isEmpty());
    }
    @Test void localStoreChecksHashCountsAndRejectsPathOrSymlink() throws Exception {
        var dir=Files.createDirectory(temporary.resolve("snapshot-1"));byte[] bytes=json.writeValueAsBytes(fixture());Files.write(dir.resolve("source.json"),bytes);
        var manifest=json.createObjectNode().put("formatVersion",1).put("complete",true).put("sourceFile","source.json").put("organizationId","org").put("sha256",MasterDataSnapshotStore.sha256(bytes)).put("listedSkuCount",1);
        manifest.set("counts",json.readTree("{\"crm\":{\"skus\":2},\"mes\":{\"boms\":1}}"));Files.write(dir.resolve("manifest.json"),json.writeValueAsBytes(manifest));
        var env=new MockEnvironment().withProperty("APP_MASTER_DATA_ENABLED","true").withProperty("APP_MASTER_DATA_SNAPSHOT_ROOT",temporary.toString());var store=new MasterDataSnapshotStore(env,json);
        assertEquals(2,store.load("snapshot-1").snapshot().rows("crm","skus").size());assertThrows(BusinessException.class,()->store.load("../snapshot-1"));
        Files.writeString(dir.resolve("source.json"),"{}");assertThrows(BusinessException.class,()->store.load("snapshot-1"));
        Files.delete(dir.resolve("source.json"));Files.createSymbolicLink(dir.resolve("source.json"),temporary.resolve("elsewhere"));assertThrows(BusinessException.class,()->store.load("snapshot-1"));
    }
    @Test void actualFrozenSnapshotWhenExplicitlyProvided() throws Exception {
        String directory=System.getProperty("master.data.snapshot");Assumptions.assumeTrue(directory!=null);
        Path path=Path.of(directory);var store=new MasterDataSnapshotStore(new MockEnvironment().withProperty("APP_MASTER_DATA_ENABLED","true").withProperty("APP_MASTER_DATA_SNAPSHOT_ROOT",path.getParent().toString()),json);
        var loaded=store.load(path.getFileName().toString());var s=loaded.snapshot();assertEquals(loaded.manifest().path("counts").path("crm").path("skus").asInt(),s.rows("crm","skus").size());
        for(var sku:s.rows("crm","skus"))for(var route:s.routesFor(sku))s.recipe(sku,route.path("route_code").asText());
    }
}
