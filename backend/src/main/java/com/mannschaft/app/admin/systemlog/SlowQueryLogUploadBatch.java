package com.mannschaft.app.admin.systemlog;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * スロークエリログを毎日 1:00 AM に前日分を R2 にアップロードするバッチ。
 * デフォルトは毎日午前 1:00 実行。環境変数でスケジュールを変更可能。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SlowQueryLogUploadBatch {

    private final SystemLogService systemLogService;

    /**
     * 前日分のスロークエリログを R2 にアップロードする。
     * cron 式: 秒 分 時 日 月 曜日（デフォルト: 毎日 1:00 AM）
     */
    @BatchEndpoint(name = "systemlog-slow-query-upload-daily", description = "前日分スロークエリログを R2 へ毎日 01:00 アップロードする")
    @Scheduled(cron = "${mannschaft.system-log.slow-query-cron:0 0 1 * * *}")
    // 起動間隔は日次 01:00。処理は前日分スロークエリログ 1 ファイルの R2 アップロードのみで、最悪ケース（ログが上限まで肥大 + R2 リトライ）
    // でも数分。ネットワーク停滞を見込んで 30 分を上限とする。
    @SchedulerLock(name = "systemLogSlowQueryUpload", lockAtLeastFor = "PT1M", lockAtMostFor = "PT30M")
    public void run() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        log.info("スロークエリログアップロードバッチ開始: date={}", yesterday);
        systemLogService.uploadSlowQueryLog(yesterday);
        log.info("スロークエリログアップロードバッチ完了: date={}", yesterday);
    }
}
