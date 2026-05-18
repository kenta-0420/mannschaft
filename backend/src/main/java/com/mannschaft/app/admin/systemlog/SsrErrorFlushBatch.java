package com.mannschaft.app.admin.systemlog;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * SSR エラーバッファを定期的に R2 にフラッシュするバッチ。
 * デフォルトは 5 分間隔（300,000ms）。環境変数でインターバルを変更可能。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SsrErrorFlushBatch {

    private final SystemLogService systemLogService;

    /**
     * バッファに蓄積された SSR エラーを R2 にフラッシュする。
     * fixedDelay: 前回の実行完了から指定ミリ秒後に次の実行を開始する。
     */
    @BatchEndpoint(name = "systemlog-ssr-error-flush", description = "SSR エラーバッファを 5 分毎に R2 にフラッシュする")
    @Scheduled(fixedDelayString = "${mannschaft.system-log.ssr-flush-interval-ms:300000}")
    public void run() {
        LocalDate today = LocalDate.now();
        log.debug("SSR エラーフラッシュバッチ開始: date={}", today);
        systemLogService.flushSsrErrors(today);
        log.debug("SSR エラーフラッシュバッチ完了: date={}", today);
    }
}
