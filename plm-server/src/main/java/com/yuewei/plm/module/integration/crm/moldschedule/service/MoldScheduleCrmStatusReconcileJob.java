package com.yuewei.plm.module.integration.crm.moldschedule.service;

import com.yuewei.plm.module.integration.crm.moldschedule.config.CrmMoldScheduleProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class MoldScheduleCrmStatusReconcileJob {
    private final CrmMoldScheduleProperties properties;
    private final MoldScheduleCrmSyncService sync;

    public MoldScheduleCrmStatusReconcileJob(CrmMoldScheduleProperties properties, MoldScheduleCrmSyncService sync) {
        this.properties = properties;
        this.sync = sync;
    }

    @Scheduled(cron = "${plm.integrations.crm.mold-schedule.reconcile-cron:0 10 0 * * *}", zone = "Asia/Shanghai")
    public void reconcile() {
        if (!properties.isEnabled()) return;
        try {
            int queued = sync.enqueueAllActive("daily-reconcile", "reconcile");
            if (queued > 0) log.info("CRM mold schedule daily reconciliation queued {} changed snapshots", queued);
        } catch (Exception failure) {
            log.error("CRM mold schedule daily reconciliation failed", failure);
        }
    }
}
