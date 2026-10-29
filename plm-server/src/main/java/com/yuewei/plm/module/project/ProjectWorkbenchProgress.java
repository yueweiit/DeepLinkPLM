package com.yuewei.plm.module.project;

import com.yuewei.plm.module.collaboration.MoldSchedulePolicy;
import com.yuewei.plm.module.collaboration.MoldScheduleTypes.Task;
import com.yuewei.plm.module.moldreference.MoldCompatibilityPolicy;
import java.util.*;

/** Presents existing facts without advancing any manual workflow node. */
public final class ProjectWorkbenchProgress {
    private ProjectWorkbenchProgress() {}
    public static Map<String,Object> summarize(String kind,String flow,List<Map<String,Object>> apps,List<Map<String,Object>> sets,boolean canApps,boolean canSets){
        var result=new LinkedHashMap<String,Object>();var risks=new LinkedHashSet<String>();
        var tasks=sets.stream().flatMap(s->MoldSchedulePolicy.tasksFor(s,MoldSchedulePolicy.today(),"all").stream())
            .sorted(Comparator.comparing(Task::overdue).reversed().thenComparing(Task::dueDate,Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(Task::taskKey)).toList();
        long physical=sets.stream().filter(s->"confirmed".equals(MoldCompatibilityPolicy.partState(s,"pc"))||"confirmed".equals(MoldCompatibilityPolicy.partState(s,"tpu"))).count();
        long making=sets.stream().filter(s->"making".equals(MoldCompatibilityPolicy.partState(s,"pc"))||"making".equals(MoldCompatibilityPolicy.partState(s,"tpu"))).count();
        boolean paused=sets.stream().anyMatch(s->Boolean.TRUE.equals(s.get("schedulePaused")))||apps.stream().anyMatch(a->"paused".equals(a.get("executionStatus")));
        for(var a:apps)if("blocked".equals(a.get("provisioningStatus")))risks.add(Objects.toString(a.get("provisioningError"),"建档目标待核对"));
        long late=tasks.stream().filter(Task::overdue).count();if(late>0)risks.add(late+" 项待办已逾期");if(paused)risks.add("关联申请执行已暂停或终止；已发生事实保留");
        String phase=kind.equals("product_research")?flow:!canApps&&!canSets?"业务进度未授权":paused?"执行暂停":!risks.isEmpty()&&apps.stream().anyMatch(a->"blocked".equals(a.get("provisioningStatus")))?"建档待核对":
            !sets.isEmpty()?sets.stream().allMatch(s->s.get("marketReadyDate")!=null)?"已确认上市":sets.stream().allMatch(s->"delivered".equals(s.get("scheduleStatus")))?"逐套验收通过":making>0?"制作中":physical>0?"已有实物，交付／验收见逐套记录":sets.stream().anyMatch(s->s.get("openedDate")!=null)?"已开工，按逐套事实推进":"待开工／实物待核对":
            apps.stream().anyMatch(a->"draft".equals(a.get("status")))?"草稿待提交":apps.stream().anyMatch(a->Boolean.TRUE.equals(a.get("approvalGranted")))?"审批已同意，待核对建档":!apps.isEmpty()?"审批进度见申请":"尚未关联开模业务";
        String next=kind.equals("product_research")?"处理研发节点："+flow:paused?"查看暂停原因，由工程明确处理":!risks.isEmpty()&&apps.stream().anyMatch(a->"blocked".equals(a.get("provisioningStatus")))?"核对申请建档目标后重试":!tasks.isEmpty()?tasks.get(0).title():!apps.isEmpty()?Objects.toString(apps.get(0).get("nextStep"),"查看申请进度"):"查看项目资料及关联依据";
        result.put("phase",phase);result.put("nextStep",next);result.put("risks",List.copyOf(risks));result.put("physicalSetCount",canSets?physical:null);result.put("makingSetCount",canSets?making:null);result.put("overdueTaskCount",canSets?late:null);
        if(!tasks.isEmpty()&&!paused)result.put("nextTask",tasks.get(0));return result;
    }
}
