package com.mannschaft.app.schedule.service;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 共有予定リマインダーバッチ（機能55 第二陣 — リマインド根治）。
 *
 * <p>1分間隔で未送信のリマインダーを走査し、実効リマインド時刻（ABSOLUTE は固定日時、
 * RELATIVE は親予定開始のN分前）を過ぎたものを送信する。第一陣までは {@code @Scheduled} も
 * 呼び出し元も存在せず共有予定リマインダーは永遠に発火しなかったため、本バッチで根治する。</p>
 *
 * <p>{@link SchedulerLock} により複数インスタンス起動時の二重実行を防ぐ。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScheduleReminderBatchService {

    private final ScheduleReminderService scheduleReminderService;

    /**
     * 共有予定リマインダーバッチを実行する。
     */
    @BatchEndpoint(name = "schedule-reminder",
            description = "共有予定の未送信リマインダーを 1 分毎に実効時刻判定して送信する")
    @Scheduled(fixedDelay = 60_000) // 1分間隔
    @SchedulerLock(
            name = "scheduleReminderBatch",
            lockAtLeastFor = "PT50S",
            lockAtMostFor = "PT2M")
    public void runBatch() {
        try {
            scheduleReminderService.processScheduledReminders();
        } catch (Exception e) {
            log.error("共有予定リマインダーバッチ処理失敗: error={}", e.getMessage(), e);
        }
    }
}
