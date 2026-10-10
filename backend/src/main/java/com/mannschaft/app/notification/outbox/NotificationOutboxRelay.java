package com.mannschaft.app.notification.outbox;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

/**
 * 送り手ドメインの outbox を通知ドメインへ取り込む指揮役（docs/architecture/notification_outbox.md §4・§5）。
 *
 * <p><b>tx を持たない</b>。1行ごとに ① {@link NotificationOutboxSource#claim}（送り手の REQUIRES_NEW）
 * ② {@link NotificationOutboxIngestService#ingest}（通知の別 Bean・REQUIRES_NEW）
 * ③ {@link NotificationOutboxSource#markRelayed} / {@link NotificationOutboxSource#markFailed}（送り手の REQUIRES_NEW）
 * の順に呼ぶ。行ごとに例外を捕まえ、1行の失敗で残りを止めない。</p>
 *
 * <ul>
 *   <li>起こし: {@link #onAppended} を {@code @Async("notification-outbox-pool")} と
 *       {@code @TransactionalEventListener(AFTER_COMMIT)} で受けて即 drain（{@code nudge-enabled=false} なら何もしない。
 *       試験プロファイルでは止め、IT は {@link #drainAll()} を同期で呼ぶ）</li>
 *   <li>予備ポーラー: {@link #poll()} を {@code @Scheduled(fixedDelay=5000)}・
 *       {@code @SchedulerLock(name="notificationOutboxRelay", lockAtMostFor="PT1M", lockAtLeastFor="PT1S")}・
 *       {@code @BackgroundFeaturePolicy(ALWAYS)} で回す。1回の実行は100件の claim を空になるまで繰り返し、30秒で打ち切る</li>
 * </ul>
 *
 * <p>【試練の骨格・出陣で実装】注釈・本体・メトリクスの登録は出陣で行う。</p>
 */
@Component
public class NotificationOutboxRelay {

    /** 1回の claim の最大行数。 */
    public static final int CLAIM_LIMIT = 100;
    /** 1回の drain の打ち切り時間。 */
    public static final Duration DRAIN_TIME_BUDGET = Duration.ofSeconds(30);
    /** この回数まで失敗したら DEAD。 */
    public static final int MAX_ATTEMPTS = 10;
    /** 再試行の待ちの基準（30秒×2^(n-1)）と上限（1時間）。 */
    public static final Duration BACKOFF_BASE = Duration.ofSeconds(30);
    public static final Duration BACKOFF_MAX = Duration.ofHours(1);
    /** 読めない版の行の先送り。 */
    public static final Duration UNSUPPORTED_VERSION_DEFER = Duration.ofSeconds(60);

    /** メトリクス名（tag {@code source}）。 */
    public static final String METRIC_PREFIX = "mannschaft.notification.outbox.";
    public static final String METRIC_RELAYED = METRIC_PREFIX + "relayed";
    public static final String METRIC_FAILED = METRIC_PREFIX + "failed";
    public static final String METRIC_DEAD = METRIC_PREFIX + "dead";
    public static final String METRIC_RECOVERED = METRIC_PREFIX + "recovered";
    public static final String METRIC_STALE_MARK = METRIC_PREFIX + "stale_mark";
    public static final String METRIC_UNSUPPORTED_VERSION = METRIC_PREFIX + "unsupported_version";
    public static final String METRIC_NUDGE_REJECTED = METRIC_PREFIX + "nudge_rejected";
    public static final String METRIC_OLDEST_PENDING_AGE_SECONDS = METRIC_PREFIX + "oldest_pending_age_seconds";
    public static final String METRIC_POLLER_LAST_SUCCESS_EPOCH = METRIC_PREFIX + "poller_last_success_epoch";

    private final List<NotificationOutboxSource> sources;
    private final NotificationOutboxIngestService ingestService;
    private final NotificationOutboxPayloadCodec codec;
    private final ObjectProvider<MeterRegistry> meterRegistryProvider;
    private final Clock clock;
    private final boolean nudgeEnabled;

    public NotificationOutboxRelay(List<NotificationOutboxSource> sources,
                                   NotificationOutboxIngestService ingestService,
                                   NotificationOutboxPayloadCodec codec,
                                   ObjectProvider<MeterRegistry> meterRegistryProvider,
                                   Clock clock,
                                   @Value("${mannschaft.notification.outbox.nudge-enabled:true}") boolean nudgeEnabled) {
        this.sources = sources;
        this.ingestService = ingestService;
        this.codec = codec;
        this.meterRegistryProvider = meterRegistryProvider;
        this.clock = clock;
        this.nudgeEnabled = nudgeEnabled;
    }

    /** 起こし（AFTER_COMMIT・{@code notification-outbox-pool}）。 */
    public void onAppended(NotificationOutboxAppendedEvent event) {
        throw new UnsupportedOperationException("出陣で実装: NotificationOutboxRelay#onAppended");
    }

    /** 予備ポーラー（5秒ごと・ShedLock で単一ノード）。成功したら {@code poller_last_success_epoch} を更新する。 */
    public void poll() {
        throw new UnsupportedOperationException("出陣で実装: NotificationOutboxRelay#poll");
    }

    /**
     * 全 source を順に回り、取り込み待ちが空になるか打ち切り時間に達するまで drain する。
     *
     * @return RELAYED にした行数
     */
    public int drainAll() {
        throw new UnsupportedOperationException("出陣で実装: NotificationOutboxRelay#drainAll");
    }
}
