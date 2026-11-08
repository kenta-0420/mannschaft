package com.mannschaft.app.moduleevent;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DB不要の公開surface契約。注釈付き骨格では初回greenになり得るcharacterizationであり、
 * 業務本体のgenuine red/greenは実MySQLのRetentionBatchITで別に確認する。
 */
class ModuleEventResumeAuditRetentionBatchTest {
    @Test
    void 日次UTCの同一入口が管理起動と背景処理と明示lockの契約を持つ() throws Exception {
        var method = ModuleEventResumeAuditRetentionBatch.class.getMethod("deleteExpiredAudits");
        assertThat(method.getParameterCount()).isZero();
        assertThat(method.getReturnType()).isEqualTo(Integer.class);
        var schedule = method.getAnnotation(Scheduled.class);
        assertThat(schedule.cron()).isEqualTo("0 0 3 * * *");
        assertThat(schedule.zone()).isEqualTo("UTC");
        assertThat(method.getAnnotation(BatchEndpoint.class).name())
                .isEqualTo(ModuleEventResumeAuditRetentionBatch.BATCH_NAME);
        var policy = method.getAnnotation(BackgroundFeaturePolicy.class);
        assertThat(policy.mode()).isEqualTo(BackgroundFeatureMode.ALWAYS);
        assertThat(policy.gateKeys()).isEmpty();
        assertThat(policy.reason()).isNotBlank();
        var lock = method.getAnnotation(SchedulerLock.class);
        assertThat(lock.name()).isEqualTo(ModuleEventResumeAuditRetentionBatch.LOCK_NAME);
        assertThat(lock.lockAtLeastFor()).isEqualTo("PT1M");
        assertThat(lock.lockAtMostFor()).isEqualTo("PT30M");
        assertThat(method.getAnnotation(Transactional.class).propagation()).isEqualTo(Propagation.NEVER);
    }
}
