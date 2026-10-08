package com.mannschaft.app.common.activityschedule;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.schedule.service.ScheduleCompletionService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;

/** 予定完了の毎分入口。試練先行の骨格で、まだ完了処理を実行しない。 */
@Service
public class AutomaticScheduleCompletionBatch {
    public static final int LIMIT = 100;
    public static final String LOCK_NAME = "automaticScheduleCompletionBatch";
    private final ScheduleCompletionService schedules;
    private final AutomaticScheduleCompletionFacade completion;
    private final Clock clock;

    public AutomaticScheduleCompletionBatch(ScheduleCompletionService schedules,
                                             AutomaticScheduleCompletionFacade completion,
                                             @Qualifier("wallClock") Clock clock) {
        this.schedules = schedules;
        this.completion = completion;
        this.clock = clock;
    }

    /** 完了した実件数を返す。ShedLock skip時はnullとなる参照型を維持する。 */
    @BatchEndpoint(name = "automatic-schedule-completion", description = "共有予定の期限経過と予定表示を毎分確定する")
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "共有予定の自動完了には停止gateがなく、既存の常時実行policyを使用する")
    @Scheduled(fixedDelay = 60_000)
    @SchedulerLock(name = LOCK_NAME, lockAtLeastFor = "PT30S", lockAtMostFor = "PT2M")
    public Integer runBatch() { return 0; }
}
