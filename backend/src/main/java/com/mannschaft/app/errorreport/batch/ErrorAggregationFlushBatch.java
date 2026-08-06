package com.mannschaft.app.errorreport.batch;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.common.batch.PodLocalScheduled;
import com.mannschaft.app.errorreport.service.ErrorReportAggregator;
import com.mannschaft.app.errorreport.service.ErrorReportAggregator.AggregatedEntry;
import com.mannschaft.app.errorreport.service.ErrorReportNotifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * F10.6 §5.6-② — {@link ErrorReportAggregator} のバッファを定期的にドレインし、
 * 集約サマリを Slack に流すバッチ。
 *
 * <p>5 分毎（既定 {@code 300_000ms}）に {@link ErrorReportAggregator#drainAndClear()} を呼び、
 * 「直近 5 分で N 件発生」と 1 通の集約サマリを {@link ErrorReportNotifier#notifyAggregatedSummary}
 * 経由で Slack に送信する。</p>
 *
 * <p><b>self-invocation 罠回避:</b>
 * 本バッチは {@link ErrorReportAggregator} とは別 Bean として切り出されており、
 * {@code @Scheduled} の Spring AOP プロキシは確実に適用される。
 * 同一クラス内 self-invocation で {@code @Scheduled} がバイパスされる事故を未然に回避する。</p>
 *
 * <p>サマリに載せる対象は <b>occurrenceCount &gt;= 2</b> の entry のみ。
 * 1 通目（FIRST_OCCURRENCE）は別経路で即時通知済みのため、ここでは「2 通目以降だけが
 * 何件発生していたか」を報告する。</p>
 *
 * @see ErrorReportAggregator
 * @see ErrorReportNotifier#notifyAggregatedSummary(Map)
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ErrorAggregationFlushBatch {

    private final ErrorReportAggregator aggregator;
    private final ErrorReportNotifier notifier;

    @Value("${mannschaft.error-monitoring.aggregation.enabled:true}")
    private boolean enabled;

    /**
     * 5 分毎に集約バッファをドレインしてサマリ送信する。
     *
     * <p>{@code fixedRateString} で指定した間隔は {@code application.yml} で上書き可能。
     * バッチ実行が遅延しても次回は予定通り走る（fixedRate）。</p>
     *
     * <p><b>分散排他（{@code @SchedulerLock}）を敢えて付けない理由</b>:
     * ドレイン対象の {@link ErrorReportAggregator} のバッファは<b>その Pod のメモリ上</b>にあり、
     * 他 Pod のバッファには手が届かない。ロックを掛けると、ロックを取得できなかった Pod の
     * エラー集約は<b>永久にドレインされず溜まり続ける</b>（＝エラーの取りこぼしとメモリ膨張）。
     * よって Pod ごとに走ることが設計そのものであり、{@link PodLocalScheduled} で明示している。</p>
     *
     * <p><b>Phase 1-c への申し送り（外部通知の冪等化）</b>:
     * 上記の帰結として、Pod 数だけ Slack へサマリが飛ぶ（同一時間窓に N 通）。
     * バッファは Pod ごとに独立しているため<b>内容は重複しない</b>が、
     * 運用上は「1 通にまとめたい」需要がある。これは<b>ロックではなく通知側の集約・冪等化</b>で
     * 解くべき課題であり（ロックで解くと上記のドレイン欠落を招く）、
     * {@link ErrorReportNotifier#notifyAggregatedSummary} 側の是正として
     * Phase 1-c に申し送る。本 Javadoc がその申し送りの正本である。</p>
     */
    @PodLocalScheduled("Pod ローカルのメモリバッファをドレインする処理であり、"
        + "ロックを掛けると敗者 Pod の集約エラーが永久にドレインされず取りこぼすため")
    @BatchEndpoint(name = "errorreport-aggregation-flush", description = "エラー集約バッファを 5 分毎にドレインして Slack にサマリ送信する")
    @Scheduled(fixedRateString = "${mannschaft.error-monitoring.aggregation.flush-interval-ms:300000}",
               initialDelayString = "${mannschaft.error-monitoring.aggregation.flush-interval-ms:300000}")
    public void flush() {
        if (!enabled) return;
        try {
            doFlush();
        } catch (Exception e) {
            // バッチが落ちると後続が止まるので必ず握る
            log.warn("ErrorAggregationFlushBatch: 集約サマリ送信失敗", e);
        }
    }

    /**
     * 実フラッシュ処理。テスト容易性のため public で切り出している。
     */
    public void doFlush() {
        Map<String, AggregatedEntry> drained = aggregator.drainAndClear();
        if (drained.isEmpty()) {
            return;
        }
        // 2 通目以降のみサマリ対象（occurrenceCount >= 2）
        Map<String, AggregatedEntry> filtered = new LinkedHashMap<>();
        long totalOccurrences = 0;
        for (Map.Entry<String, AggregatedEntry> entry : drained.entrySet()) {
            AggregatedEntry e = entry.getValue();
            if (e.occurrenceCount() >= 2L) {
                filtered.put(entry.getKey(), e);
                totalOccurrences += e.occurrenceCount();
            }
        }
        if (filtered.isEmpty()) {
            log.debug("ErrorAggregationFlushBatch: 全 {} エントリは初回発火のみ。サマリ送信スキップ。", drained.size());
            return;
        }
        log.info("ErrorAggregationFlushBatch: {} 種のエラーが計 {} 回発生。集約サマリを送信。",
                filtered.size(), totalOccurrences);
        notifier.notifyAggregatedSummary(filtered);
    }

    /**
     * テスト容易性のためのドレイン件数参照（送信前のフィルタ後件数）。
     */
    static int countAggregatedEntries(List<AggregatedEntry> entries) {
        return entries == null ? 0 : entries.size();
    }
}
