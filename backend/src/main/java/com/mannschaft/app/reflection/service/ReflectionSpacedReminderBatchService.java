package com.mannschaft.app.reflection.service;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 振り返りの間隔反復／考査前リマインドを 1 分毎に送信するバッチ（F06.5・§5.2）。
 *
 * <p>絶対日時 {@code remind_at}＋status 遷移型（{@code ScheduleReminderBatchService} 手本）。
 * ユーザー TZ は {@code remind_at} 生成時（§5.3）に織り込むため、バッチ本体は単純な絶対時刻比較でよい。
 * {@code @SchedulerLock} で複数インスタンスの重複起動を防止する（AC-11）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReflectionSpacedReminderBatchService {

    private final ReflectionSpacedReminderService reminderService;

    @BatchEndpoint(name = "reflection-spaced-reminder",
            description = "振り返りの間隔反復／考査前リマインドを1分毎に実効時刻判定して送信する")
    @Scheduled(fixedDelay = 60_000)
    @SchedulerLock(name = "reflectionSpacedReminderBatch",
            lockAtLeastFor = "PT50S", lockAtMostFor = "PT2M")
    public void runBatch() {
        try {
            reminderService.processDueReminders();
        } catch (Exception e) {
            log.error("振り返りリマインダーバッチ処理失敗: error={}", e.getMessage(), e);
        }
    }
}
