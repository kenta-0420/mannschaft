package com.mannschaft.app.notification.outbox;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 取り込み済み・死んだ outbox の行を掃除する（docs/architecture/notification_outbox.md §4.5）。日次。
 *
 * <p>RELAYED は {@code relayed_at} から7日、DEAD は {@code dead_at} から30日を過ぎたものを削除する。
 * PENDING・RELAYING は消さない。tx を持たず、各 source の {@link NotificationOutboxSource#sweep} を呼ぶ。</p>
 *
 * <p>【試練の骨格・出陣で実装】{@code @Scheduled}（日次）・{@code @SchedulerLock(name="notificationOutboxSweep")}・
 * {@code @BatchEndpoint(name="notification-outbox-sweep")}・{@code @BackgroundFeaturePolicy} を付ける。</p>
 */
@Component
@RequiredArgsConstructor
public class NotificationOutboxSweepBatch {

    /** バッチ一覧 API の名前。 */
    public static final String BATCH_NAME = "notification-outbox-sweep";

    private final List<NotificationOutboxSource> sources;

    /** 保存期間を過ぎた RELAYED・DEAD を削除する。 */
    public void sweep() {
        throw new UnsupportedOperationException("出陣で実装: NotificationOutboxSweepBatch#sweep");
    }
}
