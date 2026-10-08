package com.mannschaft.app.common.activityschedule;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.schedule.service.ScheduleCompletionService;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.OffsetDateTime;

/** 共有具体予定を毎分100候補まで処理する。各行は別Beanの短TXで最新条件を再確認する。 */
@Slf4j
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

    /**
     * 完了した実件数を返す。候補100件・外部I/Oなし、PT2M以内を想定する。
     * lock期限後も行lockと最新status再確認で二重更新を防ぐ。skip時はnullとなる参照型を使う。
     */
    @BatchEndpoint(name = "automatic-schedule-completion", description = "共有予定の期限経過と予定表示を毎分確定する")
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "共有予定の自動完了には停止gateがなく、既存の常時実行policyを使用する")
    @Scheduled(fixedDelay = 60_000)
    @SchedulerLock(name = LOCK_NAME, lockAtLeastFor = "PT30S", lockAtMostFor = "PT2M")
    public Integer runBatch() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        var ids = schedules.findDueIds(now, LIMIT);
        int completed = 0;
        int failed = 0;
        for (Long id : ids) {
            try {
                if (completion.completeOne(id, now)) completed++;
            } catch (Exception e) {
                failed++;
                log.error("予定自動完了の行処理失敗 scheduleId={}", id, e);
            }
        }
        log.info("予定自動完了 batchCandidates={} completed={} failed={}", ids.size(), completed, failed);
        return completed;
    }
}