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
import org.junit.jupiter.api.condition.EnabledIf;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Dedicated local QA database only; every test owns a random schema, never the application schema. */
@EnabledIf("com.yuewei.plm.module.moldsales.MoldSalesLocalFixture#databaseConfigured")
class MoldActualCostLocalIntegrationTest extends MoldSalesLocalFixture {
    @Test void savesFourFeesAndGroupTotalWithVersionHistoryAndAuditWithoutChangingBudget(){
        String original=jdbc.queryForObject("select costs::text from plm_mold_sales_snapshot",String.class);var before=service.cost(101);assertThat(before).containsEntry("version",0).containsEntry("actual",null);
        var saved=service.updateCost(101,request(before,amounts("1000")));assertThat(saved).containsEntry("version",1);assertThat(actualOf(saved)).containsEntry("totalCost",new BigDecimal("1125.00"));
        assertThat((List<?>)saved.get("history")).hasSize(1);assertThat(jdbc.queryForObject("select costs::text from plm_mold_sales_snapshot",String.class)).isEqualTo(original);
        var displayed=costOf(first("sales","all"));assertThat(displayed).containsEntry("source","actual").containsEntry("totalCost",new BigDecimal("1125.00")).containsEntry("budgetTotalCost",new BigDecimal("2000")).containsEntry("perUnitCost",new BigDecimal("11.2500"));
        var log=ArgumentCaptor.forClass(OperationLogCreateCommand.class);verify(audit).logRequired(log.capture(),eq(actor));assertThat(log.getValue().getAction()).isEqualTo("MOLD_GROUP_ACTUAL_COST_UPDATE");assertThat(log.getValue().getDetailJson()).contains("\"financialLedgerFact\":false");
    }
    @Test void repeatedSaveOfSameAmountsDoesNotCreateVersionOrDoubleCost(){
        var saved=service.updateCost(101,request(service.cost(101),amounts("1000")));var again=service.updateCost(102,request(saved,amounts("1000")));assertThat(again).containsEntry("version",1);assertThat((List<?>)again.get("history")).hasSize(1);assertThat(jdbc.queryForObject("select count(*) from plm_mold_group_actual_cost",Long.class)).isEqualTo(1);verify(audit,times(1)).logRequired(any(),eq(actor));
    }
    @Test void zeroActualOverridesBudgetAndMissingBudgetDoesNotBlockManualMaintenance(){
        jdbc.update("update plm_mold_sales_snapshot set costs='{\"rows\":[]}'");var zero=new MoldActualCostStore.Amounts(BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO);var saved=service.updateCost(101,request(service.cost(101),zero));assertThat(saved.get("budget")).isNull();assertThat(costOf(first("sales","all"))).containsEntry("totalCost",new BigDecimal("0.00")).containsEntry("breakEvenUnits",null);assertThat(first("sales","missingCost")).isNull();
    }
    @Test void staleVersionOrGroupTokenNeverOverwritesExistingActual(){
        var before=service.cost(101);service.updateCost(101,request(before,amounts("1000")));assertThatThrownBy(()->service.updateCost(101,request(before,amounts("2000")))).hasMessageContaining("其他人修改");
        var current=service.cost(101);var wrong=new MoldActualCostStore.Update(1L,"wrong",amounts("3000"),"旧组");assertThatThrownBy(()->service.updateCost(101,wrong)).hasMessageContaining("共模组");assertThat(((Map<?,?>)service.cost(101).get("actual")).get("totalCost")).isEqualTo(new BigDecimal("1125.00"));
    }
    @Test void concurrentFirstInsertHasOneWinnerAndOneVersionConflict()throws Exception{
        var request=request(service.cost(101),amounts("1000"));var pool=Executors.newFixedThreadPool(2);var ready=new CountDownLatch(2);var start=new CountDownLatch(1);var saved=new AtomicInteger();var conflicts=new AtomicInteger();
        try{var tasks=new ArrayList<Future<?>>();for(int i=0;i<2;i++)tasks.add(pool.submit(()->{ready.countDown();try{start.await();service.updateCost(101,request);saved.incrementAndGet();}catch(BusinessException ex){assertThat(ex.getMessage()).contains("其他人修改");conflicts.incrementAndGet();}catch(InterruptedException ex){Thread.currentThread().interrupt();throw new IllegalStateException(ex);}}));assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();start.countDown();for(var task:tasks)task.get(30,TimeUnit.SECONDS);}finally{pool.shutdownNow();}
        assertThat(saved.get()).isEqualTo(1);assertThat(conflicts.get()).isEqualTo(1);assertThat(jdbc.queryForObject("select count(*) from plm_mold_group_actual_cost_history",Long.class)).isEqualTo(1);
    }
    @Test void anExistingBusinessWriterIsNotOverriddenAndCostLockWaitIsBounded()throws Exception{
        var request=request(service.cost(101),amounts("1000"));var pool=Executors.newSingleThreadExecutor();var ready=new CountDownLatch(1);var release=new CountDownLatch(1);
        try{var task=pool.submit(()->new org.springframework.transaction.support.TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource()))).execute(ignored->{jdbc.update("update plm_collaboration_item set style_name=style_name where item_id=101");ready.countDown();try{if(!release.await(15,TimeUnit.SECONDS))throw new IllegalStateException("QA lock release timed out");}catch(InterruptedException ex){Thread.currentThread().interrupt();throw new IllegalStateException(ex);}return null;}));
            assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();long started=System.nanoTime();assertThatThrownBy(()->service.updateCost(101,request)).isInstanceOf(BusinessException.class).hasMessageContaining("相关资料正在修改");assertThat(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime()-started)).isLessThan(8);release.countDown();task.get(10,TimeUnit.SECONDS);
            assertThat(jdbc.queryForObject("select count(*) from plm_mold_group_actual_cost",Long.class)).isZero();assertThat(jdbc.queryForObject("select count(*) from plm_mold_group_actual_cost_history",Long.class)).isZero();verifyNoInteractions(audit);
        }finally{release.countDown();pool.shutdownNow();}
    }
    @Test void financePermissionsAndEverySharedMemberAreRequiredWithoutAmountLeakage(){
        var request=request(service.cost(101),amounts("1000"));financeWrite=false;assertThatThrownBy(()->service.updateCost(101,request)).hasMessageContaining("finance:write");financeWrite=true;service.updateCost(101,request);
        financeRead=false;assertThatThrownBy(()->service.cost(101)).hasMessageContaining("finance:read");assertThat(first("sales","all")).doesNotContainKey("cost");financeRead=true;
        visible.remove(102L);assertThatThrownBy(()->service.cost(101)).hasMessageContaining("授权");assertThatThrownBy(()->service.updateCost(101,request)).hasMessageContaining("授权");var row=first("sales","all");assertThat(row).doesNotContainKey("cost").containsEntry("canWriteCost",false);
    }
    @Test void productionViewCannotSerialiseActualBudgetOrHistoryAmounts(){
        service.updateCost(101,request(service.cost(101),amounts("1234.56")));String result=json.valueToTree(service.page(1,20,"","all",null,null,"production")).toString();assertThat(result).doesNotContain("manufacturingCost","freightCost","totalCost","budgetTotalCost","1234.56");
    }
    @Test void mandatoryAuditFailureRollsBackCurrentValueAndHistory(){
        service.updateCost(101,request(service.cost(101),amounts("1000")));doThrow(new IllegalStateException("audit unavailable")).when(audit).logRequired(any(),eq(actor));var before=service.cost(101);assertThatThrownBy(()->service.updateCost(101,request(before,amounts("2000")))).hasMessageContaining("audit unavailable");assertThat(service.cost(101)).containsEntry("version",1);assertThat(jdbc.queryForObject("select total_cost from plm_mold_group_actual_cost",BigDecimal.class)).isEqualTo(new BigDecimal("1125.00"));assertThat(jdbc.queryForObject("select count(*) from plm_mold_group_actual_cost_history",Long.class)).isEqualTo(1);
    }
    @Test void changedSharedMembershipOrPhysicalSetsRequireExplicitReview(){
        service.updateCost(101,request(service.cost(101),amounts("1000")));jdbc.update("update plm_collaboration_mold_part set physical_status='making' where mold_part_id=4");assertThatThrownBy(()->service.cost(101)).hasMessageContaining("先核对原成本记录");assertThat(first("sales","all")).containsEntry("costConflict",true);assertThat(jdbc.queryForObject("select total_cost from plm_mold_group_actual_cost",BigDecimal.class)).isEqualTo(new BigDecimal("1125.00"));
    }
    @Test void mergingTwoPreviouslyMaintainedGroupsNeverSumsOrChoosesOneActual(){
        jdbc.update("update plm_collaboration_item set phone_model_code_item_id=null,phone_model_code='0058' where item_id=102");service.updateCost(101,request(service.cost(101),amounts("1000")));service.updateCost(102,request(service.cost(102),amounts("2000")));jdbc.update("update plm_collaboration_item set phone_model_code_item_id=1,phone_model_code=null where item_id=102");assertThatThrownBy(()->service.cost(101)).hasMessageContaining("多份实际成本");assertThat(first("sales","all")).containsEntry("costConflict",true);assertThat(jdbc.queryForObject("select count(*) from plm_mold_group_actual_cost",Long.class)).isEqualTo(2);
    }
    @Test void crmSnapshotRefreshPreservesManualCostsAndRecomputesOnlyQuantity()throws Exception{
        service.updateCost(101,request(service.cost(101),amounts("1000")));var directory=Files.createTempDirectory("mold-cost-source-qa-");Path source=directory.resolve("crm-sales-qa-refresh.jsonl.gz"),costs=directory.resolve("cost-baseline.json");
        try{Files.writeString(costs,budgetJson());String header="{\"kind\":\"header\",\"format\":1,\"organizations\":1,\"organization\":\"QA\",\"rows\":1,\"capturedAt\":\"2026-10-26T00:00:00Z\",\"periodStart\":\"2025-01-01\",\"periodEnd\":\"2026-10-26\"}";
            String line="{\"kind\":\"line\",\"businessDate\":\"2026-10-20\",\"id\":\"qa-refresh-line\",\"orderId\":\"qa-order\",\"orderNo\":\"QA\",\"status\":\"closed\",\"code\":\"NFA4030005701\",\"quantity\":\"200\"}";
            try(var out=new GZIPOutputStream(Files.newOutputStream(source))){out.write((header+"\n"+line+"\n{\"kind\":\"footer\",\"complete\":true,\"rows\":1}\n").getBytes(StandardCharsets.UTF_8));}
            service.importSnapshot(source,costs,actor);assertThat(service.cost(101)).containsEntry("version",1);assertThat(costOf(first("sales","all"))).containsEntry("totalCost",new BigDecimal("1125.00")).containsEntry("perUnitCost",new BigDecimal("5.6250"));
        }finally{Files.deleteIfExists(source);Files.deleteIfExists(costs);Files.deleteIfExists(directory);}
    }
}
