package com.mannschaft.app.returnstayplan.service;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** F02.11 期限切れ予定purgeバッチの契約骨格。 */
@Service
@RequiredArgsConstructor
public class ReturnStayPlanPurgeBatchService {

    private final ReturnStayPlanService service;

    @BatchEndpoint(name = "return-stay-plan-purge-daily",
            description = "終了から1年を超えた帰省・滞在予定を日次削除する")
    @Scheduled(cron = "0 10 4 * * *", zone = "Asia/Tokyo")
    @SchedulerLock(name = "returnStayPlanPurgeBatch", lockAtLeastFor = "PT5S", lockAtMostFor = "PT5M")
    public Integer purgeExpiredPlans() {
        return service.purgeExpiredPlans();
    }
}
