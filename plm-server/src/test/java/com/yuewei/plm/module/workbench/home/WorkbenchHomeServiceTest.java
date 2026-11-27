package com.yuewei.plm.module.workbench.home;

import com.yuewei.plm.common.exception.BusinessException;
import com.yuewei.plm.module.pilot.PilotAccessService;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.yuewei.plm.module.workbench.home.WorkbenchHomeModels.*;

class WorkbenchHomeServiceTest {
    PilotAccessService access=mock(PilotAccessService.class);
    ResearchHomeQuery research=mock(ResearchHomeQuery.class);
    ScheduleHomeSource schedule=mock(ScheduleHomeSource.class);
    ApprovalHomeQuery generic=mock(ApprovalHomeQuery.class);
    MoldApprovalHomeSource molds=mock(MoldApprovalHomeSource.class);
    WorkbenchHomeService service=new WorkbenchHomeService(access,research,schedule,generic,molds);

    @Test void dashboardPermissionIsRequiredBeforeSourceReads() {
        doThrow(new BusinessException(40103,"denied")).when(access).requirePermission("dashboard:view");
        assertThatThrownBy(()->service.summary()).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->service.projects(new Query())).isInstanceOf(BusinessException.class);
        verifyNoInteractions(research,schedule,generic,molds);
    }
    @Test void restrictedSourceNeverReadsOrManufacturesZero() {
        var value=service.projects(new Query());
        assertThat(value.sourceState()).isEqualTo("restricted");assertThat(value.totalElements()).isNull();
        verifyNoInteractions(research);
    }
    @Test void failureIsDifferentFromARealEmptyBusinessResult() {
        when(access.hasPermission(anyString())).thenReturn(true);
        when(research.page(any(),eq(false))).thenThrow(new IllegalStateException("offline"));
        assertThat(service.projects(new Query()).sourceState()).isEqualTo("error");
        assertThat(service.projects(new Query()).totalElements()).isNull();
        doReturn(WorkbenchHomeModels.page("research",new Query(),List.of(),0,null)).when(research).page(any(),eq(false));
        assertThat(service.projects(new Query()).totalElements()).isZero();
    }
    @Test void sourceWhitelistRejectsAnArbitrarySource() {
        var q=new Query();q.setSource("other-user");
        assertThatThrownBy(()->service.tasks(q)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->service.approvals(q)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->service.exceptions(q)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(research,schedule,generic,molds);
    }
    @Test void perSourcePermissionDenialDoesNotBreakOtherSummarySources() {
        when(access.hasPermission("schedule:read")).thenReturn(true);
        when(schedule.page(any(),anyBoolean())).thenAnswer(i->WorkbenchHomeModels.page("schedule",i.getArgument(0),List.of(),25,null));
        var summary=service.summary();
        assertThat(summary.get("tasks").get(0).total()).isEqualTo(25);
        assertThat(summary.get("projects").get(0).state()).isEqualTo("restricted");
        assertThat(summary.get("approvals")).allSatisfy(c->assertThat(c.total()).isNull());
        verifyNoInteractions(research,generic,molds);
    }
}
