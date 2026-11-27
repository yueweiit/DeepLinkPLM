package com.yuewei.plm.module.workbench.home;

import com.yuewei.plm.module.moldapplication.MoldApplicationService;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import static com.yuewei.plm.module.workbench.home.WorkbenchHomeModels.*;

@Service @RequiredArgsConstructor
public class MoldApprovalHomeSource {
    private final MoldApplicationService applications;
    @Value("${app.workbench.approval-stale-hours:24}") private long staleHours = 24;

    public HomePage page(Query query, boolean exceptions) {
        if (!applications.enabled()) return unavailable("mold_application",query,"disabled");
        var result = applications.workbenchRead(query.getPage(),query.getSize(),exceptions);
        if ("identity_unbound".equals(result.state())) return unavailable("mold_application",query,result.state());
        var page = WorkbenchHomeModels.page("mold_application",query,result.page().getContent(),result.page().getTotalElements(),
            result.syncedAt()==null?null:result.syncedAt().toString());
        if (result.syncedAt()==null || result.syncedAt().isBefore(Instant.now().minus(Duration.ofHours(Math.max(1,staleHours))))) return page.state("stale");
        return page;
    }
}
