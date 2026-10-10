package com.mannschaft.app.notification.outbox;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * claim されたまま止まった outbox の行（RELAYING で {@code claimed_at} が2分を超えたもの）を PENDING に戻す
 * （docs/architecture/notification_outbox.md §4.4）。毎分。
 *
 * <p>tx を持たず、各 source の {@link NotificationOutboxSource#recoverStuck}（送り手の REQUIRES_NEW）を呼ぶ。
 * 回収した件数をメトリクス {@code recovered{source}} に足す。</p>
 *
 * <p>【試練の骨格・出陣で実装】{@code @Scheduled(cron="0 * * * * *")}・
 * {@code @SchedulerLock(name="notificationOutboxStuckRecovery")}・
 * {@code @BatchEndpoint(name="notification-outbox-stuck-recovery")}・{@code @BackgroundFeaturePolicy(ALWAYS)} を付ける。</p>
 */
@Component
@RequiredArgsConstructor
public class NotificationOutboxStuckRecoveryBatch {

    /** バッチ一覧 API の名前。 */
    public static final String BATCH_NAME = "notification-outbox-stuck-recovery";

    private final List<NotificationOutboxSource> sources;

    /** RELAYING の残骸を PENDING に戻す。 */
    public void recover() {
        throw new UnsupportedOperationException("出陣で実装: NotificationOutboxStuckRecoveryBatch#recover");
    }
}
