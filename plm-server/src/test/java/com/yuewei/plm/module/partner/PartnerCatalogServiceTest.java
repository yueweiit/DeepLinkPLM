package com.yuewei.plm.module.partner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuewei.plm.common.exception.BusinessException;
import com.yuewei.plm.common.security.CurrentUser;
import com.yuewei.plm.module.operationlog.service.OperationLogService;
import com.yuewei.plm.module.pilot.PilotAccessService;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import static com.yuewei.plm.module.partner.PartnerValues.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PartnerCatalogServiceTest {
    private JdbcTemplate jdbc;
    private PartnerStore store;
    private PartnerOperationsAccess permissions;
    private SupplierProfileService suppliers;
    private PartnerSelectionService selections;
    private PartnerMaterialService materials;
    private PartnerCatalogPhoneResolver phones;
    private PartnerOperationsService operations;
    private PartnerCatalogService catalog;
    private final String rowId = "e36edce4-66f3-4ba6-b54d-73bfa268eea0";

    @BeforeEach void setup() {
        jdbc = mock(JdbcTemplate.class);
        var access = mock(PilotAccessService.class);
        store = spy(new PartnerStore(jdbc, new ObjectMapper(), access, mock(OperationLogService.class)));
        permissions = mock(PartnerOperationsAccess.class);
        suppliers = mock(SupplierProfileService.class);
        selections = mock(PartnerSelectionService.class);
        materials = mock(PartnerMaterialService.class);
        phones = mock(PartnerCatalogPhoneResolver.class);
        operations = mock(PartnerOperationsService.class);
        catalog = new PartnerCatalogService(store, permissions, suppliers, selections, materials, phones, operations);
        when(access.actorId()).thenReturn(1L);
        when(access.visibleSqlClause(eq("partner"), anyString())).thenReturn("r.record_id in (9,10)");
        when(access.visibleSqlClause(eq("supplier_profile"), anyString())).thenReturn("p.supplier_id=3");
        when(access.visibleSqlClause(eq("file"), anyString())).thenReturn("a.attachment_id=7");
        when(permissions.costs()).thenReturn(true);
        when(jdbc.queryForList(startsWith("select s.style_id as"),anyString(),anyLong(),anyString())).thenReturn(List.of(values("catalogStyleId",1L,"catalogStyleCode","CP-000001","supplierCode","XD","supplierShortCode","XD","fields","{}")));
        when(permissions.engineering()).thenReturn(true);
        when(suppliers.requireSupplier("XD")).thenReturn(profile());
        when(phones.resolveAll(anyList())).thenAnswer(call -> call.getArgument(0));
        var scope=mock(PartnerCatalogPhoneResolver.ResolutionScope.class);when(phones.newScope()).thenReturn(scope);
        when(scope.resolve(anyMap())).thenAnswer(call->resolveForTest(call.getArgument(0)));
        when(selections.validateCatalogChoice(anyMap(),anyList(),anyList())).thenAnswer(call->call.getArgument(2));
        doReturn(values("id", 10L, "code", "SL-000010", "name", "熊大新选品", "version", 3L, "supplierCode", "XD"))
            .when(store).load(eq(10L),eq("selection"),anyBoolean());
    }

    private Map<String, Object> profile() { return Map.of("supplierCode", "XD", "supplierName", "熊大"); }
    private Map<String, Object> values(Object... entries) {
        var result = new LinkedHashMap<String, Object>();
        for (int i = 0; i < entries.length; i += 2) result.put(text(entries[i]), entries[i + 1]);
        return result;
    }
    private Map<String, Object> raw() {
        return values("sourceKind", "selection", "recordId", 9L, "rowId", rowId, "rowVersion", 1L,
            "sourceCode", "SL-000009", "sourceName", "熊大选品", "recordVersion", 4L,
            "supplierCode", "XD", "name", "熊抱支架款", "modelText", "iPhone11到18", "phoneModelIds", List.of(12L),
            "phones", List.of(Map.of("phoneModelId", 12L, "phoneModelCode", "0185", "displayName", "iPhone11")),
            "confirmed", false, "unitPrice", "12.00", "currency", "", "priceNote", "黑色加价", "notes", "原报价含价备注",
            "refs", List.of(), "confirmationSnapshot", Map.of("unitPrice", "88.00"), "unexpectedPayload", "不得外露");
    }
    private Map<String, Object> item(long record, String supplier) {
        return values("supplierCode", supplier, "requestId", "add-once", "targetSelectionId", 10L, "targetVersion", 3L,
            "items", List.of(values("sourceKind", "selection", "recordId", record, "rowId", rowId, "rowVersion", 1L,"recordVersion",4L,"phoneModelIds",List.of(12L))));
    }
    private Map<String,Object> resolveForTest(Map<String,Object> input){var row=new LinkedHashMap<>(input);row.putIfAbsent("phoneModelIds",List.of(12L));row.put("phones",PartnerSelectionService.scopeIds(row.get("phoneModelIds")).stream().map(id->Map.<String,Object>of("phoneModelId",id,"displayName","iPhone "+id)).toList());return row;}
    private Map<String,Object> previewed(Map<String,Object> input){var preview=catalog.additionPreview(input);assertEquals(true,preview.get("canAdd"),preview.toString());input.put("previewToken",preview.get("previewToken"));return input;}
    private void writableTarget(Map<String,Map<String,Object>> savedRows,java.util.concurrent.atomic.AtomicLong version){
        doReturn(null).when(store).created(eq("catalog_add"),anyString());
        doAnswer(call->values("id",10L,"code","SL-000010","name","工程选品","version",version.get(),"supplierCode","XD")).when(store).load(eq(10L),eq("selection"),anyBoolean());
        doAnswer(call->maps(call.getArgument(1))).when(store).canonicalFiles(eq(9L),any());doNothing().when(store).remember(anyString(),anyString(),anyLong());doNothing().when(store).event(anyLong(),anyString(),anyMap(),anyString());
        when(jdbc.queryForList(startsWith("select row_id::text,row_version,data::text,confirmed"),eq(10L),eq("selection"),eq("9"),eq(rowId))).thenAnswer(call->new ArrayList<>(savedRows.values()));
        when(selections.patchCatalogRows(eq(10L),anyMap())).thenAnswer(call->{for(var operation:maps(map(call.getArgument(1)).get("operations"))){String id=text(operation.get("rowId"));savedRows.put(id,values("row_id",id,"row_version",1L,"data",encode(store.json,operation.get("changes")),"confirmed",false));}return values("id",10L,"code","SL-000010","name","工程选品","version",version.incrementAndGet());});
    }
    private void sourceRow(String actualSupplier) {
        doReturn(values("id", 9L, "code", "SL-000009", "name", "熊大", "version", 4L)).when(store).load(9L, "selection", false);
        when(jdbc.queryForList(contains("from plm_partner_selection_row where record_id=? and row_id=?::uuid"), eq(9L), eq(rowId)))
            .thenReturn(List.of(values("row_id", rowId, "row_version", 1L, "confirmed", false,
                "row_data", encode(store.json, values("name", "熊大款", "supplierCode", actualSupplier, "modelText", "iPhone11", "refs", List.of())))));
    }

    @Test void supplierMustBeChosenBeforeAnyStyleQuery() {
        assertThrows(BusinessException.class, () -> catalog.styles(Map.of()));
        verifyNoInteractions(jdbc, suppliers, phones);
    }

    @Test void suppliersAndStyleCountsApplyBothRecordAndSupplierObjectScopes() {
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(0L);
        var result = catalog.suppliers(Map.of("q", "熊大", "page", "2", "size", "10"));
        assertEquals(2L, result.get("page"));
        var sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForList(sql.capture(), any(Object[].class));
        assertTrue(sql.getValue().contains("r.record_id in (9,10)"));
        assertTrue(sql.getValue().contains("p.supplier_id=3"));
        assertTrue(sql.getValue().contains("r.deleted_flag=0"));
        assertTrue(sql.getValue().contains("p.deleted_flag=0"));
        assertTrue(sql.getValue().contains("s.data->>'supplierCode'"));
        assertTrue(sql.getValue().contains("not exists(select 1 from plm_partner_event"));
        assertTrue(sql.getValue().contains("e.data->'createdRowIds'"));
        var countSql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForObject(countSql.capture(), eq(Long.class), eq(new Object[] {"%熊大%", "%熊大%", "%熊大%"}));
        String suppliersFrom = "from plm_supplier_profile p join catalog_suppliers c on c.supplier_code=p.supplier_code where ";
        assertTrue(sql.getValue().contains(suppliersFrom));
        assertTrue(countSql.getValue().contains(suppliersFrom));
        assertTrue(countSql.getValue().contains("r.record_id in (9,10)"));
        assertTrue(countSql.getValue().contains("p.supplier_id=3"));
    }

    @Test void creatingACandidateSourceReusesSupplierQuoteCreationAndIgnoresProductOrApprovalFacts(){
        when(operations.create(eq("supplier_quote"),anyMap())).thenReturn(values("id",20L,"kind","supplier_quote","code","SQ-000020","name","来源原件","status","draft","version",1L,"supplierCode","XD"));
        var created=catalog.createSource(values("name","来源原件","supplierCode","XD","requestId","source-one","productId",99,"status","confirmed","sampleQty",3));
        assertEquals("quote",created.get("sourceKind"));assertEquals("catalog_source",created.get("purpose"));assertEquals(true,created.get("canImport"));
        var command=ArgumentCaptor.forClass(Map.class);verify(operations).create(eq("supplier_quote"),command.capture());assertEquals(Set.of("name","supplierCode","requestId"),command.getValue().keySet());verifyNoInteractions(selections,materials,phones);verify(store,never()).create(anyString(),anyString());
    }

    @Test void sourceHeaderUsesAuthoritativeColumnsRatherThanStaleJsonVersions(){
        Map<String,Object> projected=org.springframework.test.util.ReflectionTestUtils.invokeMethod(catalog,"sourceHeader",values("id",42L,"version",9L,"rowCount",3L,"kind","supplier_quote","status","draft","data","{\"id\":1,\"version\":1,\"rowCount\":999,\"kind\":\"selection\"}"));
        assertEquals(42L,projected.get("id"));assertEquals(9L,projected.get("version"));assertEquals(3L,projected.get("rowCount"));assertEquals("quote",projected.get("sourceKind"));
    }

    @Test void aPurposePreviewRejectsActiveJobsAndAdoptedRowsWithoutTouchingTheirData(){
        doReturn(values("id",9L,"kind","selection","name","历史原件","version",4L,"supplierCode","XD")).when(store).load(9L,"selection",false);
        when(jdbc.queryForMap(startsWith("select count(*) as \"rowCount\""),eq(9L))).thenReturn(values("rowCount",43L,"adoptedCount",0L));
        var command=values("version",4L,"purpose","catalog_source","reason","明确核实原件来源");assertEquals(true,catalog.purposePreview(9L,command).get("canChange"));
        when(jdbc.queryForObject(contains("plm_partner_file_job"),eq(Boolean.class),eq(9L),eq(9L))).thenReturn(true);
        assertEquals(false,catalog.purposePreview(9L,command).get("canChange"));
        when(jdbc.queryForObject(contains("plm_partner_file_job"),eq(Boolean.class),eq(9L),eq(9L))).thenReturn(false);
        when(jdbc.queryForMap(startsWith("select count(*) as \"rowCount\""),eq(9L))).thenReturn(values("rowCount",43L,"adoptedCount",1L));
        assertEquals(false,catalog.purposePreview(9L,command).get("canChange"));verify(store,never()).save(anyLong(),anyMap());verify(store,never()).event(anyLong(),anyString(),anyMap(),anyString());
    }

    @Test void sourceDetailsNeverQueryRestrictedOriginalsForCostlessReadersAndFinancialFilesStayExcluded(){
        sourceRow("XD");doReturn(values("id",9L,"kind","selection","name","历史原件","version",4L,"supplierCode","XD")).when(store).load(9L,"selection",false);
        when(jdbc.queryForList(startsWith("select kind from plm_partner_record"),eq(9L))).thenReturn(List.of(Map.of("kind","selection")));
        when(jdbc.queryForObject(anyString(),eq(Long.class),eq(9L))).thenReturn(19L);
        when(permissions.costs()).thenReturn(false);assertTrue(maps(catalog.sourceDetail(9L).get("files")).isEmpty());verify(jdbc,never()).queryForList(contains("f.purpose<>'financial'"),eq(9L));
        when(permissions.costs()).thenReturn(true);catalog.sourceDetail(9L);verify(jdbc).queryForList(contains("f.purpose<>'financial'"),eq(9L));verify(jdbc).queryForList(contains("a.attachment_id=7"),eq(9L));
    }

    @Test void explicitEmptyAndOutOfSourcePhoneChoicesReturnRowErrorsInsteadOfCopyingTheWholeRange(){
        sourceRow("XD");var command=item(9L,"XD");maps(command.get("items")).get(0).put("phoneModelIds",List.of());assertEquals(false,catalog.additionPreview(command).get("canAdd"));
        maps(command.get("items")).get(0).put("phoneModelIds",List.of(99L));assertEquals(false,catalog.additionPreview(command).get("canAdd"));verifyNoInteractions(materials);verify(selections,never()).patchCatalogRows(anyLong(),anyMap());
    }

    /** Actual PostgreSQL behavior, with temporary tables rolled back in a dedicated loopback QA database. */
    @Nested
    @EnabledIfEnvironmentVariable(named = "PLM_CATALOG_NONEMPTY_TEST_URL",
        matches = "jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/catalog_nonempty_filter_tests")
    class NonemptySupplierDatabaseTests {
        private DataSourceTransactionManager transactions;
        private TransactionStatus transaction;
        private PilotAccessService scopedAccess;
        private OperationLogService renameAudit;

        @BeforeEach void database() {
            var source = new DriverManagerDataSource(System.getenv("PLM_CATALOG_NONEMPTY_TEST_URL"), "postgres",
                System.getenv("PLM_CATALOG_NONEMPTY_TEST_PASSWORD"));
            jdbc = new JdbcTemplate(source);
            transactions = new DataSourceTransactionManager(source);
            transaction = transactions.getTransaction(new DefaultTransactionDefinition());
            jdbc.execute("""
                create temp table plm_supplier_profile(supplier_id bigint,supplier_code text,supplier_name text,short_code text,status text,deleted_flag int default 0);
                create temp table plm_partner_record(record_id bigint,kind text,code text,name text,status text default 'draft',version bigint,updated_at timestamptz default now(),supplier_code text,deleted_flag int default 0,data jsonb default '{}');
                create temp table plm_partner_selection_row(record_id bigint,row_id uuid,row_version bigint,sort_order bigint,data jsonb,confirmed boolean default false);
                create temp table plm_partner_quote_line(record_id bigint,line_id uuid,row_version bigint,sort_order bigint,data jsonb);
                create temp table plm_partner_event(record_id bigint,action text,data jsonb);
                create temp table plm_partner_catalog_style(style_id bigint,code text,supplier_code text);
                create temp table plm_partner_catalog_style_source(source_kind text,record_id bigint,row_id uuid,style_id bigint,fields jsonb);
                insert into plm_supplier_profile values
                    (1,'EMPTY','A Empty','EM','active',0),(2,'QUOTE','B Quote','BQ','draft',0),
                    (3,'SELECTION','C Selection','CS','active',0),(4,'DISCARDED','D Discarded','DC','active',0),
                    (5,'DELETED_RECORD','E Deleted record','DR','active',0),(6,'HIDDEN_RECORD','F Hidden record','HR','active',0),
                    (7,'HIDDEN_PROFILE','G Hidden profile','HP','active',0),(8,'DERIVED','H Derived only','DO','active',0),
                    (9,'DELETED_PROFILE','I Deleted profile','DP','active',1),(10,'MISMATCHED_QUOTE','J Mismatched quote','MQ','active',0);
                insert into plm_partner_record(record_id,kind,code,name,version,supplier_code,deleted_flag) values
                    (10,'supplier_quote','SQ-10','Quote',1,'QUOTE',0),(20,'selection','SL-20','Selection',1,'SELECTION',0),
                    (30,'selection','SL-30','Discarded',1,'DISCARDED',0),(40,'selection','SL-40','Deleted',1,'DELETED_RECORD',1),
                    (50,'selection','SL-50','Hidden record',1,'HIDDEN_RECORD',0),(60,'selection','SL-60','Hidden profile',1,'HIDDEN_PROFILE',0),
                    (70,'selection','SL-70','Derived copy',1,'DERIVED',0),(80,'selection','SL-80','Deleted profile',1,'DELETED_PROFILE',0),
                    (90,'supplier_quote','SQ-90','Mismatched quote',1,'MISMATCHED_QUOTE',0);
                """);
            quote(10, Map.of());
            quote(10, Map.of("supplierCode", "QUOTE"));
            quote(90, Map.of("supplierCode", "QUOTE"));
            selection(20, "SELECTION", false);
            selection(30, "DISCARDED", true);
            selection(40, "DELETED_RECORD", false);
            selection(50, "HIDDEN_RECORD", false);
            selection(60, "HIDDEN_PROFILE", false);
            String derived = selection(70, "DERIVED", false);
            selection(80, "DELETED_PROFILE", false);
            jdbc.update("insert into plm_partner_event values(70,'catalog_add',?::jsonb)",
                encode(store.json, Map.of("createdRowIds", List.of(derived))));
            scopedAccess = mock(PilotAccessService.class);
            when(scopedAccess.visibleSqlClause("partner", "r.record_id")).thenReturn("r.record_id<>50");
            when(scopedAccess.visibleSqlClause("supplier_profile", "p.supplier_id")).thenReturn("p.supplier_id<>7");
            var scopedStore = new PartnerStore(jdbc, new ObjectMapper(), scopedAccess, mock(OperationLogService.class));
            catalog = new PartnerCatalogService(scopedStore, permissions, suppliers, selections, materials, phones, operations);
        }

        private String selection(long recordId, String supplier, boolean discarded) {
            String id = UUID.randomUUID().toString();
            jdbc.update("insert into plm_partner_selection_row values(?,?::uuid,1,1,?::jsonb,true)", recordId, id,
                encode(store.json, Map.of("supplierCode", supplier, "discarded", discarded)));
            return id;
        }

        private void quote(long recordId, Map<String, Object> data) {
            jdbc.update("insert into plm_partner_quote_line values(?,?::uuid,1,1,?::jsonb)", recordId,
                UUID.randomUUID().toString(), encode(store.json, data));
        }

        private List<String> codes(Map<String, Object> result) {
            return maps(result.get("content")).stream().map(row -> text(row.get("supplierCode"))).toList();
        }

        @AfterEach void rollback() {
            if (transaction != null && !transaction.isCompleted()) transactions.rollback(transaction);
        }

        private void renameFixture(){
            jdbc.execute("""
                alter table plm_partner_record add column site text,add column external_id text,add column customer_id bigint,add column source_project_id bigint,add column updated_by text,add column parent_record_id bigint;
                alter table plm_partner_selection_row add column updated_at timestamptz default now(),add column updated_by text;
                alter table plm_partner_quote_line add column updated_at timestamptz default now(),add column updated_by text;
                alter table plm_partner_event add column request_id text,add column created_by text,add column actor_id bigint;
                create temp table plm_partner_revision(record_id bigint,revision bigint,data jsonb,created_by text);
                create temp table plm_partner_selection_row_revision(record_id bigint,row_id uuid,row_version bigint,request_id text,data jsonb,created_by text);
                create temp table plm_partner_quote_line_revision(record_id bigint,line_id uuid,row_version bigint,request_id text,data jsonb,created_by text);
                create temp table plm_attachment(attachment_id bigint,deleted_flag int default 0,file_name text,version_no text,content_type text,file_size bigint);
                create temp table plm_partner_file(record_id bigint,attachment_id bigint,purpose text default 'photo');
                create temp table plm_partner_file_job_row(job_id bigint,row_id uuid);
                create temp table plm_partner_file_job(job_id bigint,record_id bigint,source_attachment_id bigint,kind text,status text);
                insert into plm_attachment values(88,0,'现用产品图.png','V1','image/png',100);insert into plm_partner_file(record_id,attachment_id) values(10,88),(20,88);
                """);
            when(scopedAccess.actor()).thenReturn(new CurrentUser(21L,"qa-rename","QA名称维护",false));when(scopedAccess.actorId()).thenReturn(21L);
            when(scopedAccess.canMaintainEngineeringData()).thenReturn(true);when(scopedAccess.hasPermission(anyString())).thenReturn(true);
            renameAudit=mock(OperationLogService.class);store=new PartnerStore(jdbc,new ObjectMapper(),scopedAccess,renameAudit);
            when(suppliers.requireSupplier(anyString())).thenAnswer(call->Map.of("supplierCode",call.getArgument(0),"supplierName","QA供方"));
            selections=new PartnerSelectionService(store,permissions,suppliers,mock(PartnerProductService.class),materials);
            catalog=new PartnerCatalogService(store,permissions,suppliers,selections,materials,phones,operations);
        }

        @Test void nameCorrectionUpdatesLegacyAndQuoteSourcesWithoutChangingCodesImagesQuotesOrFrozenSnapshots(){
            renameFixture();
            for(String kind:List.of("selection","quote")){
                long id=kind.equals("selection")?20:10;String table=kind.equals("selection")?"plm_partner_selection_row":"plm_partner_quote_line",column=kind.equals("selection")?"row_id":"line_id",supplier=kind.equals("selection")?"SELECTION":"QUOTE";
                String rid=jdbc.queryForObject("select "+column+"::text from "+table+" where record_id=? limit 1",String.class,id);
                var original=values("name","凡影大孔手机壳","supplierCode",supplier,"unitPrice","3.80","currency","","refs",List.of(Map.of("id",88)),"phoneModelIds",List.of(1218),"confirmationSnapshot",Map.of("row",Map.of("name","凡影大孔手机壳")));
                jdbc.update("update "+table+" set data=?::jsonb where record_id=? and "+column+"=?::uuid",encode(store.json,original),id,rid);
                var result=catalog.updateStyle(kind,id,rid,supplier,values("name","凡影精孔手机壳","rowVersion",1,"recordVersion",1,"requestId","rename-"+kind));
                var after=decode(store.json,jdbc.queryForObject("select data::text from "+table+" where record_id=? and "+column+"=?::uuid",String.class,id,rid));assertEquals(Map.of("name","凡影精孔手机壳"),after.remove("catalogCurrent"));assertEquals(original,after);
                assertEquals(2L,result.get("rowVersion"));assertEquals(2L,result.get("recordVersion"));assertEquals(2L,jdbc.queryForObject("select version from plm_partner_record where record_id=?",Long.class,id));
                assertEquals(1L,jdbc.queryForObject("select count(*) from "+(kind.equals("quote")?"plm_partner_quote_line_revision":"plm_partner_selection_row_revision")+" where record_id=?",Long.class,id));
                assertThrows(BusinessException.class,()->catalog.updateStyle(kind,id,rid,supplier,values("name","过期覆盖","rowVersion",1,"recordVersion",1,"requestId","stale-"+kind)));
            }
            assertEquals(0L,jdbc.queryForObject("select count(*) from plm_partner_catalog_style",Long.class));assertEquals(2L,jdbc.queryForObject("select count(*) from plm_partner_event where action='catalog_style_update' and actor_id=21",Long.class));var actor=scopedAccess.actor();verify(renameAudit,times(2)).logRequired(any(),eq(actor));
        }

        @Test void renameRejectsOtherFieldsAndUnauthorizedWritesAndRollsBackWhenRequiredAuditFails(){
            renameFixture();String rid=jdbc.queryForObject("select row_id::text from plm_partner_selection_row where record_id=20",String.class);var input=values("name","凡影精孔手机壳","rowVersion",1,"recordVersion",1,"requestId","rename-audit");
            var invalid=new LinkedHashMap<>(input);invalid.put("supplierCode","OTHER");assertThrows(BusinessException.class,()->catalog.updateStyle("selection",20,rid,"SELECTION",invalid));
            doThrow(new BusinessException(40103,"无工程维护权限")).when(permissions).engineer();assertThrows(BusinessException.class,()->catalog.updateStyle("selection",20,rid,"SELECTION",input));doNothing().when(permissions).engineer();
            String before=jdbc.queryForObject("select data::text from plm_partner_selection_row where row_id=?::uuid",String.class,rid);doThrow(new IllegalStateException("QA审计不可用")).when(renameAudit).logRequired(any(),any());
            var nested=new TransactionTemplate(transactions);nested.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_NESTED);
            assertThrows(IllegalStateException.class,()->nested.execute(status->catalog.updateStyle("selection",20,rid,"SELECTION",input)));
            assertEquals(before,jdbc.queryForObject("select data::text from plm_partner_selection_row where row_id=?::uuid",String.class,rid));assertEquals(1L,jdbc.queryForObject("select version from plm_partner_record where record_id=20",Long.class));assertEquals(0L,jdbc.queryForObject("select count(*) from plm_partner_selection_row_revision",Long.class));assertEquals(0L,jdbc.queryForObject("select count(*) from plm_partner_event where action='catalog_style_update'",Long.class));
        }
        @Test void catalogFieldsRefreshDraftsButKeepConfirmedPriceReceiptBasisAndStableIdentities(){
            renameFixture();String rid=jdbc.queryForObject("select row_id::text from plm_partner_selection_row where record_id=20",String.class);
            var frozen=Map.of("name","原名称","unitPrice","3.80","color","黑色");var original=values("name","原名称","supplierCode","SELECTION","unitPrice","3.80","currency","CNY","color","黑色","material","TPU","confirmed",true,"confirmationSnapshot",Map.of("row",frozen));
            jdbc.update("update plm_partner_selection_row set data=?::jsonb where record_id=20",encode(store.json,original));
            jdbc.update("insert into plm_partner_record(record_id,kind,code,name,version,data) values(100,'selection','SL-DRAFT','工程选品',1,'{\"purpose\":\"my_selection\"}')");
            var linked=values("name","原名称","supplierCode","SELECTION","unitPrice","3.80","currency","CNY","color","黑色","material","TPU","catalogStyleId",23L,"catalogSourceSnapshot",Map.of("sourceKind","selection","recordId",20L,"rowId",rid));
            String draft=UUID.randomUUID().toString(),confirmed=UUID.randomUUID().toString();
            jdbc.update("insert into plm_partner_selection_row(record_id,row_id,row_version,sort_order,data,confirmed) values(100,?::uuid,1,1,?::jsonb,false),(100,?::uuid,1,2,?::jsonb,true)",draft,encode(store.json,linked),confirmed,encode(store.json,linked));
            var result=catalog.updateStyle("selection",20,rid,"SELECTION",values("rowVersion",1,"recordVersion",1,"requestId","full-edit","changes",Map.of("name","凡盾大孔手机壳","color","蓝色","material","PC","unitPrice","0","currency","MXN")));
            var saved=decode(store.json,jdbc.queryForObject("select data::text from plm_partner_selection_row where record_id=20",String.class));
            var catalogCurrent=PartnerCatalogStyles.currentData(saved);assertEquals("0.00",catalogCurrent.get("unitPrice"));assertEquals("MXN",catalogCurrent.get("currency"));assertEquals("explicit",catalogCurrent.get("currencySource"));assertEquals(Map.of("row",frozen),saved.get("confirmationSnapshot"));assertEquals(true,saved.get("confirmed"));assertEquals("3.80",saved.get("unitPrice"));assertEquals("CNY",saved.get("currency"));assertEquals("TPU",saved.get("material"));
            var displayed=maps(catalog.styles(Map.of("supplierCode","SELECTION")).get("content")).get(0);assertEquals("凡盾大孔手机壳",displayed.get("name"));assertEquals("0.00",displayed.get("unitPrice"));assertEquals("MXN",displayed.get("currency"));assertEquals("PC",catalog.detail("selection",20,rid,"SELECTION").get("material"));
            jdbc.update("insert into plm_partner_catalog_style values(23,'CP-000023','SELECTION')");jdbc.update("insert into plm_partner_catalog_style_source values('selection',20,?::uuid,23,'{}')",rid);
            var sample=new LinkedHashMap<String,Object>(Map.of("catalogStyleId",23L,"sampleCode","SY-UNCHANGED","name","原名称","colorName","实收黑色"));new PartnerCatalogStyles(store).projectCurrent(List.of(sample));assertEquals("凡盾大孔手机壳",sample.get("name"));assertEquals("实收黑色",sample.get("colorName"));assertEquals("0.00",map(sample.get("currentCatalog")).get("unitPrice"));
            when(scopedAccess.canMaintainEngineeringData()).thenReturn(false);when(scopedAccess.hasPermission("finance:read")).thenReturn(false);new PartnerCatalogStyles(store).projectCurrent(List.of(sample));assertFalse(map(sample.get("currentCatalog")).containsKey("unitPrice"));when(scopedAccess.canMaintainEngineeringData()).thenReturn(true);
            var current=new PartnerSelectionRows(store).find(100,draft,false);assertEquals("凡盾大孔手机壳",current.get("name"));assertEquals("PC",current.get("material"));assertEquals("蓝色",current.get("color"));assertEquals("0.00",current.get("unitPrice"));assertEquals(2L,current.get("rowVersion"));assertEquals(signature(store.json,linked.get("catalogSourceSnapshot")),signature(store.json,current.get("catalogSourceSnapshot")));
            var historical=new PartnerSelectionRows(store).find(100,confirmed,false);assertEquals("原名称",historical.get("name"));assertEquals("3.80",historical.get("unitPrice"));assertEquals(1L,historical.get("rowVersion"));assertEquals(2L,result.get("recordVersion"));
            assertThrows(BusinessException.class,()->catalog.updateStyle("selection",20,rid,"SELECTION",values("rowVersion",2,"recordVersion",2,"requestId","bad-price","changes",Map.of("unitPrice","-1"))));
            catalog.updateStyle("selection",20,rid,"SELECTION",values("rowVersion",2,"recordVersion",2,"requestId","unknown-price","changes",Map.of("unitPrice","")));
            assertEquals("",new PartnerSelectionRows(store).find(100,draft,false).get("unitPrice"));
        }
        @Test void removalAndRestoreUseExistingVersionedRowWithoutDeletingDownstreamOrAllocatingAnotherCp(){
            renameFixture();String rid=jdbc.queryForObject("select row_id::text from plm_partner_selection_row where record_id=20",String.class);jdbc.update("update plm_partner_selection_row set data=data||'{\"name\":\"可恢复产品\"}'::jsonb where record_id=20");
            catalog.updateStyle("selection",20,rid,"SELECTION",values("rowVersion",1,"recordVersion",1,"requestId","remove","changes",Map.of("discarded",true)));
            assertEquals(0L,catalog.styles(Map.of("supplierCode","SELECTION")).get("totalElements"));assertEquals(1L,catalog.styles(Map.of("supplierCode","SELECTION","deleted","true")).get("totalElements"));
            assertThrows(BusinessException.class,()->catalog.detail("selection",20,rid,"SELECTION"));assertEquals(true,catalog.detail("selection",20,rid,"SELECTION",true).get("discarded"));
            catalog.updateStyle("selection",20,rid,"SELECTION",values("rowVersion",2,"recordVersion",2,"requestId","restore","changes",Map.of("discarded",false)));
            assertEquals(1L,catalog.styles(Map.of("supplierCode","SELECTION")).get("totalElements"));assertEquals(0L,catalog.styles(Map.of("supplierCode","SELECTION","deleted","true")).get("totalElements"));assertEquals(0L,jdbc.queryForObject("select count(*) from plm_partner_catalog_style",Long.class));
            assertEquals(1L,catalog.styles(Map.of("supplierCode","SELECTION","sourceRecordId","20","sourceKind","selection","size","1","page","3")).get("totalElements"));assertEquals(0L,catalog.styles(Map.of("supplierCode","SELECTION","sourceRecordId","10","sourceKind","quote")).get("totalElements"));
        }

        @Test void nonemptySuppliersAreFilteredBeforePaginationAndBothSourceKindsCountWithoutCopies() {
            var first = catalog.suppliers(Map.of("size", "1"));
            var second = catalog.suppliers(Map.of("page", "2", "size", "1"));
            var third = catalog.suppliers(Map.of("page", "3", "size", "1"));
            assertEquals(List.of("QUOTE"), codes(first));
            assertEquals(2L, maps(first.get("content")).get(0).get("styleCount"));
            assertEquals(List.of("SELECTION"), codes(second));
            assertEquals(1L, maps(second.get("content")).get(0).get("styleCount"));
            assertTrue(codes(third).isEmpty());
            for (var result : List.of(first, second, third)) {
                assertEquals(2L, result.get("totalElements"));
                assertEquals(2L, result.get("totalPages"));
            }
            verifyNoInteractions(phones, selections, materials, suppliers);
        }

        @Test void searchByNameCodeAndShortCodeUsesTheSameNonemptySupplierSetAndNeverDeletesProfiles() {
            String before = jdbc.queryForObject("select string_agg(to_jsonb(p)::text,',' order by p.supplier_code) from plm_supplier_profile p", String.class);
            for (String keyword : List.of("b quote", "QUOTE", "bq")) {
                var found = catalog.suppliers(Map.of("q", keyword));
                assertEquals(List.of("QUOTE"), codes(found));
                assertEquals(1L, found.get("totalElements"));
            }
            for (String keyword : List.of("Empty", "Discarded", "Hidden", "Derived", "Deleted", "Mismatched")) {
                var empty = catalog.suppliers(Map.of("keyword", keyword));
                assertTrue(codes(empty).isEmpty(), keyword);
                assertEquals(0L, empty.get("totalElements"), keyword);
            }
            assertEquals(before, jdbc.queryForObject("select string_agg(to_jsonb(p)::text,',' order by p.supplier_code) from plm_supplier_profile p", String.class));
        }

        @Test void hidingTheOnlyVisibleStyleOrSupplierAlsoRemovesItsCardAndCount() {
            when(scopedAccess.visibleSqlClause("partner", "r.record_id")).thenReturn("r.record_id not in (20,50)");
            assertEquals(List.of("QUOTE"), codes(catalog.suppliers(Map.of())));
            when(scopedAccess.visibleSqlClause("supplier_profile", "p.supplier_id")).thenReturn("p.supplier_id not in (2,7)");
            var result = catalog.suppliers(Map.of());
            assertTrue(codes(result).isEmpty());
            assertEquals(0L, result.get("totalElements"));
        }

        @Test void aFutureSupplierAppearsOnItsFirstStyleAndDisappearsWhenItsLastStyleIsExcluded() {
            jdbc.update("insert into plm_supplier_profile values(11,'FUTURE','K Future','KF','draft',0)");
            jdbc.update("insert into plm_partner_record(record_id,kind,code,name,version,supplier_code) values(100,'selection','SL-100','Future',1,'FUTURE')");
            assertEquals(2L, catalog.suppliers(Map.of()).get("totalElements"));
            selection(100, "FUTURE", false);
            var added = catalog.suppliers(Map.of());
            assertEquals(List.of("QUOTE", "SELECTION", "FUTURE"), codes(added));
            assertEquals(3L, added.get("totalElements"));
            jdbc.update("update plm_partner_selection_row set data=data||'{\"discarded\":true}'::jsonb where record_id=100");
            assertEquals(2L, catalog.suppliers(Map.of()).get("totalElements"));
            assertEquals(1L, jdbc.queryForObject("select count(*) from plm_supplier_profile where supplier_code='FUTURE'", Long.class));
        }
    }

    @Test void costlessProjectionIsAWhitelistNotHiddenFullSourceJson() {
        when(permissions.costs()).thenReturn(false);
        var source = raw();
        source.put("priceOptions", List.of(Map.of("label", "普通款", "unitPrice", "12.00", "currency", "CNY")));
        source.put("sampleFeeNote", "样品费实际约定");
        source.put("pairings",List.of(values("phoneModelId",12L,"colorId",3L,"state","available","unitPrice","987.65","currency","USD","terms","秘密合同","evidence","原文含价说明","phoneSnapshot",values("phoneModelId",12L,"displayName","iPhone11","phoneModelCode","0185","raw","完整原始手机数据"),"colorSnapshot",values("id",3L,"name","供方黑色","supplierColorCode","BK","notes","隐含报价条件"))));
        var result = catalog.project(source, profile(), Map.of(), true);
        assertEquals("熊抱支架款", result.get("name"));
        assertEquals("0185", maps(result.get("phones")).get(0).get("phoneModelCode"));
        for (String key : List.of("unitPrice", "currency", "priceNote", "notes", "priceOptions", "sampleFeeNote", "confirmationSnapshot", "unexpectedPayload", "sourceOriginals"))
            assertFalse(result.containsKey(key), key);
        assertEquals(List.of(values("phoneModelId",12L,"colorId",3L,"state","available","phoneSnapshot",values("phoneModelId",12L,"displayName","iPhone11","phoneModelCode","0185"),"colorSnapshot",values("id",3L,"name","供方黑色","supplierColorCode","BK"))),result.get("pairings"));for(String secret:List.of("987.65","USD","秘密合同","原文含价说明","完整原始手机数据","隐含报价条件"))assertFalse(result.toString().contains(secret),secret);assertFalse(catalog.project(source,profile(),Map.of(),false).containsKey("pairings"));
        assertEquals(false, result.get("canReadCost"));
    }

    @Test void costReaderKeepsUnspecifiedCurrencyRatherThanGuessing() {
        var source = raw();
        source.put("priceOptions", List.of(Map.of("label", "磁吸款", "unitPrice", "12.00", "currency", "", "unexpectedSecret", "不返回")));
        source.put("pairings",List.of(values("phoneModelId",12L,"colorId",3L,"state","available","unitPrice","9.80","currency","CNY","evidence","有成本岗位继续编辑原条件")));
        var result = catalog.project(source, profile(), Map.of(), true);
        assertEquals("12.00", result.get("unitPrice"));
        assertEquals("", result.get("currency"));
        assertEquals("原报价含价备注", result.get("notes"));
        assertFalse(result.containsKey("unexpectedPayload"));
        assertEquals("", maps(result.get("priceOptions")).get(0).get("currency"));
        assertFalse(maps(result.get("priceOptions")).get(0).containsKey("unexpectedSecret"));
        assertEquals(source.get("pairings"),result.get("pairings"));assertFalse(catalog.project(source,profile(),Map.of(),false).containsKey("pairings"));
    }

    @Test void explicitWholeModelExclusionsReachSummaryAndDetailForBothCostRoles() {
        var source = raw();
        source.put("phoneModelIds", List.of(12L, 13L));
        source.put("unavailablePhoneModelIds", List.of(13L, 13L));
        source.put("pairings", List.of(values("phoneModelId", 12L, "colorId", 3L,
            "state", "unavailable", "unitPrice", "93.47", "evidence", "仅该颜色不供")));
        for (boolean costs : List.of(false, true)) {
            when(permissions.costs()).thenReturn(costs);
            for (boolean detail : List.of(false, true)) {
                var projected = catalog.project(source, profile(), Map.of(), detail);
                assertEquals(List.of(13L), projected.get("unavailablePhoneModelIds"));
                assertEquals(List.of(12L, 13L), projected.get("phoneModelIds"));
            }
        }
        source.remove("unavailablePhoneModelIds");
        assertEquals(List.of(), catalog.project(source, profile(), Map.of(), true).get("unavailablePhoneModelIds"));
    }

    @Test void scopeModeComesFromSupplierDeclarationsEvenWhenTheClientClaimsOtherwise() {
        var unspecified = raw();
        for (String field : List.of("modelText", "phoneModelIds", "phones")) unspecified.remove(field);
        unspecified.put("phoneScopeMode", "unspecified");
        assertEquals("unresolved", catalog.project(unspecified, profile(), Map.of(), true).get("phoneScopeMode"));
        unspecified.put("phoneScopeDeclaration", "not_provided");
        unspecified.put("phoneScopeMode", "supplier");
        assertEquals("unspecified", catalog.project(unspecified, profile(), Map.of(), true).get("phoneScopeMode"));
        var declarations = List.of(
            values("modelText", "无法匹配的供方型号"),
            values("phoneModelIds", List.of(12L)),
            values("phoneModelIds", List.of("损坏的手机ID")),
            values("phones", List.of(values("phoneModelId", 12L))),
            values("availablePhoneModelIds", List.of(12L)),
            values("unavailablePhoneModelIds", List.of(13L)),
            values("rangeRefs", List.of(values("type", "model", "reference", "待核对范围"))),
            values("rangeSnapshots", List.of(values("type", "model", "members", List.of(12L)))),
            values("rangeRefs", List.of("损坏的范围引用")),
            values("rangeRefs", List.of(values("reference", "缺少类型"))),
            values("pairings", List.of(values("phoneModelId", 12L, "colorId", 3L, "state", "unavailable"))),
            values("selectedPairs", List.of(values("phoneModelId", 12L, "colorId", 3L))));
        for (var declaration : declarations) {
            var source = new LinkedHashMap<>(unspecified);source.putAll(declaration);source.put("phoneScopeMode", "unspecified");
            for (boolean detail : List.of(false, true))
                assertEquals("supplier", catalog.project(source, profile(), Map.of(), detail).get("phoneScopeMode"), declaration.toString());
        }
        unspecified.put("rangeRefs", List.of(values("type", "color", "reference", "供方原色")));
        assertEquals("unspecified", catalog.project(unspecified, profile(), Map.of(), true).get("phoneScopeMode"));
        unspecified.remove("phoneScopeDeclaration");
        assertEquals("unresolved", catalog.project(unspecified, profile(), Map.of(), true).get("phoneScopeMode"));
    }

    @Test void candidateCopyExcludesProductSampleAndOtherConfirmationFacts() {
        var source = raw();
        source.putAll(values("sampleQty", 3, "sampleBatches", List.of(Map.of("code", "SP-000123")),
            "productId", 99, "productCode", "PP-000099", "specId", 20, "selectedPairs", List.of(Map.of("phoneModelId", 12, "colorId", 3)),
            "rangeRefs", List.of(Map.of("id", 4, "version", 1)), "pairings", List.of(Map.of("state", "available"))));
        var copied = catalog.copyFields(source);
        assertEquals(List.of(12L), copied.get("phoneModelIds"));
        assertEquals(source.get("rangeRefs"), copied.get("rangeRefs"));
        assertEquals(source.get("pairings"), copied.get("pairings"));
        for (String field : List.of("rowId", "confirmed", "confirmationSnapshot", "productId", "productCode", "specId", "sampleQty", "sampleBatches", "selectedPairs"))
            assertFalse(copied.containsKey(field), field);
        verifyNoInteractions(selections, materials);
        verify(store, never()).create(anyString(), anyString());
    }

    @Test void detailCannotCrossSupplierEvenWithARealSourceIdentity() {
        sourceRow("OTHER");
        assertThrows(BusinessException.class, () -> catalog.detail("selection", 9L, rowId, "XD"));
        verifyNoInteractions(phones, selections, materials);
    }

    @Test void excludedOrMalformedSourcesCannotBeViewed() {
        assertThrows(BusinessException.class, () -> catalog.detail("product", 9L, rowId, "XD"));
        assertThrows(BusinessException.class, () -> catalog.detail("selection", 9L, "not-a-row-id", "XD"));
        sourceRow("XD");
        when(jdbc.queryForList(contains("from plm_partner_selection_row where record_id=? and row_id=?::uuid"), eq(9L), eq(rowId)))
            .thenReturn(List.of(values("row_id", rowId, "row_version", 1L, "confirmed", false,
                "row_data", encode(store.json, values("supplierCode", "XD", "discarded", true)))));
        assertThrows(BusinessException.class, () -> catalog.detail("selection", 9L, rowId, "XD"));
    }

    @Test void unauthorizedAddStopsBeforeAnyRecordOrFileWrite() {
        doThrow(new BusinessException(40103, "仅国内工程可维护")).when(permissions).engineer();
        assertThrows(BusinessException.class, () -> catalog.addToSelection(item(9L, "XD")));
        verifyNoInteractions(jdbc, suppliers, selections, materials);
    }

    @Test void targetMustNotContainAnotherSupplier() {
        sourceRow("XD");
        doReturn(null).when(store).created("catalog_add", "add-once");
        doReturn(values("id", 10L, "version", 3L, "supplierCode", "OTHER")).when(store).load(10L, "selection", true);
        assertThrows(BusinessException.class, () -> catalog.addToSelection(item(9L, "XD")));
        verifyNoInteractions(selections, materials);
        verify(store, never()).remember(anyString(), anyString(), anyLong());
    }

    @Test void changedSourceVersionDoesNotSilentlyCopy() {
        sourceRow("XD");
        doReturn(null).when(store).created("catalog_add", "add-once");
        doReturn(values("id", 10L, "version", 3L, "supplierCode", "XD")).when(store).load(10L, "selection", true);
        var command = item(9L, "XD");
        maps(command.get("items")).get(0).put("rowVersion", 0L);
        assertThrows(BusinessException.class, () -> catalog.addToSelection(command));
        verifyNoInteractions(selections, materials);
        verify(store, never()).remember(anyString(), anyString(), anyLong());
    }

    @Test void supplierSourceCannotBeItsOwnTargetSelection() {
        sourceRow("XD");
        doReturn(null).when(store).created("catalog_add", "add-once");
        doReturn(values("id", 9L, "code", "SL-000009", "name", "熊大", "version", 4L, "supplierCode", "XD"))
            .when(store).load(9L, "selection", true);
        var command = item(9L, "XD"); command.put("targetSelectionId", 9L); command.put("targetVersion", 4L);
        maps(command.get("items")).get(0).put("rowId", rowId.toUpperCase(Locale.ROOT));
        when(jdbc.queryForList(startsWith("select row_id::text,row_version,sort_order,data::text,confirmed"), eq(9L), eq(rowId)))
            .thenReturn(List.of(values("row_id", rowId, "row_version", 1L, "sort_order", 1, "data", "{}", "confirmed", false)));
        doNothing().when(store).remember(anyString(), anyString(), anyLong());
        doNothing().when(store).event(anyLong(), anyString(), anyMap(), anyString());
        var error=assertThrows(BusinessException.class,()->catalog.addToSelection(command));
        assertTrue(error.getMessage().contains("不能作为目标选品"));
        verifyNoInteractions(selections, materials);
    }

    @Test void copyIntoAnotherSelectionUsesAuthorizedRowFilesAndTheExistingIncrementalWritePath() {
        sourceRow("XD");
        when(jdbc.queryForList(contains("from plm_partner_selection_row where record_id=? and row_id=?::uuid"), eq(9L), eq(rowId)))
            .thenReturn(List.of(values("row_id", rowId, "row_version", 1L, "confirmed", false,
                "row_data", encode(store.json, values("name", "熊大款", "supplierCode", "XD", "phoneModelIds", List.of(12L),
                    "refs", List.of(Map.of("id", 7L)), "sourceAttachmentId", 8L, "productId", 99L,
                    "sampleBatches", List.of(Map.of("code", "SP-000123")))))));
        doReturn(null).when(store).created("catalog_add", "add-once");
        doReturn(values("id", 10L, "code", "SL-000010", "name", "熊大新选品", "version", 3L, "supplierCode", "XD"))
            .when(store).load(10L, "selection", true);
        doAnswer(call -> maps(call.getArgument(1))).when(store).canonicalFiles(eq(9L), any());
        when(selections.patchCatalogRows(eq(10L), anyMap())).thenReturn(values("id", 10L, "code", "SL-000010", "name", "熊大新选品", "version", 4L));
        doNothing().when(store).remember(anyString(), anyString(), anyLong());
        doNothing().when(store).event(anyLong(), anyString(), anyMap(), anyString());
        var scope = mock(PartnerCatalogPhoneResolver.ResolutionScope.class);
        when(phones.newScope()).thenReturn(scope);
        when(scope.resolve(anyMap())).thenAnswer(call -> resolveForTest(call.getArgument(0)));
        var result = catalog.addToSelection(previewed(item(9L, "XD")));
        assertEquals(1L, number(result.get("addedCount")));
        assertEquals(4L, map(result.get("selection")).get("version"));
        var update = ArgumentCaptor.forClass(Map.class);
        verify(selections).patchCatalogRows(eq(10L), update.capture());
        assertEquals(3L, update.getValue().get("version"));
        var operation = maps(update.getValue().get("operations")).get(0);
        var changes = map(operation.get("changes"));
        assertEquals(List.of(7L), maps(changes.get("refs")).stream().map(file -> number(file.get("id"))).toList());
        assertEquals(8L, changes.get("sourceAttachmentId"));
        assertEquals(List.of(12L), PartnerSelectionService.scopeIds(changes.get("phoneModelIds")));
        assertEquals(true, changes.get("selected"));
        assertFalse(changes.containsKey("productId"));
        assertFalse(changes.containsKey("sampleBatches"));
        var files = ArgumentCaptor.forClass(Object.class);
        verify(materials, times(2)).inherit(eq(10L), files.capture());
        assertEquals(List.of(7L, 8L), files.getAllValues().stream().flatMap(value -> maps(value).stream()).map(file -> number(file.get("id"))).toList());
        verify(jdbc).update(contains("purpose='cooperation_cost'"), eq(10L), eq(8L));
        verify(phones,times(2)).newScope();
        var audit = ArgumentCaptor.forClass(Map.class);
        verify(store).event(eq(10L), eq("catalog_add"), audit.capture(), anyString());
        assertEquals(List.of(operation.get("rowId")), audit.getValue().get("createdRowIds"));
        var origin = maps(audit.getValue().get("origins")).get(0);
        assertEquals(9L, origin.get("recordId"));
        assertEquals(4L, origin.get("recordVersion"));
        assertEquals(1L, origin.get("rowVersion"));
        verify(store, never()).create(anyString(), anyString());
    }

    @Test void requestReplayReturnsTheAuditedResultWithoutAnyNewRowsOrFiles() {
        var command = item(9L, "XD");
        var result = values("selection", Map.of("id", 10L, "version", 4L), "rowIds", List.of(rowId), "addedCount", 1, "reusedCount", 0);
        String fingerprint = signature(store.json, Map.of("supplierCode", "XD", "items", maps(command.get("items")),
            "targetSelectionId", 10L, "selectionName", "","previewToken",""));
        doReturn(10L).when(store).created("catalog_add", "add-once");
        when(jdbc.queryForList(contains("action='catalog_add'"), eq(10L), anyString()))
            .thenReturn(List.of(Map.of("data", encode(store.json, Map.of("fingerprint", fingerprint, "result", result)))));
        var replayed = catalog.addToSelection(command);
        assertEquals(true, replayed.get("replayed"));
        assertEquals(List.of(rowId), replayed.get("rowIds"));
        maps(command.get("items")).get(0).put("rowId", rowId.toUpperCase(Locale.ROOT));
        assertEquals(List.of(rowId), catalog.addToSelection(command).get("rowIds"));
        verifyNoInteractions(selections, materials, phones);
        verify(store, never()).remember(anyString(), anyString(), anyLong());
        verify(store, never()).event(anyLong(), anyString(), anyMap(), anyString());
        command.put("selectionName", "不同目的的选品表");
        assertThrows(BusinessException.class, () -> catalog.addToSelection(command));
    }

    @Test void detailOnlyLoadsImagesLinkedToThisRowAndThisSourceRecord() {
        sourceRow("XD");
        when(jdbc.queryForList(contains("from plm_partner_selection_row where record_id=? and row_id=?::uuid"), eq(9L), eq(rowId)))
            .thenReturn(List.of(values("row_id", rowId, "row_version", 1L, "confirmed", false,
                "row_data", encode(store.json, values("supplierCode", "XD", "refs", List.of(Map.of("id", 7L)))))));
        when(jdbc.queryForList(contains("from plm_partner_file f join plm_attachment"), any(Object[].class)))
            .thenReturn(List.of(values("record_id", 9L, "id", 7L, "name", "本款图片.png", "version", "V1", "type", "image/png", "size", 100L, "restricted", false)));
        var result = catalog.detail("selection", 9L, rowId, "XD");
        assertEquals(4L, result.get("recordVersion"));
        assertEquals("本款图片.png", maps(result.get("fileDetails")).get(0).get("name"));
        var sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForList(sql.capture(), eq(new Object[] {9L, 7L}));
        assertTrue(sql.getValue().contains("f.record_id=? and f.attachment_id=?"));
        assertTrue(sql.getValue().contains("a.attachment_id=7"));
        assertTrue(sql.getValue().contains("a.deleted_flag=0"));
    }

    @Test void derivedSelectionCopyCannotBeUsedAsANewCatalogSource() {
        sourceRow("XD");
        when(jdbc.queryForObject(contains("(data->'createdRowIds') @>"), eq(Boolean.class), eq(9L), eq(rowId))).thenReturn(true);
        assertThrows(BusinessException.class, () -> catalog.detail("selection", 9L, rowId, "XD"));
        verifyNoInteractions(phones, materials, selections);
    }

    @Test void keywordCanFindTheExistingFourDigitPhoneCodeEvenWhenAbsentFromSupplierText() {
        var scope = mock(PartnerCatalogPhoneResolver.ResolutionScope.class);
        when(phones.newScope()).thenReturn(scope);
        when(scope.resolveAll(anyList())).thenAnswer(call -> ((List<Map<String, Object>>) call.getArgument(0)).stream().map(row -> {
            var result = new LinkedHashMap<>(row);
            result.put("phoneModelIds", List.of(12L));
            result.put("phones", List.of(Map.of("phoneModelId", 12L, "phoneModelCode", "0185", "displayName", "iPhone11")));
            return (Map<String, Object>) result;
        }).toList());
        when(jdbc.queryForList(startsWith("select c.*,c.data::text"), any(Object[].class)))
            .thenReturn(List.of(values("source_kind", "selection", "record_id", 9L, "source_code", "SL-9", "source_name", "熊大", "record_version", 4L,
                "row_id", rowId, "row_version", 1L, "supplier_code", "XD", "confirmed", false,
                "row_data", encode(store.json, Map.of("name", "熊抱款", "modelText", "iPhone11到18")))), List.of());
        var result = catalog.styles(Map.of("supplierCode", "XD", "keyword", "0185"));
        assertEquals(1L, result.get("totalElements"));
        assertEquals("0185", maps(maps(result.get("content")).get(0).get("phones")).get(0).get("phoneModelCode"));
        verify(phones).newScope();
        verify(phones, never()).resolveAll(anyList());
    }

    @Test void oldSupplierStyleLabelIsSearchableOnlyThroughThisSuppliersReservedCodes() {
        var scope=mock(PartnerCatalogPhoneResolver.ResolutionScope.class);when(phones.newScope()).thenReturn(scope);when(scope.resolveAll(anyList())).thenAnswer(call->call.getArgument(0));
        when(suppliers.shortCodeHistory("XD")).thenReturn(List.of(" OLD2 "));
        var source=values("source_kind","selection","record_id",9L,"source_code","SL-9","source_name","原资料","record_version",4L,"row_id",rowId,"row_version",1L,"supplier_code","XD","confirmed",false,"row_data",encode(store.json,Map.of("name","原款")));
        when(jdbc.queryForList(contains("select s.style_id as"),anyString(),anyLong(),anyString())).thenReturn(List.of(values("catalogStyleId",8L,"catalogStyleCode","CP-000008","supplierCode","XD","supplierShortCode","NEW2","fields","{}")));
        for(String keyword:List.of("OLD2-CP-000008","FOREIGN-CP-000008")){
            when(jdbc.queryForList(startsWith("select c.*,c.data::text"),any(Object[].class))).thenReturn(List.of(source),List.of());
            var result=catalog.styles(Map.of("supplierCode","XD","keyword",keyword));assertEquals(keyword.startsWith("OLD2")?1L:0L,result.get("totalElements"));
            if(keyword.startsWith("OLD2"))assertEquals("NEW2-CP-000008",maps(result.get("content")).get(0).get("catalogStyleLabel"));
        }
        verify(suppliers,times(2)).shortCodeHistory("XD");verify(suppliers,never()).shortCodeHistory("FOREIGN");
    }

    @Test void committedImportOriginalIsResolvedOnlyForTheExactRecordAndRow() {
        sourceRow("XD");
        String otherRow = UUID.randomUUID().toString();
        when(jdbc.queryForList(startsWith("select distinct c.record_id,c.row_id::text,j.source_attachment_id"), anyString()))
            .thenReturn(List.of(values("record_id", 9L, "row_id", rowId, "file_id", 8L),
                values("record_id", 9L, "row_id", otherRow, "file_id", 99L),
                values("record_id", 10L, "row_id", rowId, "file_id", 100L)));
        when(jdbc.queryForList(contains("from plm_partner_file f join plm_attachment"), any(Object[].class)))
            .thenReturn(List.of(values("record_id", 9L, "id", 8L, "name", "真实已提交原件.xlsx", "version", "V1", "type", "application/xlsx", "size", 100L, "restricted", true)));
        var result = catalog.detail("selection", 9L, rowId, "XD");
        assertTrue(maps(result.get("fileDetails")).isEmpty());
        assertEquals(List.of(8L), maps(result.get("sourceOriginals")).stream().map(file -> number(file.get("id"))).toList());
        var lookup = ArgumentCaptor.forClass(String.class);
        var chosen = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForList(lookup.capture(), chosen.capture());
        assertTrue(lookup.getValue().contains("jr.row_id=c.row_id"));
        assertTrue(lookup.getValue().contains("j.record_id=c.record_id"));
        assertTrue(lookup.getValue().contains("j.kind='import' and j.status='committed'"));
        assertTrue(lookup.getValue().contains("f.record_id=j.record_id and f.attachment_id=j.source_attachment_id"));
        assertTrue(lookup.getValue().contains("a.deleted_flag=0"));
        assertTrue(lookup.getValue().contains("a.attachment_id=7"));
        assertTrue(chosen.getValue().contains(rowId));
        assertFalse(chosen.getValue().contains(otherRow));
        var attachmentArgs = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForList(contains("from plm_partner_file f join plm_attachment"), attachmentArgs.capture());
        assertArrayEquals(new Object[] {9L, 8L}, attachmentArgs.getValue());
    }

    @Test void aCostlessReaderDoesNotLookupOrExposeImportOriginalProvenance() {
        sourceRow("XD");
        when(permissions.costs()).thenReturn(false);
        var result = catalog.detail("selection", 9L, rowId, "XD");
        assertFalse(result.containsKey("sourceOriginals"));
        verify(jdbc, never()).queryForList(startsWith("select distinct c.record_id,c.row_id::text,j.source_attachment_id"), anyString());
        verify(jdbc, never()).queryForList(contains("from plm_partner_file f join plm_attachment"), any(Object[].class));
    }

    @Test void inferredOriginalsAreInheritedAsRestrictedAndRecordedWithoutMutatingTheSource() {
        sourceRow("XD");
        when(jdbc.queryForList(startsWith("select distinct c.record_id,c.row_id::text,j.source_attachment_id"), anyString()))
            .thenReturn(List.of(values("record_id", 9L, "row_id", rowId, "file_id", 8L)));
        doReturn(null).when(store).created("catalog_add", "add-once");
        doReturn(values("id", 10L, "code", "SL-000010", "name", "熊大新选品", "version", 3L, "supplierCode", "XD"))
            .when(store).load(10L, "selection", true);
        doAnswer(call -> maps(call.getArgument(1))).when(store).canonicalFiles(eq(9L), any());
        when(selections.patchCatalogRows(eq(10L), anyMap())).thenReturn(values("id", 10L, "code", "SL-000010", "name", "熊大新选品", "version", 4L));
        doNothing().when(store).remember(anyString(), anyString(), anyLong());
        doNothing().when(store).event(anyLong(), anyString(), anyMap(), anyString());
        var scope = mock(PartnerCatalogPhoneResolver.ResolutionScope.class);
        when(phones.newScope()).thenReturn(scope);
        when(scope.resolve(anyMap())).thenAnswer(call -> resolveForTest(call.getArgument(0)));
        catalog.addToSelection(previewed(item(9L, "XD")));
        var update = ArgumentCaptor.forClass(Map.class);
        verify(selections).patchCatalogRows(eq(10L), update.capture());
        assertEquals(8L, map(maps(update.getValue().get("operations")).get(0).get("changes")).get("sourceAttachmentId"));
        verify(materials).inherit(10L, List.of(Map.of("id", 8L)));
        verify(jdbc).update(contains("purpose='cooperation_cost'"), eq(10L), eq(8L));
        var audit = ArgumentCaptor.forClass(Map.class);
        verify(store).event(eq(10L), eq("catalog_add"), audit.capture(), anyString());
        assertEquals(List.of(8L), maps(audit.getValue().get("origins")).get(0).get("sourceOriginalIds"));
        verify(store, never()).save(eq(9L), anyMap());
    }

    @Test void differentlyCasedSourceUuidReusesTheSameTargetRowAcrossRequests() {
        sourceRow("XD");
        doReturn(null).when(store).created(eq("catalog_add"), anyString());
        var version=new java.util.concurrent.atomic.AtomicLong(3L);
        doAnswer(call->values("id",10L,"code","SL-000010","name","熊大新选品","version",version.get(),"supplierCode","XD"))
            .when(store).load(eq(10L),eq("selection"),anyBoolean());
        doAnswer(call -> maps(call.getArgument(1))).when(store).canonicalFiles(eq(9L), any());
        doNothing().when(store).remember(anyString(), anyString(), anyLong());
        doNothing().when(store).event(anyLong(), anyString(), anyMap(), anyString());
        var scope = mock(PartnerCatalogPhoneResolver.ResolutionScope.class);
        when(phones.newScope()).thenReturn(scope);
        when(scope.resolve(anyMap())).thenAnswer(call -> resolveForTest(call.getArgument(0)));
        var savedRows = new HashMap<String, Map<String, Object>>();
        when(jdbc.queryForList(startsWith("select row_id::text,row_version,data::text,confirmed"), eq(10L),eq("selection"),eq("9"),eq(rowId)))
            .thenAnswer(call -> new ArrayList<>(savedRows.values()));
        when(selections.patchCatalogRows(eq(10L), anyMap())).thenAnswer(call -> {
            for (var operation : maps(map(call.getArgument(1)).get("operations"))) {
                String id = text(operation.get("rowId"));
                savedRows.put(id, values("row_id", id, "row_version", 1L, "data", encode(store.json,operation.get("changes")), "confirmed", false));
            }
            return values("id", 10L, "code", "SL-000010", "name", "熊大新选品", "version", version.incrementAndGet());
        });
        var first = item(9L, "XD");
        maps(first.get("items")).get(0).put("rowId", rowId.toUpperCase(Locale.ROOT));
        var created = catalog.addToSelection(previewed(first));
        var second = item(9L, "XD"); second.put("requestId", "same-origin-lowercase"); second.put("targetVersion", 4L);
        var reused = catalog.addToSelection(previewed(second));
        assertEquals(1L, number(created.get("addedCount")));
        assertEquals(0L, number(reused.get("addedCount")));
        assertEquals(1L, reused.get("reusedCount"));
        assertEquals(created.get("rowIds"), reused.get("rowIds"));
        assertEquals(1, savedRows.size());
        verify(selections).patchCatalogRows(eq(10L), anyMap());
        verify(jdbc, times(4)).queryForList(contains("from plm_partner_selection_row where record_id=? and row_id=?::uuid"), eq(9L), eq(rowId));
    }

    @Test void mixedUuidCasesInOneBatchAreRejectedAsOneDuplicateSource() {
        sourceRow("XD");
        doReturn(null).when(store).created("catalog_add", "add-once");
        doReturn(values("id", 10L, "version", 3L, "supplierCode", "XD")).when(store).load(10L, "selection", true);
        var command = item(9L, "XD");
        var lower = maps(command.get("items")).get(0);
        var upper = new LinkedHashMap<>(lower); upper.put("rowId", rowId.toUpperCase(Locale.ROOT));
        command.put("items", List.of(lower, upper));
        var error = assertThrows(BusinessException.class, () -> catalog.addToSelection(command));
        assertTrue(error.getMessage().contains("重复款式"));
        verifyNoInteractions(selections, materials, phones);
        verify(store, never()).remember(anyString(), anyString(), anyLong());
        verify(store, never()).event(anyLong(), anyString(), anyMap(), anyString());
    }
}
