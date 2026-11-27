package com.yuewei.plm.module.workbench.home;

import com.yuewei.plm.module.collaboration.MoldScheduleService;
import com.yuewei.plm.module.collaboration.MoldScheduleTypes;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import static com.yuewei.plm.module.workbench.home.WorkbenchHomeModels.*;

@Service @RequiredArgsConstructor
public class ScheduleHomeSource {
    private final MoldScheduleService schedules;
    public HomePage page(Query query, boolean exceptions) {
        var original = new MoldScheduleTypes.Query();
        original.setPage(query.getPage()); original.setSize(query.getSize()); original.setKeyword(query.getKeyword());
        original.setCategory("all"); original.setMineOnly(true); original.setOverdueOnly(exceptions||query.isOverdueOnly());
        var result = schedules.tasks(original);
        var rows = result.getContent().stream().map(task -> {
            Map<String,Object> row = new LinkedHashMap<>();
            row.put("key","schedule:"+task.moldSetId()+":"+task.taskKey()); row.put("source","schedule"); row.put("target","schedule");
            row.put("businessId",String.valueOf(task.moldSetId())); row.put("moldSetId",task.moldSetId()); row.put("taskKey",task.taskKey());
            row.put("title",task.title()); row.put("code",task.assetNumber()); row.put("projectName",task.styleName());
            row.put("ownerUserId",task.ownerUserId()); row.put("ownerName",task.ownerUserId()==null?"本人职责组待认领":"任务负责人 #"+task.ownerUserId());
            row.put("dueDate",task.dueDate()); row.put("overdue",task.overdue()); row.put("nextAction","进入排程办理");
            row.put("blocker",task.overdue()?"排程任务已逾期 "+task.overdueDays()+" 天":"");
            return row;
        }).toList();
        return WorkbenchHomeModels.page("schedule",query,rows,result.getTotalElements(),null);
    }
}
