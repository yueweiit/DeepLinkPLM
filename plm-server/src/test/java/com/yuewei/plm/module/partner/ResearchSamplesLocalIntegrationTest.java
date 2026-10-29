package com.yuewei.plm.module.partner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuewei.plm.common.security.CurrentUser;
import com.yuewei.plm.module.moldreference.PhoneModelReferenceService;
import com.yuewei.plm.module.operationlog.service.OperationLogService;
import com.yuewei.plm.module.pilot.PilotAccessService;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import static com.yuewei.plm.module.partner.PartnerValues.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Full migrated local clone, one rollback transaction per scenario. No real OA or stock posting. */
@EnabledIfEnvironmentVariable(named="PLM_SAMPLE_DOMAINS_TEST_URL",matches="jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/plm_sample_domains_qa")
class ResearchSamplesLocalIntegrationTest {
    private JdbcTemplate jdbc;private DataSourceTransactionManager manager;private TransactionStatus tx;
    private PartnerStore store;private PartnerSampleService samples;private ResearchSampleSources sources;private PartnerQueryService query;
    private PilotAccessService access;private long phone,model,research,otherModel,set,otherSet;private String prefix;
    private Map<String,Object> values(Object... pairs){var d=new LinkedHashMap<String,Object>();for(int i=0;i<pairs.length;i+=2)d.put(text(pairs[i]),pairs[i+1]);return d;}
    @BeforeEach void setup(){
        var ds=new DriverManagerDataSource(System.getenv("PLM_SAMPLE_DOMAINS_TEST_URL")+"?currentSchema=plm","postgres","");jdbc=new JdbcTemplate(ds);manager=new DataSourceTransactionManager(ds);tx=manager.getTransaction(new DefaultTransactionDefinition());
        prefix="QA样品域-"+UUID.randomUUID().toString().substring(0,8);long base=9_200_000_000L+new Random().nextInt(100_000_000);research=base;model=base+1;otherModel=base+2;
        phone=jdbc.queryForObject("select id from plm_phone_model_member where status='enabled' order by id limit 1",Long.class);
        for(long id:List.of(research,model,otherModel))jdbc.update("insert into plm_product(product_id,product_code,product_name,product_type,version_no,created_by,updated_by,project_kind,project_registered_at,development_mode,phone_model_member_id,parent_product_id) values(?,?,?,?,?,'qa','qa',?,now(),?,?,?)",id,"QA-"+id,prefix+id,id==research?"product_line":"model_variant","V1",id==research?"product_research":"model_extension",id==research?"launch_v1":null,phone,id==research?null:research);
        long actor=jdbc.queryForObject("select user_id from sys_user where deleted_flag=0 order by user_id limit 1",Long.class);
        access=mock(PilotAccessService.class);when(access.actor()).thenReturn(new CurrentUser(actor,"qa-samples","QA工程",false));when(access.actorId()).thenReturn(actor);when(access.hasPermission(anyString())).thenReturn(true);when(access.visibleSqlClause(anyString(),anyString())).thenReturn("true");
        store=new PartnerStore(jdbc,new ObjectMapper(),access,mock(OperationLogService.class));var phones=mock(PhoneModelReferenceService.class);
        var member=jdbc.queryForMap("select id as \"phoneModelId\",model_name as \"modelName\" from plm_phone_model_member where id=?",phone);when(phones.requireEnabledModels(List.of(phone))).thenReturn(Map.of(phone,member));
        sources=new ResearchSampleSources(store,phones);samples=new PartnerSampleService(store,mock(PartnerProductService.class),mock(PartnerMaterialService.class));ReflectionTestUtils.setField(samples,"researchSources",sources);
        query=new PartnerQueryService(store,mock(SupplierProfileService.class),mock(PartnerCustomerService.class));
        long item=jdbc.queryForObject("insert into plm_collaboration_item(style_name,compatible_models,mold_name,created_by,updated_by) values(?,'',?,'qa','qa') returning item_id",Long.class,prefix,prefix);
        jdbc.update("insert into plm_collaboration_item_project(item_id,project_id,linked_by) values(?,?,'qa'),(?,?,'qa')",item,model,item,otherModel);
        set=createSet(item,1);otherSet=createSet(item,2);
        jdbc.update("insert into plm_collaboration_phone_member(item_id,phone_model_id,mold_set_ids,pc_confirmed,tpu_confirmed,evidence,created_by,updated_by) values(?,?,array[?]::bigint[],false,false,'QA实际范围','qa','qa')",item,phone,set);
        jdbc.update("insert into plm_collaboration_phone_set_compatibility(item_id,phone_model_id,mold_set_id,created_by,updated_by) values(?,?,?,'qa','qa')",item,phone,set);
        // Same master, deliberately no exact project/phone relation for the second set.
    }
    private long createSet(long item,int no){long id=jdbc.queryForObject("insert into plm_collaboration_mold_set(item_id,set_no,set_name,created_by,updated_by) values(?,?,?,'qa','qa') returning mold_set_id",Long.class,item,no,prefix+no);for(String part:List.of("PC","TPU"))jdbc.update("insert into plm_collaboration_mold_part(mold_set_id,part_type,status) values(?,?,'pending')",id,part);return id;}
    @AfterEach void rollback(){if(tx!=null)manager.rollback(tx);}
    private Map<String,Object> receipt(String type,String kind){return values("sourceType",type,"sampleKind",kind,"ownerName","QA保管","date","2026-10-22","location","QA样品柜","qty",3,"requestId",UUID.randomUUID().toString());}
    private Map<String,Object> device(){var d=receipt("device","phone");d.putAll(values("phoneModelId",phone,"provenance",prefix+"采购凭据","assetNumber","原物料-0185"));return d;}
    private Map<String,Object> trial(){var d=receipt("trial","pc");d.putAll(values("projectId",model,"moldSetId",set,"trialBatch","T0"));return d;}
    private Map<String,Object> modelReceipt(){var d=receipt("model","");d.remove("sampleKind");d.putAll(values("modelProductId",research,"phoneModelId",phone,"provenance",prefix+"产品实收凭据"));return d;}
    private Map<String,String> scope(String domain){return Map.of("domain",domain,"keyword",prefix,"size","50");}
    @Test void realDevicesKeepIdentityAndRepeatedReceiptDoesNotAddStock(){
        var input=device();var first=samples.create(input);var repeated=samples.create(input);assertEquals(first.get("id"),repeated.get("id"));assertEquals(3L,number(map(repeated.get("balances")).get("onhand")));assertEquals("model",first.get("sampleDomain"));assertEquals("原物料-0185",first.get("assetNumber"));assertEquals(1,maps(first.get("events")).size());
        input.put("requestId",UUID.randomUUID().toString());input.put("sampleKind","dummy");var dummy=samples.create(input);assertEquals("dummy",dummy.get("sampleKind"));assertNotEquals(first.get("id"),dummy.get("id"));
    }
    @Test void modelSampleRequiresRealProductPhoneAndSourceWithoutInventingACategoryOrProject(){
        long products=jdbc.queryForObject("select count(*) from plm_product",Long.class),sets=jdbc.queryForObject("select count(*) from plm_collaboration_mold_set",Long.class);
        var input=modelReceipt();var saved=samples.create(input);var repeated=samples.create(input);
        assertEquals(saved.get("id"),repeated.get("id"));assertEquals("model",saved.get("sampleDomain"));assertEquals("model",saved.get("sourceType"));assertFalse(saved.containsKey("sampleKind"));assertFalse(saved.containsKey("projectId"));assertFalse(saved.containsKey("moldSetId"));
        assertEquals(research,number(saved.get("modelProductId")));assertEquals(phone,number(saved.get("phoneModelId")));assertEquals(research,number(map(map(saved.get("sourceSnapshot")).get("product")).get("id")));
        assertEquals(3L,number(map(saved.get("balances")).get("onhand")));assertEquals(1,maps(saved.get("events")).size());assertEquals(products,jdbc.queryForObject("select count(*) from plm_product",Long.class));assertEquals(sets,jdbc.queryForObject("select count(*) from plm_collaboration_mold_set",Long.class));
        for(String missing:List.of("modelProductId","phoneModelId","provenance")){var bad=modelReceipt();bad.remove(missing);assertThrows(RuntimeException.class,()->samples.create(bad));}
        var variant=modelReceipt();variant.put("modelProductId",model);assertThrows(RuntimeException.class,()->samples.create(variant));
    }
    @Test void modelSourceOptionsReturnRealProductAndDeepLinkProjectIdentity(){
        assertTrue(maps(sources.options("model",null).get("projects")).isEmpty());assertTrue(maps(sources.options("model",null,research,null).get("projects")).isEmpty());
        var options=sources.options("model",model);var p=map(options.get("project"));assertEquals(research,number(p.get("modelProductId")));assertTrue(maps(p.get("phoneModels")).stream().anyMatch(row->number(row.get("phoneModelId"))==phone));
        assertTrue(maps(options.get("products")).stream().anyMatch(row->number(row.get("id"))==research));assertFalse(maps(options.get("products")).stream().anyMatch(row->number(row.get("id"))==model));
        var filtered=sources.options("model",model,research,phone);assertTrue(maps(filtered.get("projects")).stream().anyMatch(row->number(row.get("id"))==model));assertEquals(List.of(set),maps(filtered.get("sets")).stream().map(row->number(row.get("id"))).toList());
    }
    @Test void modelSampleMayLinkAnExactProjectAndSetWithoutRecountingMoldsOrProduction(){
        var input=modelReceipt();input.putAll(values("projectId",model,"moldSetId",set,"trialBatch","实际T1"));var saved=samples.create(input);
        assertEquals(model,number(saved.get("projectId")));assertEquals(set,number(saved.get("moldSetId")));assertFalse(saved.containsKey("sampleKind"));assertEquals(2,maps(map(saved.get("sourceSnapshot")).get("parts")).size());
        assertNull(jdbc.queryForObject("select pc_total from plm_collaboration_item where item_id=?",Long.class,number(saved.get("itemId"))));assertEquals("pending",jdbc.queryForObject("select status from plm_collaboration_mold_part where mold_set_id=? and part_type='PC'",String.class,set));
        var projectOnly=modelReceipt();projectOnly.put("projectId",model);assertEquals(model,number(samples.create(projectOnly).get("projectId")));
        var noBatch=modelReceipt();noBatch.putAll(values("projectId",model,"moldSetId",set));assertThrows(RuntimeException.class,()->samples.create(noBatch));
        var noProject=modelReceipt();noProject.put("moldSetId",set);assertThrows(RuntimeException.class,()->samples.create(noProject));
        var masterOnly=modelReceipt();masterOnly.putAll(values("projectId",model,"moldSetId",otherSet,"trialBatch","T0"));assertThrows(RuntimeException.class,()->samples.create(masterOnly));
    }
    @Test void modelReceiptRejectsCrossProductAndCrossPhoneProjectsAndSets(){
        long otherProduct=research+100;
        jdbc.update("insert into plm_product(product_id,product_code,product_name,product_type,version_no,created_by,updated_by) values(?,?,?,'product_line','V1','qa','qa')",otherProduct,"QA-"+otherProduct,prefix+"另一款式");
        var input=modelReceipt();input.put("projectId",model);jdbc.update("update plm_product set parent_product_id=? where product_id=?",otherProduct,model);assertThrows(RuntimeException.class,()->samples.create(input));assertThrows(RuntimeException.class,()->sources.options("model",model,research,phone));
        jdbc.update("update plm_product set parent_product_id=?,phone_model_member_id=null where product_id=?",research,model);assertThrows(RuntimeException.class,()->samples.create(input));
        jdbc.update("update plm_product set phone_model_member_id=?,phone_model_code='QA-CODE-CONFLICT' where product_id=?",phone,model);assertThrows(RuntimeException.class,()->samples.create(input));
        jdbc.update("update plm_product set phone_model_code=null where product_id=?",model);jdbc.update("update plm_collaboration_item set product_line_id=? where item_id=(select item_id from plm_collaboration_mold_set where mold_set_id=?)",otherProduct,set);
        input.putAll(values("moldSetId",set,"trialBatch","T1"));assertThrows(RuntimeException.class,()->samples.create(input));assertTrue(maps(sources.options("model",model,research,phone).get("sets")).isEmpty());
    }
    @Test void productModelReceiptFilteringAndBorrowReturnUseTheSameBatch(){
        var input=modelReceipt();var row=samples.create(input);long id=number(row.get("id"));var filters=Map.of("domain","model","modelProductId",Long.toString(research));
        assertEquals(1L,number(query.page("sample",filters).get("totalElements")));assertEquals(1L,number(query.movements(filters).get("totalElements")));assertEquals(0L,number(query.page("sample",Map.of("domain","model","modelProductId",Long.toString(research),"sampleKind","unspecified")).get("totalElements")));
        var loan=samples.action(id,"loan",values("version",row.get("version"),"qty",1,"location","QA样品柜","person","QA工程","date","2026-10-22","due","2026-10-25","requestId",UUID.randomUUID().toString()));
        var returned=samples.action(id,"return",values("version",loan.get("version"),"qty",1,"location","QA样品柜","date","2026-10-22","loanId",maps(map(loan.get("balances")).get("loans")).get(0).get("id"),"requestId",UUID.randomUUID().toString()));
        assertEquals(id,number(returned.get("id")));assertEquals(3L,number(map(returned.get("balances")).get("onhand")));assertFalse(returned.containsKey("sampleKind"));assertEquals(3L,number(query.movements(filters).get("totalElements")));
    }
    @Test void pendingSourceCorrectionToProductModelKeepsOneBatchWithoutStaleCategoryOrProject(){
        var opening=receipt("self","prototype");opening.putAll(values("pending",true,"name",prefix+"待核对","projectId",research,"provenance","旧来源待补"));var pending=samples.create(opening);
        var corrected=modelReceipt();corrected.putAll(values("version",pending.get("version"),"reason","已核实产品和手机来源"));var saved=samples.action(number(pending.get("id")),"source_resolve",corrected);
        assertEquals(pending.get("id"),saved.get("id"));assertEquals("model",saved.get("sampleDomain"));assertFalse(saved.containsKey("sampleKind"));assertFalse(saved.containsKey("projectId"));assertFalse(saved.containsKey("roundId"));assertEquals(3L,number(map(saved.get("balances")).get("onhand")));assertEquals(2,maps(saved.get("events")).size());
    }
    @Test void modelProductPermissionsAndOptionalProjectPermissionsStaySeparate(){
        doThrow(new IllegalStateException("产品无权查看")).when(access).requireProduct(research);assertThrows(RuntimeException.class,()->samples.create(modelReceipt()));assertThrows(RuntimeException.class,()->sources.options("model",null,research,phone));assertThrows(RuntimeException.class,()->query.page("sample",Map.of("modelProductId",Long.toString(research))));
        doNothing().when(access).requireProduct(research);when(access.hasPermission("project:view")).thenReturn(false);when(access.visibleSqlClause("product","p.product_id")).thenReturn("p.product_id<>"+research);
        var choices=sources.options("model",null);assertTrue(maps(choices.get("projects")).isEmpty());assertFalse((boolean)choices.get("canReadProjects"));assertFalse(maps(choices.get("products")).stream().anyMatch(p->number(p.get("id"))==research));
    }
    @Test void trialPartsUseExactSetAndNeverUpdateMoldOrProductionFacts(){
        var row=samples.create(trial());assertEquals("model",row.get("sampleDomain"));assertEquals(set,number(row.get("moldSetId")));assertEquals("pc",row.get("sampleKind"));
        assertEquals("pending",jdbc.queryForObject("select status from plm_collaboration_mold_part where mold_set_id=? and part_type='PC'",String.class,set));
        assertNull(jdbc.queryForObject("select pc_total from plm_collaboration_item where item_id=?",Long.class,number(row.get("itemId"))));
        var bad=trial();bad.put("moldSetId",otherSet);assertThrows(RuntimeException.class,()->samples.create(bad));
    }
    @Test void componentTypeCannotBeForgedOrOmitted(){
        var bad=device();bad.put("sampleKind","pc");assertThrows(RuntimeException.class,()->samples.create(bad));
        var missing=trial();missing.remove("trialBatch");assertThrows(RuntimeException.class,()->samples.create(missing));
        var noPart=trial();jdbc.update("delete from plm_collaboration_mold_part where mold_set_id=? and part_type='TPU'",set);noPart.put("sampleKind","tpu");assertThrows(RuntimeException.class,()->samples.create(noPart));
    }
    @Test void tpuAndAssembledSamplesEachRecordOnlyTheirActualReceipt(){
        long before=jdbc.queryForObject("select count(*) from plm_partner_record where kind='sample'",Long.class);
        for(String kind:List.of("tpu","case")){var input=trial();input.put("sampleKind",kind);input.put("qty",1);var saved=samples.create(input);assertEquals(kind,saved.get("sampleKind"));assertEquals(1L,number(map(saved.get("balances")).get("onhand")));}
        assertEquals(before+2,jdbc.queryForObject("select count(*) from plm_partner_record where kind='sample'",Long.class));
    }
    @Test void plannedReceiptDatesCannotBecomeActualDeviceInventory(){
        var input=device();input.put("date","2099-10-22");assertThrows(RuntimeException.class,()->samples.create(input));
    }
    @Test void borrowingForAnotherProjectReusesOneBatchAndReturnRetainsItsUsage(){
        var original=samples.create(device());long id=number(original.get("id"));long file=jdbc.queryForObject("select attachment_id from plm_attachment where deleted_flag=0 order by attachment_id limit 1",Long.class);jdbc.update("insert into plm_partner_file(record_id,attachment_id) values(?,?)",id,file);
        var loan=samples.action(id,"loan",values("version",original.get("version"),"qty",1,"location","QA样品柜","person","QA研发","date","2026-10-22","due","2026-10-25","projectId",research,"projectName","不能采用客户端假名称","refs",List.of(Map.of("id",file,"type","file")),"requestId",UUID.randomUUID().toString()));
        var borrowed=maps(map(loan.get("balances")).get("loans")).get(0);assertEquals(research,number(borrowed.get("projectId")));assertTrue(text(borrowed.get("projectName")).startsWith(prefix));assertEquals(file,number(maps(borrowed.get("refs")).get(0).get("id")));assertNotNull(maps(borrowed.get("refs")).get(0).get("version"));
        var result=query.page("sample",Map.of("projectId",Long.toString(research)));assertEquals(1L,number(result.get("totalElements")));assertEquals(id,number(maps(result.get("content")).get(0).get("id")));
        var returned=samples.action(id,"return",values("version",loan.get("version"),"qty",1,"location","QA样品柜","loanId",borrowed.get("id"),"date","2026-10-22","requestId",UUID.randomUUID().toString()));
        assertEquals(3L,number(map(returned.get("balances")).get("onhand")));assertEquals(0L,number(map(returned.get("balances")).get("borrowed")));assertEquals(research,number(map(maps(returned.get("events")).get(2).get("data")).get("projectId")));assertEquals(1L,number(query.page("sample",Map.of("projectId",Long.toString(research))).get("totalElements")));
    }
    private long round(boolean received){return jdbc.queryForObject("insert into plm_product_development_round(product_id,round_no,kind,title,result,status,record_date,details_json,created_by,updated_by) values(?,?,'sample',?,'pending',?,?::date,?::jsonb,'qa','qa') returning round_id",Long.class,research,received?1:2,prefix+"S1","draft",received?"2026-10-22":null,encode(store.json,Map.of("makerName","QA制作方","phoneModelIds",List.of(phone))));}
    @Test void researchReceiptUsesActualRoundAndKeepsValidationPending(){
        long id=round(true);var d=receipt("self","prototype");d.putAll(values("projectId",research,"roundId",id));var saved=samples.create(d);assertEquals("research",saved.get("sampleDomain"));assertEquals(id,number(saved.get("roundId")));assertEquals("pending",jdbc.queryForObject("select result from plm_product_development_round where round_id=?",String.class,id));
        d.put("requestId",UUID.randomUUID().toString());d.put("roundId",round(false));assertThrows(RuntimeException.class,()->samples.create(d));
    }
    @Test void laterValidationRefreshesProgressWithoutRewritingReceiptSnapshotOrQuantity(){
        long round=round(true);var input=receipt("self","prototype");input.putAll(values("projectId",research,"roundId",round));var received=samples.create(input);
        assertEquals("draft",map(received.get("researchProgress")).get("status"));assertEquals("pending",map(received.get("researchProgress")).get("result"));
        jdbc.update("update plm_product_development_round set status='confirmed',result='passed',details_json=details_json||?::jsonb where round_id=?",encode(store.json,Map.of("designSummary","QA-D1","actualResult","QA实测通过")),round);
        var current=samples.detail(number(received.get("id")));assertEquals("passed",map(current.get("researchProgress")).get("result"));assertEquals("pending",map(map(current.get("sourceSnapshot")).get("round")).get("result"));assertEquals(3L,number(map(current.get("balances")).get("onhand")));assertEquals(1,maps(current.get("events")).size());
    }
    @Test void aDateAloneDoesNotTurnAnIncompleteResearchPlanIntoActualSampleSource(){
        long round=round(true);var input=receipt("self","prototype");input.putAll(values("projectId",research,"roundId",round));
        jdbc.update("update plm_product_development_round set details_json=?::jsonb where round_id=?",encode(store.json,Map.of("phoneModelIds",List.of(phone))),round);assertTrue(maps(sources.options("research",research).get("rounds")).isEmpty());assertThrows(RuntimeException.class,()->samples.create(input));
        jdbc.update("update plm_product_development_round set details_json=?::jsonb where round_id=?",encode(store.json,Map.of("makerName","QA制作方")),round);assertThrows(RuntimeException.class,()->samples.create(input));
    }
    @Test void voidedRoundCannotBeSelectedOrRegisteredAgain(){
        long id=round(true);jdbc.update("update plm_product_development_round set status='confirmed',result='failed',issues='QA实测问题' where round_id=?",id);jdbc.update("insert into plm_product_development_state(product_id,data) values(?,?::jsonb)",research,encode(store.json,Map.of("voidedRounds",Map.of(Long.toString(id),"方案作废"))));
        assertTrue(maps(sources.options("research",research).get("rounds")).isEmpty());var d=receipt("self","prototype");d.putAll(values("projectId",research,"roundId",id));assertThrows(RuntimeException.class,()->samples.create(d));
    }
    @Test void domainAndProjectFiltersApplyToBothStockAndMovementPages(){
        var a=samples.create(trial());var b=trial();b.put("projectId",otherModel);samples.create(b);
        long id=round(true);var c=receipt("self","prototype");c.putAll(values("projectId",research,"roundId",id));samples.create(c);
        var q=new LinkedHashMap<>(scope("model"));q.put("projectId",Long.toString(model));assertEquals(1L,number(query.page("sample",q).get("totalElements")));assertEquals(1L,number(query.movements(q).get("totalElements")));
        var d=maps(query.page("sample",q).get("content")).get(0);assertEquals(number(a.get("id")),number(d.get("id")));assertEquals("model",d.get("sampleDomain"));
        assertEquals(1L,number(query.page("sample",scope("research")).get("totalElements")));assertEquals(0L,number(query.movements(scope("partner")).get("totalElements")));
    }
    @Test void objectVisibilityAndDeniedProjectNeverLeakCounts(){
        var row=samples.create(trial());when(access.visibleSqlClause("partner","r.record_id")).thenReturn("r.record_id=-1");assertEquals(0L,number(query.page("sample",scope("model")).get("totalElements")));assertEquals(0L,number(query.movements(scope("model")).get("totalElements")));
        doThrow(new IllegalStateException("项目无权查看")).when(access).requireProduct(research);assertThrows(RuntimeException.class,()->sources.options("research",research));assertThrows(RuntimeException.class,()->query.movements(Map.of("projectId",Long.toString(research))));assertNotNull(row);
        doNothing().when(access).requireProduct(research);when(access.hasPermission("schedule:read")).thenReturn(false);var missingPermission=sources.options("model",model);assertEquals(false,missingPermission.get("canReadSets"));assertTrue(maps(missingPermission.get("sets")).isEmpty());
    }
    @Test void loanReturnAndSourceCorrectionKeepOneBatchWithoutRecount(){
        var input=device();var row=samples.create(input);long id=number(row.get("id"));var action=values("version",row.get("version"),"qty",2,"location","QA样品柜","person","QA测试员","date","2026-10-22","due","2026-10-25","requestId",UUID.randomUUID().toString());
        var loan=samples.action(id,"loan",action);assertEquals(1L,number(map(loan.get("balances")).get("onhand")));assertEquals(2L,number(map(loan.get("balances")).get("borrowed")));
        long loanId=number(maps(map(loan.get("balances")).get("loans")).get(0).get("id"));var back=values("version",loan.get("version"),"qty",1,"location","QA样品柜","loanId",loanId,"date","2026-10-22","requestId",UUID.randomUUID().toString());var returned=samples.action(id,"return",back);assertEquals(2L,number(map(returned.get("balances")).get("onhand")));assertEquals(1L,number(map(returned.get("balances")).get("borrowed")));assertEquals(number(returned.get("id")),id);
        var opening=receipt("self","prototype");opening.putAll(values("pending",true,"name",prefix+"期初","provenance","历史来源待补"));var pending=samples.create(opening);var fix=device();fix.putAll(values("version",pending.get("version"),"reason","核实真实来源"));var resolved=samples.action(number(pending.get("id")),"source_resolve",fix);assertEquals(pending.get("code"),resolved.get("code"));assertEquals(3L,number(map(resolved.get("balances")).get("onhand")));assertEquals("model",resolved.get("sampleDomain"));
    }
    @Test void clientDomainCannotReclassifyAndInvalidFiltersAreRejected(){
        var d=trial();d.put("sampleDomain","partner");var row=samples.create(d);assertEquals("model",row.get("sampleDomain"));assertThrows(RuntimeException.class,()->query.page("sample",Map.of("domain","made-up")));assertThrows(RuntimeException.class,()->sources.options("model",research));
        jdbc.update("update plm_product set project_kind=null,project_registered_at=null,development_mode=null where product_id=?",model);assertEquals("model",samples.detail(number(row.get("id"))).get("sampleDomain"));
    }
}
