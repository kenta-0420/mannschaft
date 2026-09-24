package com.mannschaft.app.returnstayplan.service;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import io.micrometer.core.instrument.MeterRegistry;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** F02.11 期限切れ予定purgeバッチの契約骨格。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReturnStayPlanPurgeBatchService {

    private final ReturnStayPlanService service;
    private final MeterRegistry meterRegistry;

    @BatchEndpoint(name = "return-stay-plan-purge-daily",
            description = "終了から1年を超えた帰省・滞在予定を日次削除する")
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "終了から1年を超えた帰省・滞在予定の保持期間超過削除。止めると保持期限を超えた個人データが残留する")
    @Scheduled(cron = "0 10 4 * * *", zone = "Asia/Tokyo")
    @SchedulerLock(name = "returnStayPlanPurgeBatch", lockAtLeastFor = "PT5S", lockAtMostFor = "PT5M")
    public Integer purgeExpiredPlans() {
        int deleted = service.purgeExpiredPlans();
        meterRegistry.counter("return_stay_plan_purge_deleted_total").increment(deleted);
        log.info("Return/stay plan purge completed: deleted={}", deleted);
        return deleted;
    }
}
