package com.mannschaft.app.admin.systemlog;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.common.batch.PodLocalScheduled;
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
     *
     * <p><b>分散排他（{@code @SchedulerLock}）を敢えて付けない理由</b>:
     * 本バッチが吐き出すのは {@code SystemLogService} が<b>その Pod のメモリ上に</b>
     * 蓄積した SSR エラーバッファであり、他 Pod のバッファには手が届かない。
     * ロックを掛けると、ロックを取得できなかった Pod のバッファは
     * <b>flush されないまま溜まり続ける</b>（ログ欠落とメモリ膨張）。
     * すなわち Pod ごとに走ることが設計そのものである。
     * この意図は {@link PodLocalScheduled} で番人に対して明示している。</p>
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "SSR エラーバッファは上限のない ConcurrentLinkedQueue であり、止めると Pod のメモリを際限なく食い潰し、蓄積分は R2 に落ちないまま消失する")
    @PodLocalScheduled("Pod ローカルのメモリバッファを flush する処理であり、"
        + "ロックを掛けると敗者 Pod のバッファが永久に flush されず SSR エラーログが欠落するため")
    @BatchEndpoint(name = "systemlog-ssr-error-flush", description = "SSR エラーバッファを 5 分毎に R2 にフラッシュする")
    @Scheduled(fixedDelayString = "${mannschaft.system-log.ssr-flush-interval-ms:300000}")
    public void run() {
        LocalDate today = LocalDate.now();
        log.debug("SSR エラーフラッシュバッチ開始: date={}", today);
        systemLogService.flushSsrErrors(today);
        log.debug("SSR エラーフラッシュバッチ完了: date={}", today);
    }
}
