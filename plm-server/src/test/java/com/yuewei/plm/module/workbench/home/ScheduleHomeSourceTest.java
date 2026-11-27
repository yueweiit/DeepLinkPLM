package com.yuewei.plm.module.workbench.home;

import com.yuewei.plm.common.vo.PageVO;
import com.yuewei.plm.module.collaboration.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.yuewei.plm.module.workbench.home.WorkbenchHomeModels.*;

class ScheduleHomeSourceTest {
    @Test void keepsTheCanonicalPersonalAndGroupTaskPolicyAndFullServerTotal() {
        var original=mock(MoldScheduleService.class);
        var groupTask=new MoldScheduleTypes.Task(7,"MBA101199","亮甲2.0","1199","ship","发模","logistics",null,null,false,"actualShipDate",0,null,"logistics",List.of(),List.of());
        when(original.tasks(any())).thenAnswer(i->{
            MoldScheduleTypes.Query q=i.getArgument(0);
            assertThat(q.isMineOnly()).isTrue();assertThat(q.getCategory()).isEqualTo("all");
            assertThat(q.getPage()).isEqualTo(2);assertThat(q.getSize()).isEqualTo(20);assertThat(q.getKeyword()).isEqualTo("亮甲");
            assertThat(q.isOverdueOnly()).isFalse();
            return new PageVO<>(List.of(groupTask),2,20,25,2);
        });
        var q=new Query();q.setPage(2);q.setKeyword("亮甲");
        var result=new ScheduleHomeSource(original).page(q,false);
        assertThat(result.totalElements()).isEqualTo(25);
        assertThat(result.content()).singleElement().satisfies(row->{
            assertThat(row).containsEntry("key","schedule:7:ship").containsEntry("ownerName","本人职责组待认领").containsEntry("overdue",false);
            assertThat(row.get("dueDate")).isNull();
        });
        verify(original).tasks(any());verifyNoMoreInteractions(original);
    }
    @Test void exceptionsReuseOriginalOverdueFilter() {
        var original=mock(MoldScheduleService.class);
        when(original.tasks(any())).thenAnswer(i->{assertThat(((MoldScheduleTypes.Query)i.getArgument(0)).isOverdueOnly()).isTrue();return new PageVO<>(List.of(),1,20,0,0);});
        assertThat(new ScheduleHomeSource(original).page(new Query(),true).totalElements()).isZero();
    }
    @Test void queryRejectsInvalidPaginationScopeAndOversizedInput() {
        try(var factory=jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            var q=new Query();q.setPage(0);q.setSize(101);q.setScope("anyone");q.setKeyword("x".repeat(129));
            assertThat(factory.getValidator().validate(q)).extracting(v->v.getPropertyPath().toString()).containsExactlyInAnyOrder("page","size","scope","keyword");
        }
    }
}
