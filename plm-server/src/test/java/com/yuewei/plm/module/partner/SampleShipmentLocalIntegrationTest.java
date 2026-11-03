package com.yuewei.plm.module.partner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuewei.plm.common.exception.BusinessException;
import com.yuewei.plm.common.security.CurrentUser;
import com.yuewei.plm.module.operationlog.service.OperationLogService;
import com.yuewei.plm.module.pilot.PilotAccessService;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static com.yuewei.plm.module.partner.PartnerValues.*;

/** Random owned schema only; no writes to the restored clone's real business schema. */
@EnabledIfEnvironmentVariable(named="PLM_SAMPLE_IDENTITY_TEST_URL",matches="(?:jdbc:postgresql://127\\.0\\.0\\.1:15486/plm_sample_qr_kwhvfzrr(?:\\?currentSchema=plm)?|jdbc:postgresql://127\\.0\\.0\\.1:15504/plm_sales_policy_qa\\?currentSchema=plm|jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/plm_shipment_flow_qa)")
class SampleShipmentLocalIntegrationTest {
 JdbcTemplate admin,jdbc;String schema;PartnerStore store;SampleShipmentService service;TransactionTemplate tx;SampleIdentityRegistry registry,warehouse;
 String role="administrator";long actorId=1;
 @BeforeEach void setup(){
  String url=System.getenv("PLM_SAMPLE_IDENTITY_TEST_URL"),password=System.getenv("PLM_SAMPLE_IDENTITY_TEST_PASSWORD");String base=url.split("\\?")[0];admin=new JdbcTemplate(new DriverManagerDataSource(base,"postgres",password));if(url.endsWith("/plm_shipment_flow_qa")){assertThat(admin.queryForObject("select current_database()",String.class)).isEqualTo("plm_shipment_flow_qa");assertThat(admin.queryForObject("select system_identifier::text from pg_control_system()",String.class)).isNotEqualTo("7651923859845308451");}if(url.contains(":15504/")){assertThat(admin.queryForObject("select current_database()",String.class)).isEqualTo("plm_sales_policy_qa");assertThat(admin.queryForObject("select system_identifier::text from pg_control_system()",String.class)).isNotEqualTo("7651923859845308451");}schema="sample_shipment_qa_"+UUID.randomUUID().toString().replace("-","");admin.execute("create schema "+schema);
  var ds=new DriverManagerDataSource(base+"?currentSchema="+schema+",plm","postgres",password);jdbc=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
  jdbc.execute("""
   create table sys_user(user_id bigint primary key,username text,display_name text,status text,deleted_flag int default0,all_permissions boolean default false);
   create table plm_pilot_account(user_id bigint primary key,role_code text,permissions jsonb);
   create table plm_pilot_scope(user_id bigint,entity_type text,entity_id bigint,unique(user_id,entity_type,entity_id));
   create table plm_partner_record(record_id bigserial primary key,kind text,code text unique,name text,status text default 'draft',version bigint default0,data jsonb default '{}',site text default '',external_id text default '',customer_id bigint,source_project_id bigint,supplier_code text,parent_record_id bigint,owner_user_id bigint,created_by text,updated_by text,updated_at timestamp default now(),deleted_flag int default0,sample_identity_id bigint);
   create table plm_sample_identity(identity_id bigint primary key,identity_key text,source_type text,basis jsonb);
   create table plm_supplier_profile(supplier_code text primary key,supplier_name text,short_code text,deleted_flag int default 0);
   create table plm_partner_event(event_id bigserial primary key,record_id bigint,action text,data jsonb,request_id text,created_at timestamp default now(),created_by text,actor_id bigint,unique(record_id,request_id));
   create table plm_partner_revision(revision_id bigserial primary key,record_id bigint,revision bigint,data jsonb,created_by text,created_at timestamp default now(),unique(record_id,revision));
   create table plm_partner_create_request(actor_id bigint,kind text,request_id text,object_id bigint,primary key(actor_id,kind,request_id));
   create table plm_attachment(attachment_id bigint primary key,file_name text,version_no text,content_type text,file_size bigint,deleted_flag int default 0);
   create table plm_partner_file(record_id bigint,attachment_id bigint,purpose text,unique(record_id,attachment_id));
   insert into sys_user values(1,'admin','国内工程','active',0,true),(2,'mx-one','墨西哥收样一','active',0,false),(3,'mx-other','墨西哥收样二','active',0,false);
   insert into plm_pilot_account values(2,'mexico','["task:arrival","file:read"]'),(3,'mexico','["task:arrival","file:read"]');
   """.replace("default0","default 0"));
  var access=mock(PilotAccessService.class);
  when(access.actor()).thenAnswer(i->new CurrentUser(actorId,role+actorId,role.equals("mexico")?"墨西哥收样":"国内工程",!role.equals("mexico")));
  when(access.actorId()).thenAnswer(i->actorId);when(access.access()).thenAnswer(i->new PilotAccessService.Access(access.actor(),role,role.equals("mexico")?List.of("task:arrival","file:read"):List.of("*"),!role.equals("mexico"),true));
  when(access.hasPermission(anyString())).thenAnswer(i->!role.equals("mexico")||Set.of("task:arrival","file:read").contains(i.<String>getArgument(0)));
  doAnswer(i->{if(role.equals("mexico"))throw new BusinessException(40103,"not domestic");return null;}).when(access).requireDomesticView();
  doAnswer(i->{if(!access.hasPermission(i.getArgument(0)))throw new BusinessException(40103,"not authorized");return null;}).when(access).requirePermission(anyString());
  doAnswer(i->{if(role.equals("mexico"))throw new BusinessException(40103,"not partner-authorized");return null;}).when(access).requirePartner(anyLong());
  when(access.visibleSqlClause(anyString(),anyString())).thenReturn("true");when(access.isPilot()).thenReturn(false);
  store=new PartnerStore(jdbc,new ObjectMapper().findAndRegisterModules(),access,mock(OperationLogService.class));registry=mock(SampleIdentityRegistry.class);warehouse=new SampleIdentityRegistry(store);
  when(registry.requireVisible(anyLong(),anyBoolean())).thenAnswer(i->{long id=i.getArgument(0);require(Set.of(101L,102L,103L).contains(id),"identity forbidden");return Map.of("id",id,"code","SY-"+String.format("%06d",id),"name","测试手机壳","specName","实际型号／颜色","domain","partner");});
  when(registry.visibleBatches(anyLong(),anyBoolean())).thenAnswer(i->jdbc.queryForList("select record_id as id from plm_partner_record where kind='sample' and deleted_flag=0 and sample_identity_id=? order by record_id",i.<Long>getArgument(0)));
  var suppliers=mock(SupplierProfileService.class);when(suppliers.requireSupplier(anyString())).thenReturn(Map.of("code","SUP-QA"));service=new SampleShipmentService(store,registry,suppliers);
  record(101,"sample_identity",null,Map.of());record(102,"sample_identity",null,Map.of());jdbc.update("insert into plm_sample_identity values(101,'one','catalog','{}'),(102,'two','catalog','{}')");batch(201,101,6);batch(202,101,4);
 }
 @AfterEach void cleanup(){if(admin!=null&&schema!=null)admin.execute("drop schema "+schema+" cascade");}
 private Map<String,Object> data(Object... values){var m=new LinkedHashMap<String,Object>();for(int i=0;i<values.length;i+=2)m.put(values[i].toString(),values[i+1]);return m;}
 private <T>T run(Supplier<T> action){return tx.execute(status->action.get());}
 private void record(long id,String kind,Long identity,Map<String,Object> d){jdbc.update("insert into plm_partner_record(record_id,kind,code,name,status,data,owner_user_id,created_by,updated_by,sample_identity_id) values(?,?,?,?,?,?::jsonb,1,'qa','qa',?)",id,kind,(kind.equals("sample")?"SP-":"SY-")+String.format("%06d",id),"测试手机壳","active",encode(store.json,d),identity);}
 private void batch(long id,long identity,long qty){record(id,"sample",identity,data("name","测试手机壳","status","active","receivedQty",qty,"receivedDate","2026-01-01","sourceType","catalog","selectionId",400L,"selectionRowId","00000000-0000-0000-0000-000000000001","phoneModelId",10L,"colorId",20L,"sourceSnapshot",Map.of("priceCents",99999,"customerName","private")));store.event(id,"receive",data("qty",qty,"location","深圳办公室","date","2026-01-01"),"receive-"+id);}
 private Map<String,Object> draft(long quantity){return run(()->service.create(data("requestId",UUID.randomUUID().toString(),"direction","mexico_outbound","destination","墨西哥办公室","receiverUserId",2L,"rows",List.of(data("rowId","line-a","sampleIdentityId",101L,"qty",quantity)))));}
 private Map<String,Object> dispatch(Map<String,Object> d){return run(()->service.action(number(d.get("id")),"dispatched",data("requestId","dispatch-one","version",d.get("version"),"date","2026-01-01","tracking","QA-TRACK-1")));}
 private long aggregate(String field){return jdbc.queryForList("select record_id from plm_partner_record where kind='sample'").stream().mapToLong(r->number(SampleLedger.replay(store.events(number(r.get("record_id")))).view().get(field))).sum();}
 @Test void allocatedReceiverCanPartiallyReceiveGoodDamagedAndLossWithoutADomesticPartnerGrant(){
  var d=dispatch(draft(8));long id=number(d.get("id"));assertThat(aggregate("inTransit")).isEqualTo(8);role="mexico";actorId=2;
  var scan=warehouse.resolve("SY-000101");assertThat(scan.get("type")).isEqualTo("identity");assertThat(map(map(scan.get("identity")).get("balances"))).containsEntry("onhand",0L).containsEntry("inTransit",8L);assertThat(scan.toString()).doesNotContain("priceCents","sourceSnapshot","customerName","revisions","shipments","receiverUserId");
  var input=data("requestId","arrive-one","version",d.get("version"),"date","2026-01-02","location","墨西哥办公室","rows",List.of(data("rowId","line-a","goodQty",3L,"damagedQty",1L)));
  d=run(()->service.action(id,"receive",input));assertThat(d).containsEntry("status","partially_received");assertThat(aggregate("inTransit")).isEqualTo(4);assertThat(aggregate("damaged")).isEqualTo(1);assertThat(aggregate("onhand")).isEqualTo(6);
  run(()->service.action(id,"receive",input));assertThat(aggregate("inTransit")).isEqualTo(4);
  role="administrator";actorId=1;run(()->service.action(id,"receive",input));assertThat(aggregate("inTransit")).isEqualTo(4);
  var changed=new LinkedHashMap<>(input);changed.put("note","different content");assertThatThrownBy(()->run(()->service.action(id,"receive",changed))).hasMessageContaining("不同动作或内容");
  role="administrator";actorId=1;var loss=data("requestId","loss-one","version",d.get("version"),"date","2026-01-02","reason","确认短少","rows",List.of(data("rowId","line-a","qty",1L)));d=run(()->service.action(id,"loss",loss));
  var finalReceive=data("requestId","arrive-two","version",d.get("version"),"date","2026-01-03","location","墨西哥办公室","rows",List.of(data("rowId","line-a","goodQty",3L,"damagedQty",0L)));d=run(()->service.action(id,"receive",finalReceive));assertThat(d).containsEntry("status","received");assertThat(aggregate("inTransit")).isZero();assertThat(aggregate("onhand")).isEqualTo(9);assertThat(aggregate("available")).isEqualTo(8);
 }
 @Test void otherReceiversCannotOpenParcelsButSharedWarehouseScanStaysNarrow(){
  var d=dispatch(draft(3));role="mexico";actorId=3;
  assertThatThrownBy(()->service.detail(number(d.get("id")))).isInstanceOf(BusinessException.class).hasMessage("样品或寄样包裹不存在或未获授权");
  var local=warehouse.resolve("SY-000101");assertThat(map(map(local.get("identity")).get("balances"))).containsEntry("inTransit",3L).containsEntry("onhand",0L);assertThat(local.toString()).doesNotContain("shipments","QA-TRACK-1","receiverUserId","sourceSnapshot");assertThatThrownBy(()->warehouse.resolve("SY-999999")).hasMessageContaining("不存在或未获授权");
  actorId=2;assertThatThrownBy(()->run(()->service.action(number(d.get("id")),"tracking",data("requestId","bad","version",d.get("version"))))).hasMessageContaining("仅能登记");
 }
 @Test void dispatchFailureRollsBackEveryBatchAndReservedPiecesCannotBeTaken(){
  store.event(201,"retain",data("date","2026-01-01","qty",5L,"location","深圳办公室","retainedIds",List.of("L1","L2","L3","L4","L5")),"retain");var d=draft(8);
  assertThatThrownBy(()->dispatch(d)).hasMessageContaining("实际可用样品不足");assertThat(aggregate("inTransit")).isZero();assertThat(aggregate("available")).isEqualTo(5);assertThat(service.detail(number(d.get("id")))).containsEntry("status","draft");
 }
 @Test void replayedDispatchAndChangedRequestDoNotWithdrawTwice(){
  var before=draft(5);var after=dispatch(before);dispatch(before);assertThat(aggregate("inTransit")).isEqualTo(5);
  var changed=data("requestId","dispatch-one","version",before.get("version"),"date","2026-01-01","tracking","DIFFERENT");assertThatThrownBy(()->run(()->service.action(number(after.get("id")),"dispatched",changed))).hasMessageContaining("不同动作或内容");assertThat(aggregate("available")).isEqualTo(5);
 }
 @Test void actualDispatchRetainsTenPiecePlanButOnlyEightPiecesCanBeReceived(){
  var planned=draft(10);long id=number(planned.get("id"));assertThat(maps(planned.get("rows")).get(0)).containsEntry("inTransit",0L);
  var actual=data("requestId","actual-eight","version",planned.get("version"),"date","2026-01-01","tracking","QA-ACTUAL-8","rows",List.of(data("rowId","line-a","sampleIdentityId",101L,"qty",8L,"plannedQty",999L)));
  var dispatched=run(()->service.action(id,"dispatched",actual));assertThat(map(dispatched.get("totals"))).containsEntry("planned",10L).containsEntry("dispatched",8L).containsEntry("inTransit",8L);assertThat(maps(dispatched.get("rows")).get(0)).containsEntry("plannedQty",10).containsEntry("qty",8).containsEntry("inTransit",8L);assertThat(aggregate("available")).isEqualTo(2);
  assertThat(jdbc.queryForObject("select jsonb_exists(data->'rows'->0,'inTransit') from plm_partner_record where record_id=?",Boolean.class,id)).isFalse();
  role="mexico";actorId=2;assertThat(maps(service.detail(id).get("rows")).get(0)).containsEntry("inTransit",8L);
  var tooMany=data("requestId","receive-nine","version",dispatched.get("version"),"date","2026-01-02","location","墨西哥办公室","rows",List.of(data("rowId","line-a","goodQty",9L)));assertThatThrownBy(()->run(()->service.action(id,"receive",tooMany))).hasMessageContaining("超过");
  var received=run(()->service.action(id,"receive",data("requestId","receive-eight","version",dispatched.get("version"),"date","2026-01-02","location","墨西哥办公室","rows",List.of(data("rowId","line-a","goodQty",8L)))));assertThat(received).containsEntry("status","received");assertThat(map(received.get("totals"))).containsEntry("planned",10L).containsEntry("dispatched",8L).containsEntry("receivedGood",8L).containsEntry("inTransit",0L);assertThat(maps(received.get("rows")).get(0)).containsEntry("inTransit",0L);assertThat(aggregate("onhand")).isEqualTo(10);
 }
 @Test void destinationsAndChronologyAreActualFactsAndDraftReceiverTextGrantsNothing(){
  var d=run(()->service.create(data("requestId","manual-recipient","direction","mexico_outbound","receiverName","现场接收人待绑定","rows",List.of(data("sampleIdentityId",101L,"qty",2L)))));assertThat(d).containsEntry("receiverName","现场接收人待绑定");assertThatThrownBy(()->dispatch(d)).hasMessageContaining("目的地");
  var sent=dispatch(draft(2));var invalid=data("requestId","early","version",sent.get("version"),"date","2025-12-31","location","墨西哥办公室","rows",List.of(data("rowId","line-a","goodQty",1L)));assertThatThrownBy(()->run(()->service.action(number(sent.get("id")),"receive",invalid))).hasMessageContaining("不能早于");
 }
 @Test void inboundActualBatchesConsumeThePlannedRangeOnceAndNeverCreateAnotherInventoryEvent(){
  var rows=List.of(data("rowId","pending-source","selectionRowId","00000000-0000-0000-0000-000000000001","sampleIdentityId",0L,"qty",4L,"receivedGood",0L));record(301,"sample_shipment",null,data("name","供应商寄样","status","dispatched","direction","supplier_inbound","selectionId",400L,"dispatch",Map.of("date","2026-01-01"),"rows",rows));jdbc.update("update plm_partner_record set status='dispatched' where record_id=301");
  long events=jdbc.queryForObject("select count(*) from plm_partner_event where record_id=201",Long.class);
  assertThatThrownBy(()->run(()->{SampleShipmentReceipts.recordInbound(store,301,List.of(store.load(201,"sample",false)),"too-many");return null;})).hasMessageContaining("超过");
  batch(203,102,2);var b=store.load(203,"sample",false);run(()->{SampleShipmentReceipts.recordInbound(store,301,List.of(b),"one");return null;});run(()->{SampleShipmentReceipts.recordInbound(store,301,List.of(b),"one");return null;});
  assertThat(jdbc.queryForObject("select count(*) from plm_partner_event where record_id=201",Long.class)).isEqualTo(events);var shipment=service.detail(301);assertThat(shipment).containsEntry("status","partially_received");assertThat(maps(maps(shipment.get("rows")).get(0).get("receivedBatches")).get(0)).containsEntry("sampleCode","SY-000102");assertThat(jdbc.queryForObject("select jsonb_exists(data,'sampleCode') from plm_partner_record where record_id=203",Boolean.class)).isFalse();
  assertThatThrownBy(()->run(()->{SampleShipmentReceipts.recordInbound(store,301,List.of(b),"different");return null;})).hasMessageContaining("不能重复登记");
 }
 @Test void inboundChronologyFailureRollsBackEveryEarlierBatchLink(){
  var rows=List.of(data("rowId","source","selectionRowId","00000000-0000-0000-0000-000000000001","sampleIdentityId",0L,"qty",5L));record(301,"sample_shipment",null,data("name","供应商寄样","status","dispatched","direction","supplier_inbound","selectionId",400L,"dispatch",Map.of("date","2026-01-01"),"rows",rows));jdbc.update("update plm_partner_record set status='dispatched' where record_id=301");batch(203,101,3);batch(204,101,2);jdbc.update("update plm_partner_record set data=jsonb_set(data,'{receivedDate}','\"2025-12-31\"') where record_id=204");
  assertThatThrownBy(()->run(()->{SampleShipmentReceipts.recordInbound(store,301,List.of(store.load(203,"sample",false),store.load(204,"sample",false)),"invalid-chronology");return null;})).hasMessageContaining("不能早于供应商");
  assertThat(jdbc.queryForObject("select count(*) from plm_partner_record where record_id in (203,204) and jsonb_exists(data,'supplierShipmentId')",Long.class)).isZero();assertThat(jdbc.queryForObject("select count(*) from plm_partner_event where record_id=301",Long.class)).isZero();assertThat(map(service.detail(301).get("totals"))).containsEntry("receivedGood",0L).containsEntry("inTransit",5L);
 }
 @Test void oldPrintedIdentityAndExistingParcelRowsResolveToTheSameExplicitCanonicalIdentity(){
  var d=dispatch(draft(3));record(103,"sample_identity",null,Map.of("redirectIdentityId",101L));jdbc.update("insert into plm_sample_identity values(103,'legacy-alias','catalog','{}')");jdbc.update("update plm_partner_record set data=jsonb_set(data,'{rows,0,sampleIdentityId}','103') where record_id=?",d.get("id"));
  role="mexico";actorId=2;var scanned=warehouse.resolve("SY-000103");assertThat(scanned).containsEntry("sampleIdentityId",101L).containsEntry("sampleCode","SY-000101");assertThat(scanned).doesNotContainKey("shipments");actorId=3;assertThat(warehouse.resolve("SY-000103")).containsEntry("sampleIdentityId",101L);assertThatThrownBy(()->service.detail(number(d.get("id")))).hasMessage("样品或寄样包裹不存在或未获授权");
 }
 @Test void assignedMexicoSupplierLabelUsesTheTypeOriginInsteadOfTheParcelSupplier(){
  jdbc.update("update plm_partner_record set data='{\"supplierCode\":\"SUP-ORIGINAL\",\"supplierName\":\"原样品供应商\"}'::jsonb where record_id=101");var d=dispatch(draft(2));jdbc.update("update plm_partner_record set data=jsonb_set(data,'{supplierCode}','\"SUP-UNRELATED\"') where record_id=?",d.get("id"));role="mexico";actorId=2;
  var scan=warehouse.resolve("SY-000101");assertThat(map(scan.get("identity"))).containsEntry("supplierCode","SUP-ORIGINAL").containsEntry("supplierName","原样品供应商");assertThat(maps(service.detail(number(d.get("id"))).get("rows")).get(0)).containsEntry("supplierCode","SUP-ORIGINAL").containsEntry("supplierName","原样品供应商");assertThat(scan.toString()).doesNotContain("SUP-UNRELATED","sourceSnapshot");
 }
 @Test void assignedReceiptLocationsRemainParcelHistoryAfterARealMixedBatchMoveWithoutLeakingOtherStock(){
  var own=dispatch(draft(3));long ownId=number(own.get("id"));
  var other=run(()->service.create(data("requestId","other-person-parcel","direction","mexico_outbound","destination","墨西哥样品间","receiverUserId",3L,"rows",List.of(data("rowId","line-a","sampleIdentityId",101L,"qty",2L)))));long otherId=number(other.get("id"));var otherDraftVersion=other.get("version");other=run(()->service.action(otherId,"dispatched",data("requestId","dispatch-other-parcel","version",otherDraftVersion,"date","2026-01-01","tracking","QA-OTHER-TRACK")));
  var ownVersion=own.get("version");run(()->service.action(ownId,"receive",data("requestId","own-actual-receipt","version",ownVersion,"date","2026-01-02","location","墨西哥样品间 A柜／2层／3格","rows",List.of(data("rowId","line-a","goodQty",3L)))));
  var otherVersion=other.get("version");run(()->service.action(otherId,"receive",data("requestId","other-actual-receipt","version",otherVersion,"date","2026-01-02","location","他人包裹私有柜","rows",List.of(data("rowId","line-a","goodQty",2L)))));
  var sampleActions=new PartnerSampleService(store,mock(PartnerProductService.class),mock(PartnerMaterialService.class));var moved=run(()->sampleActions.action(201,"move",data("version",store.load(201,"sample",false).get("version"),"requestId","after-receipt-move","date","2026-01-03","location","墨西哥样品间 A柜／2层／3格","to","墨西哥样品间 B柜／1层／2格","qty",3L)));
  var realPositions=maps(map(moved.get("balances")).get("positions"));assertThat(realPositions).anySatisfy(position->assertThat(position).containsEntry("name","墨西哥样品间 B柜／1层／2格").containsEntry("onhand",3L));
  role="mexico";actorId=2;var assigned=service.detail(ownId);var row=maps(assigned.get("rows")).get(0);
  assertThat(maps(row.get("receiptLocations"))).singleElement().satisfies(receipt->assertThat(receipt).containsAllEntriesOf(Map.of("date","2026-01-02","location","墨西哥样品间 A柜／2层／3格","goodQty",3L,"damagedQty",0L,"site","MX")));
  assertThat(assigned.toString()).doesNotContain("他人包裹私有柜","深圳办公室","B柜","sourceSnapshot","batchId","positions");assertThat(row).containsEntry("inTransit",0L);assertThatThrownBy(()->service.detail(otherId)).hasMessage("样品或寄样包裹不存在或未获授权");
  var scan=warehouse.resolve("SY-000101");assertThat(maps(map(scan.get("identity")).get("positions"))).allSatisfy(p->assertThat(p).containsEntry("site","MX"));assertThat(maps(map(scan.get("identity")).get("positions"))).anySatisfy(p->assertThat(p).containsEntry("name","墨西哥样品间 B柜／1层／2格").containsEntry("onhand",3L));assertThat(maps(map(scan.get("identity")).get("positions"))).anySatisfy(p->assertThat(p).containsEntry("name","他人包裹私有柜").containsEntry("onhand",2L));assertThat(map(map(scan.get("identity")).get("balances"))).containsEntry("onhand",5L);assertThat(scan.toString()).doesNotContain("QA-OTHER-TRACK","QA-TRACK-1","receiverUserId","shipments","深圳办公室","sourceSnapshot","priceCents","customerName");
 }
 @Test void correctedReceiptsAreRemovedFromTheAssignedLocationHistoryAndInTransitIsNotAStoredLocation(){
  var parcel=dispatch(draft(3));long id=number(parcel.get("id"));role="mexico";actorId=2;assertThat(maps(service.detail(id).get("rows")).get(0).get("receiptLocations")).isEqualTo(List.of());
  var version=parcel.get("version");parcel=run(()->service.action(id,"receive",data("requestId","receipt-to-correct","version",version,"date","2026-01-02","location","错误签收位置","rows",List.of(data("rowId","line-a","goodQty",2L)))));
  role="administrator";actorId=1;long receipt=store.events(id).stream().filter(event->"sample_shipment_receive".equals(event.get("action"))).mapToLong(event->number(event.get("id"))).findFirst().orElseThrow();var receivedVersion=parcel.get("version");parcel=run(()->service.action(id,"undo",data("requestId","correct-location","version",receivedVersion,"date","2026-01-03","targetEventId",receipt,"reason","签收位置录错")));
  role="mexico";actorId=2;var scan=warehouse.resolve("SY-000101");assertThat(scan.toString()).doesNotContain("错误签收位置","shipments");var local=map(scan.get("identity"));assertThat(maps(local.get("positions"))).isEmpty();assertThat(map(local.get("balances"))).containsEntry("inTransit",3L).containsEntry("onhand",0L);var row=maps(service.detail(id).get("rows")).get(0);assertThat(maps(row.get("receiptLocations"))).isEmpty();assertThat(row).containsEntry("inTransit",3L);
 }

