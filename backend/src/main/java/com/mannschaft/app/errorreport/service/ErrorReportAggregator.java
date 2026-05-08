package com.mannschaft.app.errorreport.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.mannschaft.app.errorreport.ErrorReportSeverity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * F10.6 §5.6 — エラー発生回数を {@code error_hash} 単位で集約するインメモリバッファ。
 *
 * <p>同一の {@code error_hash} について 1 通目（cooldown 期間外）の発火は
 * 即時通知 ({@link AggregationResult#FIRST_OCCURRENCE}) を返し、2 通目以降は
 * バッファに蓄積 ({@link AggregationResult#BUFFERED}) して 5 分後に
 * {@link ErrorAggregationFlushBatch} が「直近 5 分で N 件発生」とまとめて Slack に流す。</p>
 *
 * <p><b>設計上の判断:</b></p>
 * <ul>
 *   <li>バッファは Caffeine の {@code expireAfterWrite=5min}, {@code maximumSize=1000} で管理。
 *       OOM 防止のため 1 entry あたりの occurrence 上限を {@code maxOccurrencesPerHash=100}
 *       で制限する（殿様御裁可）。上限到達時は {@link AggregationResult#BUFFER_FULL} を返し、
 *       追加を無視するが {@code error_reports.occurrence_count} はインクリメントされ続けるため
 *       数値で発生頻度は確認可能。</li>
 *   <li>「初回」判定は外部 cooldown キャッシュ (notifySlack 等の既存重複抑制) と独立で、
 *       本クラス自身の Caffeine cache に entry が無ければ FIRST_OCCURRENCE。
 *       2 通目以降は entry を更新して occurrenceCount をインクリメント。</li>
 *   <li>{@link #drainAndClear()} はバッチが呼び出し、内部 cache を全クリアして
 *       スナップショットを返す。次回 5 分は再びカウント開始。</li>
 * </ul>
 *
 * <p>本クラスは Spring 管理 Bean として {@code @Component} 化されているが、
 * @Async / @Scheduled は持たない（プロキシバイパス問題を避けるため、
 * バッチは {@link ErrorAggregationFlushBatch} に切り出している）。</p>
 *
 * @see ErrorAggregationFlushBatch
 * @see ErrorReportNotifier#notifyAggregatedSummary(Map)
 */
@Component
@Slf4j
public class ErrorReportAggregator {

    /** バッファの TTL（同 entry が更新されてから expire するまで）。 */
    private static final Duration BUFFER_TTL = Duration.ofMinutes(5);
    /** バッファに保持する最大 entry 数（OOM 防止）。 */
    private static final long BUFFER_MAX_SIZE = 1000L;

    /** 1 entry（同一 error_hash）あたりの最大 occurrence 蓄積数。 */
    private final long maxOccurrencesPerHash;

    /**
     * 同一 error_hash の発生件数を蓄積するバッファ。
     * key = error_hash, value = AggregatedEntry。
     */
    private final Cache<String, AggregatedEntry> aggregationBuffer;

    public ErrorReportAggregator(
            @Value("${mannschaft.error-monitoring.aggregation.max-occurrences-per-hash:100}") long maxOccurrencesPerHash) {
        this.maxOccurrencesPerHash = maxOccurrencesPerHash;
        this.aggregationBuffer = Caffeine.newBuilder()
                .expireAfterWrite(BUFFER_TTL)
                .maximumSize(BUFFER_MAX_SIZE)
                .build();
    }

    /**
     * エラーを集約バッファに追加する。
     *
     * @param errorHash エラーハッシュ（必須）
     * @param message   エラーメッセージ（NULL 可、最終的にサマリ通知に載せる）
     * @param severity  重要度（NULL 可、サマリ表示用）
     * @return 集約結果。FIRST_OCCURRENCE / BUFFERED / BUFFER_FULL のいずれか
     */
    public AggregationResult addOccurrence(String errorHash, String message, ErrorReportSeverity severity) {
        if (errorHash == null || errorHash.isBlank()) {
            // 不正入力。呼び出し側のバグなので警告のみ
            log.warn("ErrorReportAggregator: errorHash が空です。発生集約をスキップします。");
            return AggregationResult.BUFFER_FULL;
        }
        Instant now = Instant.now();
        AggregatedEntry existing = aggregationBuffer.getIfPresent(errorHash);
        if (existing == null) {
            // 初回 → 即時通知扱いでバッファには新規 entry を作成（occurrence=1 で次回以降を BUFFERED 扱いにする）
            AggregatedEntry created = new AggregatedEntry(errorHash, message, severity, now);
            aggregationBuffer.put(errorHash, created);
            return AggregationResult.FIRST_OCCURRENCE;
        }
        // 2 通目以降
        long current = existing.count.get();
        if (current >= maxOccurrencesPerHash) {
            // OOM 防止のため上限到達時は捨てる（log warn のみ）
            log.warn("ErrorReportAggregator: errorHash={} の蓄積上限 {} 到達。以降の追加を無視します。",
                    errorHash, maxOccurrencesPerHash);
            return AggregationResult.BUFFER_FULL;
        }
        existing.recordOccurrence(now);
        return AggregationResult.BUFFERED;
    }

    /**
     * 現在のバッファをドレイン（取り出し＆クリア）する。
     *
     * <p>{@link ErrorAggregationFlushBatch} が 5 分毎に呼び、返却された Map を Slack に集約サマリとして送信する。
     * 戻り値の Map は呼び出し側の独立コピー（バッファ自身は invalidateAll 済み）。</p>
     *
     * <p>注意: occurrence==1 の entry も含まれる（FIRST_OCCURRENCE 時点で entry 化されるため）。
     * バッチ側で「2 通以上発生したもののみサマリに載せる」と判断する。</p>
     *
     * @return error_hash → AggregatedEntry の不変スナップショット Map
     */
    public Map<String, AggregatedEntry> drainAndClear() {
        Map<String, AggregatedEntry> snapshot = new HashMap<>(aggregationBuffer.asMap());
        aggregationBuffer.invalidateAll();
        return snapshot;
    }

    /**
     * テスト容易性のためのバッファサイズ参照。
     */
    long currentBufferSize() {
        return aggregationBuffer.estimatedSize();
    }

    /**
     * 集約バッファ追加結果。
     */
    public enum AggregationResult {
        /** バッファに entry が無かった → 即時通知すべき。 */
        FIRST_OCCURRENCE,
        /** バッファに既に entry があり、occurrence をインクリメントした → 通知抑制。 */
        BUFFERED,
        /** entry が上限に達しており、それ以上は蓄積しない（log warn のみ）。 */
        BUFFER_FULL
    }

    /**
     * バッファ内の集約エントリ。可変な occurrenceCount / lastSeenAt を持つ。
     */
    public static final class AggregatedEntry {
        private final String errorHash;
        private final String message;
        private final ErrorReportSeverity severity;
        private final Instant firstSeenAt;
        private volatile Instant lastSeenAt;
        private final AtomicLong count = new AtomicLong(1);

        AggregatedEntry(String errorHash, String message, ErrorReportSeverity severity, Instant firstSeenAt) {
            this.errorHash = errorHash;
            this.message = message;
            this.severity = severity;
            this.firstSeenAt = firstSeenAt;
            this.lastSeenAt = firstSeenAt;
        }

        void recordOccurrence(Instant at) {
            count.incrementAndGet();
            lastSeenAt = at;
        }

        public String errorHash() { return errorHash; }
        public String message() { return message; }
        public ErrorReportSeverity severity() { return severity; }
        public long occurrenceCount() { return count.get(); }
        public Instant firstSeenAt() { return firstSeenAt; }
        public Instant lastSeenAt() { return lastSeenAt; }
    }
}
