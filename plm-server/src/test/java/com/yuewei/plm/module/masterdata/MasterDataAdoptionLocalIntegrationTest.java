package com.yuewei.plm.module.masterdata;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuewei.plm.common.security.*;
import com.yuewei.plm.module.inventory.maintenance.*;
import com.yuewei.plm.module.operationlog.service.OperationLogService;
import com.yuewei.plm.module.pilot.PilotAccessService;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
/** Root integration check: real existing master, BOM writer and lifecycle share the same row lock. */
@EnabledIfEnvironmentVariable(named="PLM_FOUNDATION_TEST_DB_URL",matches="jdbc:postgresql://127\\.0\\.0\\.1:15483/plm_foundation_crud_it\\?currentSchema=plm")
class MasterDataAdoptionLocalIntegrationTest {
 JdbcTemplate jdbc;TransactionTemplate tx;CurrentUser admin;InventoryMaintenanceService service;
 @BeforeEach void setup(){
  var ds=new DriverManagerDataSource(System.getenv("PLM_FOUNDATION_TEST_DB_URL"),"postgres",System.getenv("PLM_FOUNDATION_TEST_DB_PASSWORD"));
  jdbc=new JdbcTemplate(ds);assertThat(jdbc.queryForObject("select current_database()",String.class)).isEqualTo("plm_foundation_crud_it");tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
  var mapper=new ObjectMapper().findAndRegisterModules();long uid=jdbc.queryForObject("select user_id from sys_user where deleted_flag=0 and all_permissions=true order by user_id limit 1",Long.class);admin=new CurrentUser(uid,"root-it","仅隔离验收",true);CurrentUserContext.set(admin);
  var access=new PilotAccessService(jdbc,mapper);service=new InventoryMaintenanceService(jdbc,mapper,access,mock(OperationLogService.class),new FoundationObjectReferences(jdbc,mapper,access));
 }
 @AfterEach void clear(){CurrentUserContext.clear();}
 long inventory(){return jdbc.queryForObject("insert into plm_inventory(inventory_code,inventory_name,inventory_type,stock_uom,quantity,status,created_by,updated_by) values(?,'仅隔离验证','material','个',0,'available','foundation-it','foundation-it') returning inventory_id",Long.class,"IT-ADOPT-"+UUID.randomUUID());}
 @Test void stoppedMasterKeepsCurrentRowCorrectionButRejectsNewUseAndSourceRecreation(){tx.execute(t->{long id=inventory();String code=jdbc.queryForObject("select inventory_code from plm_inventory where inventory_id=?",String.class,id);MasterDataAdoption.requireInventory(jdbc,id,code,null,null);jdbc.update("update plm_inventory set lifecycle_status='inactive' where inventory_id=?",id);
  MasterDataAdoption.requireInventory(jdbc,id,code,id,code);MasterDataAdoption.requireInventory(jdbc,null,code,null,code);
  assertThatThrownBy(()->MasterDataAdoption.requireInventory(jdbc,id,code,null,null)).hasMessageContaining("已停用");assertThatThrownBy(()->MasterDataAdoption.requireInventory(jdbc,null,code,null,null)).hasMessageContaining("已停用");
  assertThatThrownBy(()->MasterDataAdoption.requireSourceObject(jdbc,"plm_inventory","inventory_id",id)).hasMessageContaining("已停用");
  jdbc.update("update plm_inventory set deleted_flag=1 where inventory_id=?",id);assertThatThrownBy(()->MasterDataAdoption.requireInventory(jdbc,id,code,id,code)).hasMessageContaining("已删除");assertThatThrownBy(()->MasterDataAdoption.requireSourceObject(jdbc,"plm_inventory","inventory_id",id)).hasMessageContaining("已删除");
  MasterDataAdoption.requireInventory(jdbc,null,"ERP-unknown-"+UUID.randomUUID(),null,null);t.setRollbackOnly();return null;});}
 @ParameterizedTest @ValueSource(booleans={true,false}) void actualBomAdoptionAndUnusedDeleteSerialize(boolean writerFirst)throws Exception{
  long inv=tx.execute(t->inventory());long product=tx.execute(t->jdbc.queryForObject("insert into plm_product(product_code,product_name,product_type,version_no,created_by,updated_by) values(?,'仅隔离采用','product_line','V1','foundation-it','foundation-it') returning product_id",Long.class,"IT-ADOPT-"+UUID.randomUUID()));
  long bom=tx.execute(t->jdbc.queryForObject("insert into plm_product_bom(product_id,bom_code,bom_name,bom_type,version_no,created_by,updated_by) values(?,?,'仅隔离BOM','ebom','V1','foundation-it','foundation-it') returning product_bom_id",Long.class,product,"IT-BOM-"+UUID.randomUUID()));
  var held=new CountDownLatch(1);var release=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
  try{
   Callable<Object> writer=()->{CurrentUserContext.set(admin);try{return tx.execute(t->{MasterDataAdoption.requireInventory(jdbc,inv,null,null,null);if(writerFirst){held.countDown();await(release);}jdbc.update("insert into plm_product_bom_item(product_bom_id,product_id,inventory_id,line_no,item_name,quantity,unit,version_no,created_by,updated_by) values(?,?,?,1,'仅隔离物料',1,'个','V1','foundation-it','foundation-it')",bom,product,inv);return true;});}finally{CurrentUserContext.clear();}};
   Callable<Object> remover=()->{CurrentUserContext.set(admin);try{return tx.execute(t->{var result=service.lifecycle(inv,false,"delete",Map.of("version",0,"requestId",UUID.randomUUID().toString(),"reason","仅隔离并发删除验收"));if(!writerFirst){held.countDown();await(release);}return result;});}finally{CurrentUserContext.clear();}};
   Future<?> first=pool.submit(writerFirst?writer:remover);assertThat(held.await(8,TimeUnit.SECONDS)).isTrue();Future<?> second=pool.submit(writerFirst?remover:writer);Thread.sleep(150);assertThat(second.isDone()).isFalse();release.countDown();first.get(15,TimeUnit.SECONDS);assertThatThrownBy(()->second.get(15,TimeUnit.SECONDS)).hasCauseInstanceOf(com.yuewei.plm.common.exception.BusinessException.class);
   assertThat(jdbc.queryForObject("select count(*) from plm_product_bom_item where product_bom_id=?",Long.class,bom)).isEqualTo(writerFirst?1:0);
   assertThat(jdbc.queryForObject("select deleted_flag from plm_inventory where inventory_id=?",Integer.class,inv)).isEqualTo(writerFirst?0:1);
  }finally{release.countDown();pool.shutdownNow();assertThat(pool.awaitTermination(15,TimeUnit.SECONDS)).isTrue();CurrentUserContext.set(admin);tx.execute(t->{jdbc.update("delete from plm_product_bom_item where product_bom_id=?",bom);jdbc.update("delete from plm_product_bom where product_bom_id=?",bom);jdbc.update("delete from plm_product where product_id=?",product);jdbc.update("delete from plm_inventory where inventory_id=?",inv);return null;});}
 }
 static void await(CountDownLatch latch){try{if(!latch.await(10,TimeUnit.SECONDS))throw new IllegalStateException("Test latch timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}
}
