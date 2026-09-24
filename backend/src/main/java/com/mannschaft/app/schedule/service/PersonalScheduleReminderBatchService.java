package com.mannschaft.app.schedule.service;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 個人予定リマインダーバッチ（機能55 第二陣 — リマインド根治）。
 *
 * <p>1分間隔で due 判定済みの個人予定リマインダー（RELATIVE: 開始N分前到達 / ABSOLUTE: 固定日時到達）を
 * 走査し、予定の所有者へ通知する。第一陣までは起動バッチが存在せず個人予定リマインダーは
 * 永遠に発火しなかったため、本バッチで根治する。</p>
 *
 * <p>{@link SchedulerLock} により複数インスタンス起動時の二重実行を防ぐ。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PersonalScheduleReminderBatchService {

    private final PersonalScheduleReminderService personalScheduleReminderService;

    /**
     * 個人予定リマインダーバッチを実行する。
     */
    @BatchEndpoint(name = "personal-schedule-reminder",
            description = "個人予定の due リマインダーを 1 分毎に所有者へ通知する")
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "対応する gate_key が無く停止条件を宣言できないため常時実行する。個人予定のリマインド送信。機能単位の閉栓が要るようになった時点で gate_key の発行から検討すること")
    @Scheduled(fixedDelay = 60_000) // 1分間隔
    @SchedulerLock(
            name = "personalScheduleReminderBatch",
            lockAtLeastFor = "PT50S",
            lockAtMostFor = "PT2M")
    public void runBatch() {
        try {
            personalScheduleReminderService.processDueReminders();
        } catch (Exception e) {
            log.error("個人予定リマインダーバッチ処理失敗: error={}", e.getMessage(), e);
        }
    }
}