 @Test void draftLogisticsRemainEditableMetadataWithoutInventingActualDispatchFacts(){
  var d=run(()->service.create(data("requestId","draft-logistics","direction","mexico_outbound","destination","墨西哥办公室","dispatch",Map.of("tracking"," DRAFT-TRACK ","carrier","承运商","date","2026-01-01"),"rows",List.of(data("rowId","line-a","sampleIdentityId",101L,"qty",2L)))));
  long id=number(d.get("id"));assertThat(d).containsEntry("status","draft");assertThat(map(d.get("dispatch"))).containsExactlyInAnyOrderEntriesOf(Map.of("tracking","DRAFT-TRACK","carrier","承运商"));assertThat(aggregate("inTransit")).isZero();assertThat(aggregate("available")).isEqualTo(10);
  var edited=new LinkedHashMap<String,Object>(d);edited.put("requestId","edit-draft-logistics");edited.put("dispatch",Map.of("tracking","","carrier",""));d=run(()->service.save(id,edited));assertThat(map(d.get("dispatch"))).containsEntry("tracking","").doesNotContainKey("date");assertThat(maps(d.get("rows")).get(0)).containsEntry("qty",2);
  var legacy=new LinkedHashMap<String,Object>(d);legacy.remove("dispatch");legacy.put("requestId","legacy-draft-save");d=run(()->service.save(id,legacy));assertThat(map(d.get("dispatch"))).containsEntry("tracking","");assertThat(d).containsEntry("status","draft");assertThat(aggregate("available")).isEqualTo(10);
  assertThat(store.events(id).stream().map(event->text(event.get("action")))).containsExactly("sample_shipment_create","sample_shipment_save","sample_shipment_save");
 }
 @Test void blankTrackingDispatchAndLaterTrackingAtEverySentStateNeverWithdrawStockAgain(){
  var d=draft(3);long id=number(d.get("id"));var version=d.get("version");
  d=run(()->service.action(id,"dispatched",data("requestId","dispatch-without-number","version",version,"date","2026-01-01","tracking","")));
  assertThat(d).containsEntry("status","dispatched");assertThat(map(d.get("dispatch"))).containsEntry("tracking","").containsEntry("date","2026-01-01");assertThat(aggregate("available")).isEqualTo(7);assertThat(aggregate("inTransit")).isEqualTo(3);
  for(int step=0;step<3;step++){
   long available=aggregate("available"),inTransit=aggregate("inTransit"),ledgerEvents=jdbc.queryForObject("select count(*) from plm_partner_event where record_id in (201,202)",Long.class);
   var update=data("requestId","tracking-"+step,"version",d.get("version"),"date","2026-01-02","tracking","CONFIRMED-"+step,"carrier","承运商","note","人工核实补填运单");
   d=run(()->service.action(id,"tracking",update));run(()->service.action(id,"tracking",update));
   assertThat(map(d.get("dispatch"))).containsEntry("tracking","CONFIRMED-"+step).containsEntry("date","2026-01-01");assertThat(aggregate("available")).isEqualTo(available);assertThat(aggregate("inTransit")).isEqualTo(inTransit);assertThat(jdbc.queryForObject("select count(*) from plm_partner_event where record_id in (201,202)",Long.class)).isEqualTo(ledgerEvents);
   if(step<2){var receive=data("requestId","receive-tracking-stage-"+step,"version",d.get("version"),"date","2026-01-02","location","墨西哥办公室","rows",List.of(data("rowId","line-a","goodQty",step==0?1L:2L)));d=run(()->service.action(id,"receive",receive));assertThat(d).containsEntry("status",step==0?"partially_received":"received");}
  }
  assertThat(store.events(id).stream().filter(event->"sample_shipment_tracking".equals(event.get("action")))).hasSize(3).allSatisfy(event->{assertThat(event).containsEntry("operator","国内工程");assertThat(map(event.get("data"))).containsEntry("reason","人工核实补填运单");});
  verify(store.audit,atLeastOnce()).logRequired(any(),any());assertThat(aggregate("inTransit")).isZero();
 }
 @Test void optionalTrackingDoesNotRelaxActualDateQuantityOrAvailableStockGates(){
  var d=draft(3);long id=number(d.get("id"));var version=d.get("version");
  assertThatThrownBy(()->run(()->service.action(id,"dispatched",data("requestId","no-date","version",version,"tracking","")))).hasMessageContaining("日期");
  assertThatThrownBy(()->run(()->service.action(id,"dispatched",data("requestId","zero-actual","version",version,"date","2026-01-01","tracking","","rows",List.of(data("rowId","line-a","sampleIdentityId",101L,"qty",0L)))))).hasMessageContaining("数量");
  assertThatThrownBy(()->run(()->service.action(id,"dispatched",data("requestId","not-enough","version",version,"date","2026-01-01","tracking","","rows",List.of(data("rowId","line-a","sampleIdentityId",101L,"qty",11L)))))).hasMessageContaining("实际可用样品不足");
  assertThat(service.detail(id)).containsEntry("status","draft");assertThat(aggregate("inTransit")).isZero();assertThat(aggregate("available")).isEqualTo(10);
 }

}
