package com.yuewei.plm.module.collaboration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuewei.plm.common.security.CurrentUser;
import com.yuewei.plm.common.security.CurrentUserContext;
import com.yuewei.plm.module.operationlog.service.OperationLogService;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.yuewei.plm.module.collaboration.CollaborationTypes.*;

/** Every schema/business/account fixture is local-only and rolled back. */
@EnabledIfEnvironmentVariable(named="PLM_COLLAB_TEST_DB_URL",matches="jdbc:postgresql://127\\.0\\.0\\.1:15432/plm_data_management\\?currentSchema=plm")
class MoldMasterLocalIntegrationTest {
    JdbcTemplate jdbc;DataSourceTransactionManager tx;TransactionStatus scope;CollaborationService service;CurrentUser admin;OperationLogService audit;
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    @BeforeEach void begin() throws Exception {
        var ds=new DriverManagerDataSource(System.getenv("PLM_COLLAB_TEST_DB_URL"),"postgres",System.getenv("PLM_COLLAB_TEST_DB_PASSWORD"));
        jdbc=new JdbcTemplate(ds);tx=new DataSourceTransactionManager(ds);scope=tx.getTransaction(new DefaultTransactionDefinition());MoldMasterTestSchema.apply(jdbc);
        audit=mock(OperationLogService.class);service=new CollaborationService(jdbc,json,new CollaborationRepository(jdbc,json),tx,audit);
        var user=jdbc.queryForList("select user_id,username,display_name from sys_user where all_permissions and deleted_flag=0 and status='active' order by user_id limit 1").get(0);
        admin=new CurrentUser(((Number)user.get("user_id")).longValue(),user.get("username").toString(),user.get("display_name").toString(),true);CurrentUserContext.set(admin);
    }
    @AfterEach void rollback() {if(scope!=null&&!scope.isCompleted())tx.rollback(scope);CurrentUserContext.clear();}
    long archive() {return ((Number)service.createMaster(new CreateMaster(null,"款式-"+UUID.randomUUID(),"原模具名称",null,null,"原始组文字 / 不自动拆分"),null).get("itemId")).longValue();}
    long set(long item) {return ((Number)service.importMoldSet(item,new CreateMoldSet(null,null,null),null).get("moldSetId")).longValue();}
    long brand() {return jdbc.queryForObject("insert into plm_phone_brand(name,created_by,updated_by) values(?,'it','it') returning id",Long.class,"Apple-"+UUID.randomUUID());}
    long member(long brand,String name,String code) {
        Long codeId=null;
        if(code!=null) {var ids=jdbc.queryForList("select code_item_id from plm_code_item where code_type='phone_model' and code_value=? and deleted_flag=0",Long.class,code);codeId=ids.isEmpty()?jdbc.queryForObject("insert into plm_code_item(code_type,code_value,code_name,status) values('phone_model',?,?,'enabled') returning code_item_id",Long.class,code,"注册码-"+UUID.randomUUID()):ids.get(0);}
        return jdbc.queryForObject("insert into plm_phone_model_member(brand_id,model_name,code_item_id,created_by,updated_by) values(?,?,?,'it','it') returning id",Long.class,brand,name,codeId);
    }
    RowPatch patch(String entity,long id,Map<String,Object> after,String reason) {
        var before=entity.equals("item")?service.item(id):service.mold(id);
        return new RowPatch(entity,id,after.entrySet().stream().map(e->new CellChange(e.getKey(),json.valueToTree(before.get(e.getKey())),json.valueToTree(e.getValue()),reason)).toList());
    }
    RowResult save(RowPatch patch) {return service.save(new BatchSave(List.of(patch)),null).get(0);}
    Map<String,Object> relation(long phone,List<Long> sets,String evidence) {return Map.of("phoneModelId",phone,"setIds",sets,"pcConfirmed",true,"tpuConfirmed",true,"evidence",evidence);}
    RowPatch members(long item,List<Long> ids,List<Long> sets) {return patch("item",item,Map.of("compatiblePhones",ids.stream().map(id->relation(id,sets,"工程按实物尺寸确认 PC、TPU 可共模")).toList()),"人工确认适配");}
    @SuppressWarnings("unchecked") Map<String,Object> summary(long item) {return (Map<String,Object>)service.master(item,1,50).get("summary");}
    void quote(long set,String amount,String currency,boolean confirmed) {assertThat(save(patch("mold_set",set,Map.of("quoteAmount",amount,"quoteCurrency",currency,"quoteBasis","厂家确认报价单","quoteConfirmed",confirmed),"报价记录" )).status()).isEqualTo("saved");}
    long account(List<String> permissions,String group,boolean allObjects) {
        long id=jdbc.queryForObject("select max(user_id)+100 from sys_user",Long.class);
        jdbc.update("insert into sys_user(user_id,user_code,user_name,login_name,username,display_name,status,all_permissions) values(?,?,?,?,?,?,'active',false)",id,"it-"+id,"基础资料测试","it-"+id,"master-real-"+id,"测试人员");
        jdbc.update("insert into plm_pilot_account(user_id,role_code,permissions,all_objects,created_by,updated_by) values(?,'domestic',?::jsonb,?,'it','it')",id,json.valueToTree(permissions).toString(),allObjects);
        if(group!=null)jdbc.update("insert into plm_collaboration_assignment(field_group,user_id) values(?,?)",group,id);
        CurrentUserContext.set(new CurrentUser(id,"forged-admin","forged",true));return id;
    }
    @Test void physicalStockIsConfirmedPerPartNotRegisteredSetsToolingOrProduction() {
        long item=archive(),ready=set(item),partial=set(item),arrived=set(item);
        assertThat(save(patch("mold_set",ready,Map.of("pcStatus","ready","tpuStatus","ready","openedDate","2026-01-01"),null)).status()).isEqualTo("saved");
        assertThat(save(patch("mold_set",partial,Map.of("pcPhysicalStatus","confirmed"),"人工清点 PC" )).status()).isEqualTo("saved");
        assertThat(save(patch("mold_set",arrived,Map.of("pcFactoryArrivalDate","2026-01-02","tpuFactoryArrivalDate","2026-01-02"),null)).status()).isEqualTo("saved");
        assertThat(summary(item)).containsEntry("registeredSets",3L).containsEntry("completeSets",1L).containsEntry("pcCount",2L).containsEntry("tpuCount",1L).containsEntry("stockState","partial");
        assertThat(service.item(item)).containsEntry("pcTotal",null).containsEntry("finishedTotal",null);
        assertThat(save(patch("mold_set",arrived,Map.of("pcPhysicalStatus","unknown"),"到厂来源待重核" )).status()).isEqualTo("saved");
        assertThat(summary(item)).containsEntry("completeSets",0L).containsEntry("pcCount",1L);
    }
    @Test void aBareHistoricalProjectLinkedArchiveCanBeOpenedAndMaintainedWithoutInventingPhysicalStock() {
        long line=jdbc.queryForObject("select product_id from plm_product where product_type='product_line' and deleted_flag=0 order by product_id limit 1",Long.class);
        long item=platformArchive(line),phone=member(brand(),"Phone awaiting mold review",null),project=project(line,"Phone awaiting mold review","0017",phone);
        jdbc.update("insert into plm_collaboration_item_project(item_id,project_id,linked_by) values(?,?,'it')",item,project);
        jdbc.update("update plm_collaboration_item set mold_name=null,engraved_code=null where item_id=?",item);
        long setsBefore=jdbc.queryForObject("select count(*) from plm_collaboration_mold_set",Long.class),partsBefore=jdbc.queryForObject("select count(*) from plm_collaboration_mold_part",Long.class);
        Query list=new Query();list.setView("master");list.setKeyword(service.item(item).get("styleName").toString());
        assertThat(service.moldPage(list).getContent()).isEmpty(); // Default browsing still omits uncompleted archives.
        list.setKeyword(null);list.setItemId(item);
        assertThat(service.moldPage(list).getContent()).extracting(row->row.get("itemId")).containsExactly(item);
        var opened=service.master(item,1,50);var master=(Map<String,Object>)opened.get("master");
        assertThat(master).containsEntry("itemId",item).containsEntry("moldName",null).containsEntry("engravedCode",null);
        assertThat(master.get("displayName")).isEqualTo(service.item(item).get("compatibleModels"));
        assertThat((com.yuewei.plm.common.vo.PageVO<?>)opened.get("sets")).satisfies(page->assertThat(page.getContent()).isEmpty());
        assertThat(summary(item)).containsEntry("registeredSets",0L).containsEntry("completeSets",0L).containsEntry("pcCount",0L).containsEntry("tpuCount",0L);
        assertThat(jdbc.queryForObject("select count(*) from plm_collaboration_mold_set",Long.class)).isEqualTo(setsBefore);
        assertThat(jdbc.queryForObject("select count(*) from plm_collaboration_mold_part",Long.class)).isEqualTo(partsBefore);
        assertThat(save(patch("item",item,Map.of("engravedCode","0017","engravedCodeConfirmed",true),"历史凭证核对原刻码，未登记实物" )).status()).isEqualTo("saved");
        assertThat((Map<String,Object>)service.master(item,1,50).get("master")).containsEntry("engravedCode","0017").containsEntry("engravedCodeConfirmed",true);
        assertThat(summary(item)).containsEntry("registeredSets",0L).containsEntry("completeSets",0L).containsEntry("pcCount",0L).containsEntry("tpuCount",0L);
        var recorded=service.createMoldSet(item,new CreateMoldSet("历史已有实物",null,null,null,true,"仓库清点表确认已有一套；部件适配另行核对"),null);
        assertThat(recorded).containsEntry("pcPhysicalStatus",null).containsEntry("tpuPhysicalStatus",null).containsEntry("quoteAmount",null);
        assertThat(recorded.get("notes").toString()).contains("仓库清点表");
        assertThat(summary(item)).containsEntry("registeredSets",1L).containsEntry("completeSets",0L).containsEntry("pcCount",0L).containsEntry("tpuCount",0L);
        assertThat(jdbc.queryForObject("select count(*) from plm_collaboration_mold_set",Long.class)).isEqualTo(setsBefore+1);
        assertThat(jdbc.queryForObject("select count(*) from plm_collaboration_mold_part",Long.class)).isEqualTo(partsBefore+2);
        assertThat(jdbc.queryForObject("select count(*) from plm_collaboration_phone_set_compatibility where item_id=?",Long.class,item)).isZero();
    }
    @Test void exactArchiveDetailsStillExcludeDeletedMissingAndUnauthorizedItems() {
        long item=archive();jdbc.update("update plm_collaboration_item set mold_name=null,engraved_code=null where item_id=?",item);
        var masters=new MoldMasterRepository(jdbc,new CollaborationRepository(jdbc,json));
        assertThat(masters.detail(item,"true","true")).containsEntry("itemId",item);
        long absent=jdbc.queryForObject("select max(item_id)+100 from plm_collaboration_item",Long.class);
        assertThatThrownBy(()->masters.detail(absent,"true","true")).isInstanceOf(com.yuewei.plm.common.exception.BusinessException.class).extracting("code").isEqualTo(40401);
        assertThatThrownBy(()->masters.detail(item,"false","true")).hasMessageContaining("模具档案不存在");
        long user=account(List.of("collaboration:view"),"engineering",false);
        assertThatThrownBy(()->service.master(item,1,50)).hasMessageContaining("对象不存在或未获授权");
        jdbc.update("insert into plm_pilot_scope(user_id,entity_type,entity_id) values(?,'item',?)",user,item);
        assertThat((Map<String,Object>)service.master(item,1,50).get("master")).containsEntry("itemId",item);
        CurrentUserContext.set(admin);jdbc.update("update plm_collaboration_item set deleted_flag=1 where item_id=?",item);
        assertThatThrownBy(()->masters.detail(item,"true","true")).hasMessageContaining("模具档案不存在");
    }
    @Test void differingPhoneCodesShareLockedEngravingAndAddMemberWithoutPhysicalOrCostDuplication() {
        long item=archive(),set=set(item),brand=brand(),seventeen=member(brand,"iPhone 17 Pro","1144"),eighteen=member(brand,"iPhone 18 Pro","1217");
        assertThat(save(patch("item",item,Map.of("engravedCode","1144","engravedCodeConfirmed",true,"brandId",brand),"确认刻在实物上的原码" )).status()).isEqualTo("saved");
        assertThat(save(members(item,List.of(seventeen,eighteen),List.of(set))).status()).isEqualTo("saved");
        assertThat(save(patch("item",item,Map.of("brandId",brand()),"不能单独把 Apple 成员档案改成另一品牌" )).message()).contains("品牌不一致");
        Long originalAnchor=jdbc.queryForObject("select code_item_id from plm_phone_model_member where id=?",Long.class,seventeen),otherAnchor=jdbc.queryForObject("select code_item_id from plm_phone_model_member where id=?",Long.class,eighteen);
        assertThat(save(patch("item",item,Map.of("phoneModelCodeItemId",originalAnchor),"补填空的历史编码锚点" )).status()).isEqualTo("saved");
        assertThat(save(patch("item",item,Map.of("phoneModelCodeItemId",otherAnchor,"phoneModelCode","1217"),"不能用兼容手机覆盖原编码" )).message()).contains("锚点固定");
        assertThat(service.item(item)).containsEntry("phoneModelCodeItemId",originalAnchor).containsEntry("phoneModelCode","1144");
        quote(set,"100","CNY",true);long before=jdbc.queryForObject("select count(*) from plm_collaboration_mold_set",Long.class);
        long nineteen=member(brand,"iPhone 19 Pro",null);assertThat(save(members(item,List.of(seventeen,eighteen,nineteen),List.of(set))).status()).isEqualTo("saved");
        var row=service.item(item);assertThat(row).containsEntry("engravedCode","1144").containsEntry("compatibleModels","原始组文字 / 不自动拆分");assertThat(row.get("moldName").toString()).contains("iPhone 19 Pro");
        for(String view:List.of("market","production")) {Query q=new Query();q.setView(view);q.setItemId(item);assertThat(service.page(q).getContent()).singleElement().satisfies(current->{assertThat(current.get("currentCompatibleModels").toString()).contains("iPhone 17 Pro","iPhone 18 Pro","iPhone 19 Pro");assertThat(current).containsEntry("compatibleModels","原始组文字 / 不自动拆分").containsEntry("phoneModelCodeItemId",originalAnchor).containsEntry("phoneModelCode","1144");});}
        assertThat(jdbc.queryForObject("select count(*) from plm_collaboration_mold_set",Long.class)).isEqualTo(before);assertThat(service.mold(set).get("quoteAmount").toString()).isEqualTo("100.00");
        assertThat(jdbc.queryForObject("select count(distinct code_item_id) from plm_phone_model_member where id in (?,?)",Long.class,seventeen,eighteen)).isEqualTo(2L);
        verify(audit,atLeastOnce()).logRequired(any(),eq(admin));
    }
    @Test void sameCodeCanIdentifyDistinctMembersWithoutDuplicatingRegistryOrDifferentArchiveSets() {
        long brand=brand(),first=member(brand,"Phone one","1144"),second=member(brand,"Phone two","1144"),a=archive(),b=archive(),sa=set(a),sb=set(b);
        assertThat(save(members(a,List.of(first,second),List.of(sa))).status()).isEqualTo("saved");assertThat(save(members(b,List.of(first),List.of(sb))).status()).isEqualTo("saved");
        assertThat(jdbc.queryForObject("select count(distinct code_item_id) from plm_phone_model_member where id in (?,?)",Long.class,first,second)).isEqualTo(1L);
        Query q=new Query();q.setView("master");q.setKeyword("Phone one");assertThat(service.moldPage(q).getContent()).extracting(r->r.get("itemId")).contains(a,b);
        assertThat(summary(a)).containsEntry("registeredSets",1L);assertThat(summary(b)).containsEntry("registeredSets",1L);
    }
    @SuppressWarnings("unchecked")
    @Test void confirmedCodeBrandsFlowToArchivesSetsFiltersAndSearchWithoutCopyingOrCreatingFit() {
        long item=archive(),set=set(item),first=brand(),second=brand();
        String code=jdbc.queryForObject("select lpad(v::text,4,'0') from generate_series(0,9999) v where not exists(select 1 from plm_code_item c where c.code_type='phone_model' and c.code_value=lpad(v::text,4,'0') and c.deleted_flag=0) order by v limit 1",String.class);
        long codeId=jdbc.queryForObject("insert into plm_code_item(code_type,code_value,code_name,status) values('phone_model',?,'组合原文，不拆分','enabled') returning code_item_id",Long.class,code);
        jdbc.update("insert into plm_phone_code_brand(code_item_id,brand_id,created_by) values(?,?,'it')",codeId,first);
        jdbc.update("update plm_collaboration_item set phone_model_code_item_id=?,phone_model_code=? where item_id=?",codeId,code,item);
        var master=(Map<String,Object>)service.master(item,1,50).get("master");
        assertThat(master).containsEntry("brandId",null).containsEntry("brandSource","code").containsEntry("brandConflict",false);
        assertThat((List<?>)master.get("brands")).hasSize(1);assertThat((List<?>)master.get("members")).isEmpty();
        assertThat(service.mold(set)).containsEntry("brandName",master.get("brandName")).containsEntry("brandSource","code").containsEntry("brandId",null);
        for(String view:List.of("master","delivery")) {
            Query q=new Query();q.setView(view);q.setItemId(item);q.setBrandId(first);q.setKeyword(master.get("brandName").toString());
            assertThat(service.moldPage(q).getContent()).extracting(row->row.get("itemId")).containsExactly(item);
            q.setBrandId(second);assertThat(service.moldPage(q).getContent()).isEmpty();
        }
        jdbc.update("insert into plm_phone_code_brand(code_item_id,brand_id,created_by) values(?,?,'it')",codeId,second);
        var changed=service.item(item);assertThat((List<?>)changed.get("brands")).hasSize(2);assertThat(changed).containsEntry("brandConflict",false).containsEntry("brandId",null);
        assertThat(jdbc.queryForObject("select brand_id from plm_collaboration_item where item_id=?",Long.class,item)).isNull();
        assertThat(jdbc.queryForObject("select count(*) from plm_collaboration_phone_member where item_id=?",Long.class,item)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from plm_collaboration_phone_set_compatibility where item_id=?",Long.class,item)).isZero();
        assertThat(summary(item)).containsEntry("registeredSets",1L).containsEntry("completeSets",0L);
        assertThat(service.mold(set)).containsEntry("quoteAmount",null).containsEntry("pcPhysicalStatus",null);
    }
    @Test void exactMaintainedRawBrandAliasesResolveButEngravingsNeverBecomePhoneCodeLinks() {
        long item=archive(),oppo=brand(),samsung=brand();String op="OP-"+UUID.randomUUID(),sm="SM-"+UUID.randomUUID();
        jdbc.update("update plm_phone_brand set aliases=array[?] where id=?",op,oppo);
        jdbc.update("update plm_phone_brand set prefixes=jsonb_build_array(jsonb_build_object('prefix',?::text,'replacement','')) where id=?",sm,samsung);
        jdbc.update("update plm_collaboration_item set brand=? where item_id=?","  "+op.toLowerCase(Locale.ROOT)+"  ",item);
        assertThat(service.item(item)).containsEntry("brandSource","raw").containsEntry("brandConflict",false).containsEntry("brandId",null);
        assertThat(service.item(item).get("brandIds").toString()).isEqualTo(List.of(oppo).toString());
        jdbc.update("update plm_collaboration_item set brand=? where item_id=?",sm,item);
        assertThat(service.item(item).get("brandIds").toString()).isEqualTo(List.of(samsung).toString());
        jdbc.update("update plm_phone_brand set aliases=array[?] where id=?",sm,oppo);
        assertThat(service.item(item)).containsEntry("brandSource","unresolved").containsEntry("brandName",null);
        long phone=member(samsung,"Known phone "+UUID.randomUUID(),"0017");
        jdbc.update("update plm_collaboration_item set brand=null,engraved_code='0017' where item_id=?",item);
        var onlyEngraving=service.item(item);assertThat(onlyEngraving).containsEntry("brandSource","unresolved").containsEntry("phoneModelCodeItemId",null).containsEntry("engravedCode","0017");
        assertThat(jdbc.queryForObject("select count(*) from plm_collaboration_phone_member where item_id=?",Long.class,item)).isZero();
    }
    @SuppressWarnings("unchecked")
    @Test void phoneMemberBrandDoesNotRequireFinishedFitAndDisagreedEvidenceIsVisible() {
        long item=archive(),set=set(item),correct=brand(),wrong=brand(),phone=member(correct,"Partial fit phone",null);
        var partial=new LinkedHashMap<>(relation(phone,List.of(set),"仅TPU有明确依据"));partial.put("pcConfirmed",false);
        assertThat(save(patch("item",item,Map.of("compatiblePhones",List.of(partial)),"逐套部分适配")).status()).isEqualTo("saved");
        var master=(Map<String,Object>)service.master(item,1,50).get("master");
        assertThat(master).containsEntry("brandSource","member").containsEntry("brandConflict",false).containsEntry("compatibilityPcConfirmed",false);
        assertThat(master.get("brandIds").toString()).isEqualTo(List.of(correct).toString());
        jdbc.update("update plm_collaboration_item set brand_id=?,brand=(select name from plm_phone_brand where id=?) where item_id=?",wrong,wrong,item);
        var conflicted=service.item(item);assertThat(conflicted).containsEntry("brandId",wrong).containsEntry("brandConflict",true);
        assertThat(conflicted.get("brandSourceDetail").toString()).contains("标准手机=","档案品牌=","原始品牌=");
        assertThat(conflicted.get("brandIds").toString()).isEqualTo(List.of(correct).toString());
        assertThat(service.mold(set)).containsEntry("brandName",conflicted.get("brandName")).containsEntry("brandConflict",true);
        assertThat(summary(item)).containsEntry("registeredSets",1L).containsEntry("completeSets",0L);
    }
    @Test void wholeMemberConflictPreservesCurrentRelationshipAndAllowsPartialFitOnlyOnOwnSet() {
        long item=archive(),set=set(item),other=set(archive()),brand=brand(),first=member(brand,"First",null),second=member(brand,"Second",null);
        var stale=members(item,List.of(second),List.of(set));assertThat(save(members(item,List.of(first),List.of(set))).status()).isEqualTo("saved");
        assertThat(save(stale).status()).isEqualTo("conflict");
        assertThat(save(members(item,List.of(first,second),List.of(other))).status()).isEqualTo("invalid");
        assertThat(save(patch("item",item,Map.of("compatiblePhoneModelIds",List.of(first,second)),null)).message()).contains("不能修改字段");
        var incomplete=new LinkedHashMap<>(relation(second,List.of(set),"尚未确认 PC"));incomplete.put("pcConfirmed",false);
        assertThat(save(patch("item",item,Map.of("compatiblePhones",List.of(relation(first,List.of(set),"原兼容依据"),incomplete)),"仅完成TPU适配确认" )).status()).isEqualTo("saved");
        assertThat(service.item(item).get("compatiblePhoneModelIds").toString()).isEqualTo(List.of(first,second).toString());
        assertThat(jdbc.queryForMap("select pc_confirmed,tpu_confirmed from plm_collaboration_phone_set_compatibility where item_id=? and phone_model_id=? and mold_set_id=?",item,second,set)).containsEntry("pc_confirmed",false).containsEntry("tpu_confirmed",true);
    }
    @Test void confirmedQuotesAggregateRangesPerCurrencyAndDistinguishZeroFromMissing() {
        long item=archive(),a=set(item),b=set(item),c=set(item),draft=set(item),missing=set(item);
        quote(a,"0","CNY",true);quote(b,"150","CNY",true);quote(c,"20","USD",true);quote(draft,"9999","CNY",false);
        var prices=(List<Map<String,Object>>)summary(item).get("quotes");assertThat(prices).hasSize(2);
        assertThat(prices.get(0)).containsEntry("currency","CNY").containsEntry("count",2);assertThat(new java.math.BigDecimal(prices.get(0).get("minAmount").toString())).isEqualByComparingTo("0");assertThat(new java.math.BigDecimal(prices.get(0).get("maxAmount").toString())).isEqualByComparingTo("150");
        assertThat(service.mold(missing)).containsEntry("quoteAmount",null);assertThat(save(patch("mold_set",missing,Map.of("quoteConfirmed",true),null)).status()).isEqualTo("invalid");
    }
    @Test void financeWriteConfirmAndReadAreIndependentAndHistoryCannotLeakPrices() {
        long item=archive(),set=set(item);quote(set,"40","CNY",true);
        long user=account(List.of("collaboration:view","finance:write"),null,true);
        var patch=new RowPatch("mold_set",set,List.of(new CellChange("quoteAmount",json.valueToTree(40),json.valueToTree(45),"报价更正")));
        assertThat(save(patch).status()).isEqualTo("saved");
        assertThat(save(new RowPatch("mold_set",set,List.of(new CellChange("quoteConfirmed",json.valueToTree(false),json.valueToTree(true),"确认")))).status()).isEqualTo("forbidden");
        assertThat(service.mold(set)).doesNotContainKeys("quoteAmount","quoteBasis","quoteConfirmed");assertThat(summary(item)).doesNotContainKey("quotes");
        assertThat(service.moldHistory(set,1,100).getContent()).filteredOn(h->h.get("fieldName").toString().startsWith("quote")).allSatisfy(h->assertThat(h).containsEntry("redacted",true).doesNotContainKeys("before","after","reason"));
        assertThat(save(new RowPatch("mold_set",set,List.of(new CellChange("quoteAmount",json.valueToTree(40),json.valueToTree(50),"过期报价")))).row()).doesNotContainKey("quoteAmount");
        assertThat(service.moldHistory(set,1,100).getContent()).anySatisfy(h->assertThat(h).containsEntry("actorName","master-real-"+user));
    }
    @Test void lockedEngravingAndPartCodesRejectOrdinaryEditsAndAdminMustExplainCorrections() {
        long item=archive(),set=set(item);assertThat(save(patch("mold_set",set,Map.of("pcOriginalMoldCode","PC-1144"),null)).status()).isEqualTo("saved");
        assertThat(save(patch("item",item,Map.of("engravedCode","1144","engravedCodeConfirmed",true),"确认实物" )).status()).isEqualTo("saved");
        account(List.of("collaboration:view"),"engineering",true);
        assertThat(save(patch("item",item,Map.of("engravedCode","1217"),"想换码" )).status()).isEqualTo("forbidden");assertThat(save(patch("mold_set",set,Map.of("pcOriginalMoldCode","PC-1217"),"想换码" )).status()).isEqualTo("forbidden");
        CurrentUserContext.set(admin);assertThat(save(patch("item",item,Map.of("engravedCode","1143"),null)).status()).isEqualTo("invalid");
        assertThat(save(patch("item",item,Map.of("engravedCode","1144-X"),"核对旧记录录错；实物原刻码未改变" )).status()).isEqualTo("saved");assertThat(service.history(item,1,100).getContent()).anySatisfy(h->assertThat(h).containsEntry("fieldName","engravedCode").containsEntry("reason","核对旧记录录错；实物原刻码未改变"));
    }
    @Test void newMoldLedgerRejectsScheduleWritesWhileLegacyScheduleStillUsesSameRow() {
        long item=archive(),set=set(item);var date=patch("mold_set",set,Map.of("openedDate","2026-10-01"),null);
        assertThatThrownBy(()->service.saveMoldLedger(new BatchSave(List.of(date)),null)).hasMessageContaining("不填写排程");assertThat(save(date).status()).isEqualTo("saved");
        assertThat(service.master(item,1,50)).containsKey("sets");assertThat(service.mold(set)).containsEntry("openedDate","2026-10-01");
        Query legacy=new Query();legacy.setItemId(item);assertThat(service.moldPage(legacy).getContent()).extracting(r->r.get("moldSetId")).containsExactly(set);
    }
    @Test void copyingCreatesNewPendingPhysicalSetWithoutCopyingPriceOrCompatibilityScope() {
        long item=archive(),set=set(item),phone=member(brand(),"Original phone",null);save(members(item,List.of(phone),List.of(set)));quote(set,"100","CNY",true);save(patch("mold_set",set,Map.of("pcPhysicalStatus","confirmed","tpuPhysicalStatus","confirmed"),"实物清点" ));
        var copy=service.copyMold(set,new CreateMoldSet("历史已有第二套",null,null,null,true,"清点证明已有第二套"),null);
        assertThat(copy).containsEntry("quoteAmount",null).containsEntry("quoteConfirmed",false).containsEntry("pcPhysicalStatus",null).containsEntry("sourceMoldSetId",set);
        assertThat(service.item(item).get("compatibilityMoldSetIds").toString()).isEqualTo(List.of(set).toString());assertThat(summary(item)).containsEntry("registeredSets",2L).containsEntry("completeSets",1L);
    }
    @Test void publicCreationRequiresAnExistingPhysicalDeclarationAndFactEvidence() {
        long item=archive(),original=set(item);
        long before=jdbc.queryForObject("select count(*) from plm_collaboration_mold_set where item_id=?",Long.class,item);
        assertThatThrownBy(()->service.createMoldSet(item,new CreateMoldSet("新制作",null,null),null)).hasMessageContaining("PLM开模申请");
        assertThatThrownBy(()->service.copyMold(original,new CreateMoldSet("重新开模",null,null),null)).hasMessageContaining("PLM开模申请");
        assertThatThrownBy(()->service.createMoldSet(item,new CreateMoldSet("已有实物",null,null,null,true,"  "),null)).hasMessageContaining("事实依据");
        assertThat(jdbc.queryForObject("select count(*) from plm_collaboration_mold_set where item_id=?",Long.class,item)).isEqualTo(before);
        var recorded=service.createMoldSet(item,new CreateMoldSet("历史已有实物",null,null,null,true,"仓库实物清点单2026-10-14"),null);
        assertThat(recorded.get("notes").toString()).contains("已有实物登记依据","仓库实物清点单2026-10-14");
        assertThat(recorded).containsEntry("pcPhysicalStatus",null).containsEntry("tpuPhysicalStatus",null).containsEntry("quoteAmount",null);
        assertThat(jdbc.queryForObject("select count(*) from plm_collaboration_phone_set_compatibility where item_id=?",Long.class,item)).isZero();
    }
    @Test void taskNamesUseEachSetsCurrentPhoneAndKeepSourceIntentUnconfirmed() {
        long item=archive(),first=set(item),second=set(item),planned=set(item),brand=brand();
        long a=member(brand,"Phone A","0001"),b=member(brand,"Phone B","0002"),source=member(brand,"Phone C","0003");
        assertThat(save(patch("item",item,Map.of("compatiblePhones",List.of(relation(a,List.of(first),"A原套适配"),relation(b,List.of(second),"B第二套适配"))),"逐套核对")).status()).isEqualTo("saved");
        jdbc.update("update plm_phone_model_member set model_name='Phone A updated' where id=?",a);
        jdbc.update("insert into plm_mold_application(process_code,model,applicant_id,phone_model_id,item_id,mold_set_id,status,approval_granted,execution_status,provisioning_status,created_by,updated_by) values('it','原来源名称',?,?,?,?,'converted',true,'pending','ready','it','it')",admin.userId(),source,item,planned);
        var repository=new CollaborationRepository(jdbc,json);
        var schedules=new MoldScheduleService(jdbc,json,service,repository,new com.yuewei.plm.module.pilot.PilotAccessService(jdbc,json),tx);
        var q=new MoldScheduleTypes.Query();q.setCategory("all");q.setItemId(item);
        var tasks=schedules.tasks(q).getContent();
        assertThat(tasks).isNotEmpty();
        assertThat(tasks.stream().filter(t->t.moldSetId()==first)).isNotEmpty().allSatisfy(t->assertThat(t.compatibleModels()).contains("Phone A updated","0001").doesNotContain("Phone B","Phone C","原来源名称"));
        assertThat(tasks.stream().filter(t->t.moldSetId()==second)).isNotEmpty().allSatisfy(t->assertThat(t.compatibleModels()).contains("Phone B","0002").doesNotContain("Phone A","Phone C"));
        assertThat(tasks.stream().filter(t->t.moldSetId()==planned)).isNotEmpty().allSatisfy(t->assertThat(t.compatibleModels()).contains("Phone C","0003","来源申请，适配待确认").doesNotContain("Phone A","Phone B","原来源名称"));
        assertThat(jdbc.queryForObject("select count(*) from plm_collaboration_phone_set_compatibility where mold_set_id=?",Long.class,planned)).isZero();
    }
    @Test void confirmedOldDisabledMemberCanRemainWhileNewDisabledMemberCannotBeAdded() {
        long item=archive(),set=set(item),brand=brand(),old=member(brand,"Old phone",null),fresh=member(brand,"New phone",null);
        save(members(item,List.of(old),List.of(set)));jdbc.update("update plm_phone_model_member set status='disabled' where id=?",old);
        assertThat(save(members(item,List.of(old,fresh),List.of(set))).status()).isEqualTo("saved");
        long disabled=member(brand,"Disabled phone",null);jdbc.update("update plm_phone_model_member set status='disabled' where id=?",disabled);
        assertThat(save(members(item,List.of(old,fresh,disabled),List.of(set))).status()).isEqualTo("invalid");
    }
    @Test void objectScopeLimitsSummarySetsAndCompatibilityApplicabilityWithoutPartialReplacement() {
        long item=archive(),visible=set(item),hidden=set(item),visibleBrand=brand(),hiddenBrand=brand(),phone=member(visibleBrand,"Scoped phone",null),hiddenPhone=member(hiddenBrand,"Hidden phone",null);
        save(patch("item",item,Map.of("compatiblePhones",List.of(relation(phone,List.of(visible),"可见套适配"),relation(hiddenPhone,List.of(hidden),"其他套适配"))),"逐手机确认" ));quote(hidden,"999","CNY",true);
        long user=account(List.of("collaboration:view","finance:read"),"engineering",false);jdbc.update("insert into plm_pilot_scope(user_id,entity_type,entity_id) values(?,'mold',?)",user,visible);
        assertThat(summary(item)).containsEntry("registeredSets",1L).containsEntry("quotes",List.of());
        var detail=service.master(item,1,50);var rows=(List<Map<String,Object>>)detail.get("members");assertThat(rows).hasSize(1);assertThat(rows.get(0).get("setIds").toString()).isEqualTo(List.of(visible).toString());
        Query filter=new Query();filter.setView("master");filter.setItemId(item);filter.setBrandId(hiddenBrand);assertThat(service.moldPage(filter).getContent()).isEmpty();filter.setBrandId(visibleBrand);assertThat(service.moldPage(filter).getContent()).hasSize(1);
        assertThat(save(members(item,List.of(phone),List.of(visible))).status()).isEqualTo("forbidden");
        CurrentUserContext.set(admin);assertThat(service.item(item).get("compatibilityMoldSetIds").toString()).isEqualTo(List.of(visible,hidden).toString());
    }
    @Test void quoteConfirmationChecksDisplayedAmountCurrencyAndBasisBeforeConfirming() {
        long item=archive(),set=set(item);quote(set,"100","CNY",false);
        var confirm=new RowPatch("mold_set",set,List.of(new CellChange("quoteConfirmed",json.valueToTree(false),json.valueToTree(true),"财务确认")),Map.of("quoteAmount",json.valueToTree(100),"quoteCurrency",json.valueToTree("CNY"),"quoteBasis",json.valueToTree("厂家确认报价单")));
        assertThat(save(patch("mold_set",set,Map.of("quoteConfirmed",true),"缺依据原值" )).status()).isEqualTo("invalid");
        assertThat(save(patch("mold_set",set,Map.of("quoteAmount",120),"更新报价" )).status()).isEqualTo("saved");
        assertThat(save(confirm).status()).isEqualTo("conflict");assertThat(service.mold(set)).containsEntry("quoteConfirmed",false);
        var latest=new RowPatch("mold_set",set,confirm.changes(),Map.of("quoteAmount",json.valueToTree(120),"quoteCurrency",json.valueToTree("CNY"),"quoteBasis",json.valueToTree("厂家确认报价单")));
        assertThat(save(latest).status()).isEqualTo("saved");
    }
    long platformArchive(long line) {return ((Number)service.createMaster(new CreateMaster(line,"关联款式-"+UUID.randomUUID(),"关联模具",null,null,"原始共模组文字"),null).get("itemId")).longValue();}
    long project(long line,String model,String code,Long phoneId) {
        return jdbc.queryForObject("insert into plm_product(product_code,product_name,product_type,parent_product_id,model,phone_model_code,phone_model_member_id,status,version_no,lock_status,created_by,updated_by) values(?,?,'model_variant',?,?,?,?,'draft','A','unlocked','it','it') returning product_id",Long.class,"MASTER-IT-"+UUID.randomUUID(),model,line,model,code,phoneId);
    }
    @Test void newProjectLinksRequireActivePhoneAndBrandWhileHistoricalLinksRemainTraceable() {
        long line=jdbc.queryForObject("select product_id from plm_product where product_type='product_line' and deleted_flag=0 order by product_id limit 1",Long.class),item=platformArchive(line),set=set(item),brand=brand(),phone=member(brand,"Project phone","1144");
        assertThat(save(members(item,List.of(phone),List.of(set))).status()).isEqualTo("saved");long first=project(line,"Project phone","1144",phone);
        assertThat(service.linkProjects(item,new LinkProjects(List.of(first),true,List.of(),false),null).get("projectCount")).isEqualTo(1L);
        jdbc.update("update plm_phone_model_member set status='disabled' where id=?",phone);long second=project(line,"Project phone","1144",phone);
        assertThatThrownBy(()->service.linkProjects(item,new LinkProjects(List.of(first,second),true,List.of(first),false),null)).hasMessageContaining("启用");
        assertThat(service.linkProjects(item,new LinkProjects(List.of(first),false,List.of(first),false),null).get("projectCount")).isEqualTo(1L);
        jdbc.update("update plm_phone_model_member set status='enabled' where id=?",phone);jdbc.update("update plm_phone_brand set status='disabled' where id=?",brand);
        assertThatThrownBy(()->service.linkProjects(item,new LinkProjects(List.of(first,second),true,List.of(first),false),null)).hasMessageContaining("启用");
        assertThat(service.item(item).get("projectCount")).isEqualTo(1L);
    }
    @Test void newProjectLinksUseStableMemberAndCodeIdsNotReusedCodeStrings() {
        long line=jdbc.queryForObject("select product_id from plm_product where product_type='product_line' and deleted_flag=0 order by product_id limit 1",Long.class),item=platformArchive(line),set=set(item),brand=brand(),phone=member(brand,"Stable phone","1144"),unrelated=member(brand,"Unrelated phone","1144");
        assertThat(save(members(item,List.of(phone),List.of(set))).status()).isEqualTo("saved");long wrong=project(line,"Stable phone","1144",unrelated);
        assertThatThrownBy(()->service.linkProjects(item,new LinkProjects(List.of(wrong),true,List.of(),false),null)).hasMessageContaining("唯一兼容手机");
        long first=project(line,"Stable phone","1144",phone);service.linkProjects(item,new LinkProjects(List.of(first),true,List.of(),false),null);
        Long oldCode=jdbc.queryForObject("select code_item_id from plm_phone_model_member where id=?",Long.class,phone);jdbc.update("update plm_code_item set deleted_flag=1 where code_item_id=?",oldCode);
        jdbc.update("insert into plm_code_item(code_type,code_value,code_name,status) values('phone_model','1144',?,'enabled')","重用编码-"+UUID.randomUUID());
        long candidate=project(line,"Stable phone","1144",phone);
        assertThatThrownBy(()->service.linkProjects(item,new LinkProjects(List.of(first,candidate),true,List.of(first),false),null)).hasMessageContaining("软删除");
        assertThat(service.linkProjects(item,new LinkProjects(List.of(first),false,List.of(first),false),null).get("projectCount")).isEqualTo(1L);
    }
    @Test void wholeRelationshipsPreservePerPhoneSetsCreatorsAndDetectSameUnionConcurrentSwap() {
        long item=archive(),firstSet=set(item),secondSet=set(item),brand=brand(),firstPhone=member(brand,"Phone A",null),secondPhone=member(brand,"Phone B",null);
        var original=List.of(relation(firstPhone,List.of(firstSet),"A 仅适配原套"),relation(secondPhone,List.of(secondSet),"B 仅适配复制套"));
        assertThat(save(patch("item",item,Map.of("compatiblePhones",original),"逐手机实物适配" )).status()).isEqualTo("saved");
        var stale=patch("item",item,Map.of("compatiblePhones",List.of(relation(firstPhone,List.of(firstSet),"A 补充原套依据"),original.get(1))),"原列表待保存修改");
        long user=account(List.of("collaboration:view"),"engineering",true);
        var swap=List.of(relation(firstPhone,List.of(secondSet),"A 重新核对复制套"),relation(secondPhone,List.of(firstSet),"B 重新核对原套"));
        assertThat(save(patch("item",item,Map.of("compatiblePhones",swap),"逐手机调整适用实物" )).status()).isEqualTo("saved");
        assertThat(service.item(item).get("compatibilityMoldSetIds").toString()).isEqualTo(List.of(firstSet,secondSet).toString());
        CurrentUserContext.set(admin);assertThat(save(stale).status()).isEqualTo("conflict");
        var saved=(List<Map<String,Object>>)service.item(item).get("compatiblePhones");assertThat(saved.get(0).get("setIds").toString()).isEqualTo(List.of(secondSet).toString());assertThat(saved.get(1).get("setIds").toString()).isEqualTo(List.of(firstSet).toString());
        var provenance=jdbc.queryForList("select phone_model_id,created_by,updated_by from plm_collaboration_phone_member where item_id=? order by phone_model_id",item);assertThat(provenance).allSatisfy(row->assertThat(row).containsEntry("created_by",admin.username()).containsEntry("updated_by","master-real-"+user));
        assertThat(save(patch("item",item,Map.of("compatiblePhones",List.of(swap.get(0))),"仅移除 B，不动 A 适配依据" )).status()).isEqualTo("saved");
        var kept=jdbc.queryForMap("select created_by,updated_by,evidence from plm_collaboration_phone_member where item_id=? and phone_model_id=?",item,firstPhone);assertThat(kept).containsEntry("created_by",admin.username()).containsEntry("updated_by","master-real-"+user).containsEntry("evidence","A 重新核对复制套");
    }
}
