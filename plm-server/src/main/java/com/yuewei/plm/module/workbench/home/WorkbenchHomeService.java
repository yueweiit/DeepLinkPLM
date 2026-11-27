package com.yuewei.plm.module.workbench.home;

import com.yuewei.plm.common.exception.BusinessException;
import com.yuewei.plm.module.pilot.PilotAccessService;
import java.util.*;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import static com.yuewei.plm.module.workbench.home.WorkbenchHomeModels.*;

@Service @RequiredArgsConstructor @Slf4j
public class WorkbenchHomeService {
    private final PilotAccessService access;
    private final ResearchHomeQuery research;
    private final ScheduleHomeSource schedule;
    private final ApprovalHomeQuery generic;
    private final MoldApprovalHomeSource molds;

    public HomePage projects(Query q) { return read("research",q,"project:view",()->research.page(q,false)); }
    public HomePage tasks(Query q) { requireSource(q,"schedule"); return read("schedule",q,"schedule:read",()->schedule.page(q,false)); }
    public HomePage approvals(Query q) {
        requireSource(q,"generic","mold_application");
        return "generic".equals(q.getSource())?read("generic",q,"approval:view",()->generic.page(q)):
            read("mold_application",q,"collaboration:view",()->molds.page(q,false));
    }
    public HomePage exceptions(Query q) {
        requireSource(q,"research","schedule","mold_application");
        return switch(q.getSource()) {
            case "research" -> read("research",q,"project:view",()->research.page(q,true));
            case "schedule" -> read("schedule",q,"schedule:read",()->schedule.page(q,true));
            default -> read("mold_application",q,"collaboration:view",()->molds.page(q,true));
        };
    }
    public Map<String,List<SourceCount>> summary() {
        access.requirePermission("dashboard:view");
        Query q = new Query(); q.setSize(1);
        var result = new LinkedHashMap<String,List<SourceCount>>();
        result.put("tasks",List.of(tasks(q).count()));
        result.put("projects",List.of(projects(q).count()));
        q.setSource("generic"); var a = approvals(q).count(); q.setSource("mold_application"); var b = approvals(q).count();
        result.put("approvals",List.of(a,b));
        q.setSource("schedule"); var s = exceptions(q).count(); q.setSource("research"); var r = exceptions(q).count();
        q.setSource("mold_application"); var m = exceptions(q).count(); result.put("exceptions",List.of(s,r,m));
        return result;
    }
    private HomePage read(String source, Query q, String permission, Supplier<HomePage> reader) {
        access.requirePermission("dashboard:view");
        if (!access.hasPermission(permission)) return unavailable(source,q,"restricted");
        try { return reader.get(); }
        catch (BusinessException e) {
            if (e.getCode()==40103) return unavailable(source,q,"restricted");
            if (e.getCode()==40401) return unavailable(source,q,"disabled");
            log.warn("Workbench source {} unavailable",source,e); return unavailable(source,q,"error");
        } catch (RuntimeException e) { log.warn("Workbench source {} failed",source,e); return unavailable(source,q,"error"); }
    }
    private void requireSource(Query q,String... allowed) {
        access.requirePermission("dashboard:view");
        if (!Arrays.asList(allowed).contains(q.getSource())) throw new BusinessException(40001,"工作台来源无效");
    }
}
