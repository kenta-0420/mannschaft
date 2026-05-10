package com.mannschaft.app.admin.systemlog;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
    @Scheduled(cron = "${mannschaft.system-log.slow-query-cron:0 0 1 * * *}")
    public void run() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        log.info("スロークエリログアップロードバッチ開始: date={}", yesterday);
        systemLogService.uploadSlowQueryLog(yesterday);
        log.info("スロークエリログアップロードバッチ完了: date={}", yesterday);
    }
}
