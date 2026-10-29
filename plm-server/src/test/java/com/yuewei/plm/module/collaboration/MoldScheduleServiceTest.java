package com.yuewei.plm.module.collaboration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuewei.plm.common.exception.BusinessException;
import com.yuewei.plm.common.security.CurrentUser;
import com.yuewei.plm.common.vo.PageVO;
import com.yuewei.plm.module.pilot.PilotAccessService;
import com.yuewei.plm.module.operationlog.service.OperationLogService;
import java.time.LocalDate;
import java.sql.ResultSet;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.AdditionalMatchers.aryEq;
import static com.yuewei.plm.module.collaboration.CollaborationTypes.*;

class MoldScheduleServiceTest {
    ObjectMapper json=new ObjectMapper();JdbcTemplate jdbc=mock(JdbcTemplate.class);CollaborationService collaboration=mock(CollaborationService.class);CollaborationRepository repository=mock(CollaborationRepository.class);PilotAccessService access=mock(PilotAccessService.class);
    MoldScheduleService service=new MoldScheduleService(jdbc,json,collaboration,repository,access,mock(PlatformTransactionManager.class));
    @Test void pageRequiresDomesticReadAndPassesTrustedScopeToTheExistingPageEngine() {
        var actor=new CurrentUser(1L,"real","real",false);when(collaboration.actor()).thenReturn(actor);when(collaboration.groups(actor)).thenReturn(Set.of("engineering"));when(access.visibleSqlClause("mold","s.mold_set_id")).thenReturn("s.mold_set_id=7");when(repository.page(any(),eq(Set.of("engineering")),eq("s.mold_set_id=7"))).thenReturn(new PageVO<>(List.of(),1,50,0,0));
        assertThat(service.page(new MoldScheduleTypes.Query()).getContent()).isEmpty();verify(access).requireDomesticView();verify(access).requirePermission("schedule:read");verifyNoInteractions(jdbc);
    }
    @Test void externalAccountIsRejectedBeforeAnyFullLedgerRead() {
        doThrow(new BusinessException(40103,"仅手机任务")).when(access).requireDomesticView();assertThatThrownBy(()->service.page(new MoldScheduleTypes.Query())).hasMessageContaining("手机任务");verifyNoInteractions(repository,jdbc);
    }
    @Test void fiftySetsReadDevelopmentAndPlanSnapshotsInTwoBulkQueries() {
        var rows=new ArrayList<Map<String,Object>>();for(long id=1;id<=50;id++)rows.add(new LinkedHashMap<>(Map.of("moldSetId",id)));
        when(repository.moldRows(anyList(),any())).thenReturn(rows);
        assertThat(service.projectRows(java.util.stream.LongStream.rangeClosed(1,50).boxed().toList())).hasSize(50);
        verify(jdbc).queryForList(contains("development_data::text"),any(Object[].class));
        verify(jdbc).queryForList(contains("plan_snapshot::text"),any(Object[].class));
        verify(jdbc,never()).queryForObject(anyString(),eq(String.class),any(Object[].class));
    }
    @Test void plannedSetShowsItsCurrentSourcePhoneWithoutInventingCompatibility() throws Exception {
        var rs=mock(ResultSet.class);when(rs.getLong("application_id")).thenReturn(17L);when(rs.getLong("mold_set_id")).thenReturn(7L);
        when(rs.getString("provisioning_status")).thenReturn("ready");when(rs.getString("execution_intent")).thenReturn("new_set");
        when(rs.getObject("phone_model_id")).thenReturn(3L);when(rs.getObject("brand_id")).thenReturn(1L);
        when(rs.getObject("brand_name")).thenReturn("Apple");when(rs.getObject("model_name")).thenReturn("iPhone 18 Pro updated");
        when(rs.getObject("phone_model_code")).thenReturn("0003");when(rs.getObject("code_item_id")).thenReturn(4L);
        doAnswer(invocation->{((org.springframework.jdbc.core.RowCallbackHandler)invocation.getArgument(1)).processRow(rs);return null;})
            .when(jdbc).query(anyString(),any(org.springframework.jdbc.core.RowCallbackHandler.class),any(Object[].class));
        var row=new LinkedHashMap<String,Object>();row.put("moldSetId",7L);
        new MoldWorkflowRepository(jdbc).enrich(List.of(row));
        @SuppressWarnings("unchecked") var links=(List<Map<String,Object>>)row.get("applicationLinks");
        assertThat(links).singleElement().satisfies(link->{
            @SuppressWarnings("unchecked") var phone=(Map<String,Object>)link.get("standardPhone");
            assertThat(phone).containsEntry("phoneModelId",3L).containsEntry("modelName","iPhone 18 Pro updated").containsEntry("phoneModelCode","0003");
        });
        assertThat(row).containsEntry("applicationPlanned",true).doesNotContainKey("compatiblePhones");
        verify(jdbc).query(contains("requested.id=a.phone_model_id"),any(org.springframework.jdbc.core.RowCallbackHandler.class),aryEq(new Object[]{7L}));
        verifyNoMoreInteractions(jdbc);
    }
    @Test void saveReusesTheSingleOriginalValueConflictAndAuditEngine() {
        var patch=new RowPatch("mold_set",7,List.of(new CellChange("forecastFactoryDate",json.nullNode(),json.valueToTree("2026-10-20"),"预测更新")));
        var dto=new BatchSave(List.of(patch));var result=new RowResult("mold_set",7,"conflict","原值冲突",List.of(),null);when(collaboration.save(dto,null)).thenReturn(List.of(result));assertThat(service.save(dto,null)).containsExactly(result);verify(access).requireMold(7);verify(collaboration).save(dto,null);verifyNoInteractions(jdbc);
    }
    @Test void mobileActionAllowlistRejectsWrongColumnsAndKeepsArrivalSeparateFromShipping() {
        var bad=new RowPatch("mold_set",7,List.of(new CellChange("plannedMoldDeliveryDate",json.nullNode(),json.valueToTree("2026-10-20"),null)));assertThatThrownBy(()->service.saveScopedTask("arrival",bad,null)).isInstanceOf(BusinessException.class).hasMessageContaining("未授权字段");verify(collaboration,never()).save(any(),any());
        var good=new RowPatch("mold_set",7,List.of(new CellChange("pcSignedDate",json.nullNode(),json.valueToTree("2026-10-20"),null)));when(collaboration.save(any(),any())).thenReturn(List.of(new RowResult("mold_set",7,"saved","saved",List.of(),null)));assertThat(service.saveScopedTask("arrival",good,null).status()).isEqualTo("saved");verify(access,times(2)).requirePermission("task:arrival");
        var country=new RowPatch("mold_set",7,List.of(new CellChange("actualMexicoArrivalDate",json.nullNode(),json.valueToTree("2026-10-20"),null)));
        assertThatThrownBy(()->service.saveScopedTask("ship",country,null)).isInstanceOf(BusinessException.class).hasMessageContaining("未授权字段");
    }
    @Test void trustedAutomaticReminderGenerationDoesNotFabricateALoginOrInvokeExternalDelivery() {
        when(jdbc.queryForObject(anyString(),eq(Long.class),any(Object[].class))).thenReturn(0L);
        assertThat(service.enqueueAutomaticReminders()).containsEntry("created",0).containsEntry("externalSent",0).containsEntry("externalEnabled",false);
        verify(jdbc).queryForObject(contains("t.category=?"),eq(Long.class),aryEq(new Object[]{"milestone"}));
        verifyNoInteractions(access,collaboration,repository);
    }
    @Test void moldLedgerAndScheduleShareOverdueMetadataWithoutPerRowDatabaseReads() {
        LocalDate today=MoldSchedulePolicy.today();Map<String,Object> row=new LinkedHashMap<>();
        row.put("moldSetId",7L);row.put("assetNumber","MS-000007");row.put("styleName","MB冰盾");row.put("compatibleModels","Apple iPhone");row.put("plannedMoldDeliveryDate",today.minusDays(2).toString());row.put("pcStatus","making");row.put("tpuStatus","making");
        var ledger=spy(new CollaborationService(jdbc,json,repository,mock(PlatformTransactionManager.class),mock(OperationLogService.class),access));
        var actor=new CurrentUser(1L,"real","real",true);doReturn(actor).when(ledger).actor();
        when(access.visibleSqlClause("mold","s.mold_set_id")).thenReturn("true");
        var another=new LinkedHashMap<>(row);another.put("moldSetId",8L);another.put("assetNumber","MS-000008");
        when(repository.page(any(),anySet(),eq("true"))).thenReturn(new PageVO<>(List.of(row,another),1,50,2,1));when(repository.row("mold_set",7)).thenReturn(row);
        var page=ledger.moldPage(new Query());var detail=ledger.mold(7);
        assertThat(page.getContent().get(0).get("overdueTasks")).isEqualTo(detail.get("overdueTasks"));
        assertThat(row.get("tasks")).isEqualTo(MoldSchedulePolicy.tasksFor(row,today));
        @SuppressWarnings("unchecked") var overdue=(List<MoldScheduleTypes.Task>)row.get("overdueTasks");
        assertThat(overdue).extracting(MoldScheduleTypes.Task::taskKey).containsExactly("mold_complete","ship");
        verify(repository).page(any(),anySet(),eq("true"));verify(repository).row("mold_set",7);
        verify(jdbc).query(contains("a.mold_set_id in (?,?)"),any(org.springframework.jdbc.core.RowCallbackHandler.class),aryEq(new Object[]{7L,8L}));
        verify(jdbc).query(contains("a.mold_set_id in (?)"),any(org.springframework.jdbc.core.RowCallbackHandler.class),aryEq(new Object[]{7L}));verifyNoMoreInteractions(jdbc);
    }
    @Test void allTaskCategoriesReuseScopedSqlPaginationAndReturnThePolicyFieldContract() throws Exception {
        var actor=new CurrentUser(3L,"real","real",false);when(collaboration.actor()).thenReturn(actor);when(collaboration.groups(actor)).thenReturn(Set.of("logistics"));when(access.visibleSqlClause("mold","s.mold_set_id")).thenReturn("s.mold_set_id=7");
        when(jdbc.queryForObject(anyString(),eq(Long.class),any(Object[].class))).thenReturn(1L);
        var rs=mock(ResultSet.class);when(rs.getString("task_key")).thenReturn("factory_plan");when(rs.getLong("mold_set_id")).thenReturn(7L);when(rs.getLong("item_id")).thenReturn(9L);
        when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class))).thenAnswer(invocation->List.of(((RowMapper<?>)invocation.getArgument(1)).mapRow(rs,0)));
        var q=new MoldScheduleTypes.Query();q.setCategory("all");q.setItemId(9L);q.setKeyword("计划");q.setMineOnly(true);q.setPage(2);q.setSize(20);
        var result=service.tasks(q);assertThat(result.getContent()).singleElement().satisfies(task->{assertThat(task.category()).isEqualTo("missing");assertThat(task.inputFields()).containsExactly("plannedFactoryArrivalDate");assertThat(task.supportFields()).contains("logisticsOwnerUserId");assertThat(task.itemId()).isEqualTo(9L);});
        var sql=ArgumentCaptor.forClass(String.class);var arguments=ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).query(sql.capture(),any(RowMapper.class),arguments.capture());
        assertThat(sql.getValue()).contains("coalesce(t.activated,false)","s.mold_set_id=7","i.item_id=?","t.owner_id=?","t.task_group in ('logistics')","coalesce(task_platform.product_name,i.style_name) style_name","coalesce(current_phones.current_models,case","current_phones.mold_set_id=s.mold_set_id","plm_collaboration_phone_set_compatibility","来源申请，适配待确认","待核对：","task_phone.code_item_id=i.phone_model_code_item_id","task_platform.product_name,task_phone.code_name,current_phones.current_models,t.title","order by overdue desc,t.due_date asc nulls last,s.mold_set_id,t.task_key limit ? offset ?").doesNotContain("t.category=?","task_phone.status='enabled'");
        assertThat(arguments.getValue()).containsExactly(9L,"%计划%",3L,20,20L);verifyNoInteractions(repository);
        when(collaboration.options()).thenReturn(Map.of());assertThat(service.options()).containsEntry("taskDefinitions",MoldSchedulePolicy.definitions());
    }
}
