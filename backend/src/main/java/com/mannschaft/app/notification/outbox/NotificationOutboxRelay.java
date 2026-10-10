package com.mannschaft.app.notification.outbox;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

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
 * <p>失敗の扱い: 取り込みの例外は {@code attempt_count+1} と指数バックオフで PENDING に戻し、{@link #MAX_ATTEMPTS} 回目で
 * DEAD にする。印付けの例外は行を RELAYING のまま残す（回収バッチが2分後に PENDING へ戻し、取り込みは冪等なので
 * 再取り込みしても結果は1件）。印付けが0行なら古い世代（回収後に別の relay が claim し直した）として何も変えない。</p>
 */
@Slf4j
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
    /** {@code last_error} の列幅（文字数）。 */
    static final int LAST_ERROR_MAX_LENGTH = 500;

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
    /** メトリクスの tag 名。 */
    public static final String TAG_SOURCE = "source";

    private final List<NotificationOutboxSource> sources;
    private final NotificationOutboxIngestService ingestService;
    private final NotificationOutboxPayloadCodec codec;
    private final ObjectProvider<MeterRegistry> meterRegistryProvider;
    private final Clock clock;
    private final boolean nudgeEnabled;
    /** 予備ポーラーが最後に成功したエポック秒（ゲージ {@code poller_last_success_epoch}）。 */
    private final AtomicLong pollerLastSuccessEpoch = new AtomicLong();

    public NotificationOutboxRelay(List<NotificationOutboxSource> sources,
                                   NotificationOutboxIngestService ingestService,
                                   NotificationOutboxPayloadCodec codec,
                                   ObjectProvider<MeterRegistry> meterRegistryProvider,
                                   Clock clock,
                                   @Value("${mannschaft.notification.outbox.nudge-enabled:true}") boolean nudgeEnabled) {
        this.sources = List.copyOf(sources);
        this.ingestService = ingestService;
        this.codec = codec;
        this.meterRegistryProvider = meterRegistryProvider;
        this.clock = clock;
        this.nudgeEnabled = nudgeEnabled;
    }

    /**
     * 起動時にゲージを登録する。最古の PENDING の経過秒は source ごと（読まれた時点で問い合わせ、無ければ0）、
     * 予備ポーラーの最終成功時刻は1本（OB12a・OB12b）。
     */
    @PostConstruct
    void registerGauges() {
        MeterRegistry registry = meterRegistryProvider.getIfAvailable();
        if (registry == null) {
            return;
        }
        for (NotificationOutboxSource source : sources) {
            Gauge.builder(METRIC_OLDEST_PENDING_AGE_SECONDS, source, this::oldestPendingAgeSeconds)
                    .tag(TAG_SOURCE, source.name())
                    .description("最古の PENDING の outbox 行の経過秒（無ければ0）")
                    .strongReference(true)
                    .register(registry);
        }
        Gauge.builder(METRIC_POLLER_LAST_SUCCESS_EPOCH, pollerLastSuccessEpoch, AtomicLong::get)
                .description("通知 outbox の予備ポーラーが最後に成功したエポック秒")
                .strongReference(true)
                .register(registry);
    }

    /** 起こし（AFTER_COMMIT・{@code notification-outbox-pool}）。 */
    @Async("notification-outbox-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "起こしを止めても予備ポーラーが拾うが、止める理由が無く、止めると通知の遅延が5秒以上に延びるため常に動かす")
    public void onAppended(NotificationOutboxAppendedEvent event) {
        if (!nudgeEnabled) {
            return;
        }
        List<NotificationOutboxSource> targets = sources.stream()
                .filter(source -> source.name().equals(event.sourceName()))
                .toList();
        drain(targets.isEmpty() ? sources : targets);
    }

    /** 予備ポーラー（5秒ごと・ShedLock で単一ノード）。成功したら {@code poller_last_success_epoch} を更新する。 */
    @Scheduled(fixedDelay = 5000)
    @SchedulerLock(name = "notificationOutboxRelay", lockAtMostFor = "PT1M", lockAtLeastFor = "PT1S")
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "止めると起こしを取りこぼした通知の予約が outbox に滞留し、利用者へ通知が届かないまま積み上がる")
    public void poll() {
        drainAll();
        pollerLastSuccessEpoch.set(Instant.now(clock).getEpochSecond());
    }

    /**
     * 全 source を順に回り、取り込み待ちが空になるか打ち切り時間に達するまで drain する。
     *
     * @return RELAYED にした行数
     */
    public int drainAll() {
        return drain(sources);
    }

    /**
     * 指定の source を順に1回ずつ claim して取り込み、どの source も空になるまで繰り返す（source 間の公平性）。
     * 打ち切り時間は claim の前に見る（claim した行は最後まで処理し、RELAYING のまま放置しない）。
     */
    private int drain(List<NotificationOutboxSource> targets) {
        long deadline = System.nanoTime() + DRAIN_TIME_BUDGET.toNanos();
        int relayed = 0;
        boolean claimedAny;
        do {
            claimedAny = false;
            for (NotificationOutboxSource source : targets) {
                if (System.nanoTime() - deadline >= 0) {
                    log.info("通知 outbox の drain を打ち切り時間 {} で止める（残りは次の起こし・ポーラーが拾う）",
                            DRAIN_TIME_BUDGET);
                    return relayed;
                }
                List<NotificationOutboxMessage> claimed = source.claim(CLAIM_LIMIT, Instant.now(clock));
                if (claimed.isEmpty()) {
                    continue;
                }
                claimedAny = true;
                for (NotificationOutboxMessage message : claimed) {
                    if (relayOne(source, message)) {
                        relayed++;
                    }
                }
            }
        } while (claimedAny);
        return relayed;
    }

    /** claim 済みの1行を取り込み、印を付ける。RELAYED にできたら true。例外は外へ出さない（行ごとに閉じる）。 */
    private boolean relayOne(NotificationOutboxSource source, NotificationOutboxMessage message) {
        if (!codec.supports(message.payloadVersion())) {
            deferUnsupportedVersion(source, message);
            return false;
        }

        try {
            NotificationOutboxPayload payload = codec.decode(message.payloadVersion(), message.payloadJson());
            ingestService.ingest(payload);
        } catch (RuntimeException e) {
            recordFailure(source, message, e);
            return false;
        }

        try {
            if (!source.markRelayed(message.id(), message.claimToken(), Instant.now(clock))) {
                staleMark(source, message, "markRelayed");
                return false;
            }
        } catch (RuntimeException e) {
            // 取り込みはコミット済み。行は RELAYING のまま残り、回収バッチが PENDING に戻す（再取り込みは冪等）
            log.error("通知 outbox の印付け（RELAYED）に失敗。回収バッチが戻して再取り込みする: source={} id={}",
                    source.name(), message.id(), e);
            return false;
        }
        increment(METRIC_RELAYED, source);
        return true;
    }

    /** 読めない版は失敗に数えず、PENDING のまま先送りする（OB20）。 */
    private void deferUnsupportedVersion(NotificationOutboxSource source, NotificationOutboxMessage message) {
        increment(METRIC_UNSUPPORTED_VERSION, source);
        log.warn("通知 outbox の payload_version={} を読めない。{} 後に再試行する（reader の展開待ち）: source={} id={}",
                message.payloadVersion(), UNSUPPORTED_VERSION_DEFER, source.name(), message.id());
        Instant now = Instant.now(clock);
        try {
            if (!source.deferUnsupportedVersion(message.id(), message.claimToken(),
                    now.plus(UNSUPPORTED_VERSION_DEFER), now)) {
                staleMark(source, message, "deferUnsupportedVersion");
            }
        } catch (RuntimeException e) {
            log.error("通知 outbox の先送りの記録に失敗。回収バッチが戻す: source={} id={}",
                    source.name(), message.id(), e);
        }
    }

    /** 取り込みの失敗を記録する。{@link #MAX_ATTEMPTS} 回目なら DEAD（OB06・OB07）。 */
    private void recordFailure(NotificationOutboxSource source, NotificationOutboxMessage message,
                               RuntimeException cause) {
        int attempt = message.attemptCount() + 1;
        boolean dead = attempt >= MAX_ATTEMPTS;
        Instant now = Instant.now(clock);
        Instant nextAttemptAt = now.plus(backoff(attempt));
        boolean marked;
        try {
            marked = source.markFailed(message.id(), message.claimToken(), lastError(cause), nextAttemptAt, dead,
                    now);
        } catch (RuntimeException e) {
            log.error("通知 outbox の失敗の記録に失敗。回収バッチが戻す: source={} id={} 取り込みの失敗={}",
                    source.name(), message.id(), cause.toString(), e);
            return;
        }
        if (!marked) {
            staleMark(source, message, "markFailed");
            return;
        }
        increment(METRIC_FAILED, source);
        if (dead) {
            increment(METRIC_DEAD, source);
            log.error("通知 outbox の取り込みが {} 回失敗したので DEAD にした（以後は取り込まない）: source={} id={}",
                    attempt, source.name(), message.id(), cause);
        } else {
            log.warn("通知 outbox の取り込みに失敗（{} 回目）。{} に再試行する: source={} id={}",
                    attempt, nextAttemptAt, source.name(), message.id(), cause);
        }
    }

    /** 印付けが古い世代で当たらなかった（OB08b）。状態は変えず、数えて警告だけ残す。 */
    private void staleMark(NotificationOutboxSource source, NotificationOutboxMessage message, String operation) {
        increment(METRIC_STALE_MARK, source);
        log.warn("通知 outbox の {} が古い世代で当たらなかった（回収後に別の relay が claim し直した）: source={} id={}",
                operation, source.name(), message.id());
    }

    /** 30秒×2^(n-1)、上限1時間。 */
    static Duration backoff(int attempt) {
        int exponent = Math.max(0, Math.min(attempt - 1, 30));
        long seconds = BACKOFF_BASE.getSeconds() << exponent;
        return seconds <= 0 || seconds > BACKOFF_MAX.getSeconds() ? BACKOFF_MAX : Duration.ofSeconds(seconds);
    }

    /** {@code last_error} に入れる文字列（列幅の文字数で切る。サロゲートペアを割らない）。 */
    static String lastError(Throwable cause) {
        String text = cause.getClass().getSimpleName() + ": " + cause.getMessage();
        if (text.codePointCount(0, text.length()) <= LAST_ERROR_MAX_LENGTH) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, LAST_ERROR_MAX_LENGTH));
    }

    private double oldestPendingAgeSeconds(NotificationOutboxSource source) {
        return source.oldestPendingCreatedAt()
                .map(createdAt -> Math.max(0.0, Duration.between(createdAt, Instant.now(clock)).toMillis() / 1000.0))
                .orElse(0.0);
    }

    private void increment(String name, NotificationOutboxSource source) {
        MeterRegistry registry = meterRegistryProvider.getIfAvailable();
        if (registry != null) {
            Counter.builder(name).tag(TAG_SOURCE, source.name()).register(registry).increment();
        }
    }
}
