package com.mannschaft.app.shift.event;

import com.mannschaft.app.membership.fanout.TeamFanoutRecipientSource;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.fanout.NotificationFanoutJobService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * シフト公開通知リスナー。
 * ShiftPublishedEvent を購読し、チーム全員に「シフトが公開されました」通知を送信する。
 *
 * <h2>fan-out 抜本改修 Wave-1: 受信者展開はワーカーへ移譲</h2>
 * <p>従来はチーム全員を同期展開し per-user に通知していた（受信者数に比例した O(n) 応答・N+1）。
 * 本リスナーは受信者を一切展開せず、{@link NotificationFanoutJobService#enqueue} で耐久ジョブを
 * <b>1 件 enqueue</b> するだけ（O(1)）とし、実配信は裏ワーカー {@code NotificationFanoutWorker} が
 * TEAM 受信者ソース（{@link TeamFanoutRecipientSource}）でチャンク送り・クラッシュ再開可能に配信する。</p>
 *
 * <h2>best-effort・冪等（村還流 {@code VillageEventFeedRefluxService} に倣う）</h2>
 * <p>本リスナーは AFTER_COMMIT で非同期に走るため、シフト公開の状態確定は既に確定済み。fan-out の失敗は
 * 内部で捕捉してログに留め、業務トランザクションを巻き込まない（例外を外へ伝播しない best-effort）。
 * 冪等キー {@code source_event_uuid} は「スケジュール公開」という論理イベントから決定的に導出するため、
 * 同一スケジュール公開の二重発火は {@code uk_fanout_idempotency} で 1 ジョブに収束する。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShiftPublishedNotificationListener {

    private static final String NOTIFICATION_TYPE = "SHIFT_PUBLISHED";
    private static final String SOURCE_TYPE = "SHIFT_SCHEDULE";

    private final NotificationFanoutJobService fanoutJobService;

    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onShiftPublished(ShiftPublishedEvent event) {
        try {
            fanoutJobService.enqueue(
                    TeamFanoutRecipientSource.SCOPE_TYPE,        // 戦略キー: TEAM
                    String.valueOf(event.getTeamId()),           // scope_ref: チーム ID 文字列
                    NOTIFICATION_TYPE,
                    sourceEventUuid(event.getScheduleId()),      // 冪等キー: スケジュール公開イベント UUID
                    null,                                        // organizationId: シフト公開は org 非依存
                    "シフトが公開されました",
                    "シフトスケジュールが確定・公開されました。内容を確認してください。",
                    NotificationPriority.NORMAL,
                    SOURCE_TYPE, event.getScheduleId(),
                    "/shifts/schedules/" + event.getScheduleId(),
                    event.getTriggeredByUserId());

            log.info("シフト公開通知を enqueue: scheduleId={}, teamId={}（受信者展開はワーカーへ移譲）",
                    event.getScheduleId(), event.getTeamId());
        } catch (Exception e) {
            // best-effort: シフト公開は既に確定済み。fan-out enqueue 失敗は通知の欠落に留め、業務は巻き戻さない。
            log.error("シフト公開通知の enqueue に失敗: scheduleId={}", event.getScheduleId(), e);
        }
    }

    /**
     * スケジュール公開という論理イベントから冪等キー UUID を決定的に導出する。
     * 同一スケジュールの公開通知を二重発火しても同一 UUID となり {@code uk_fanout_idempotency} で 1 ジョブに収束する。
     */
    private static UUID sourceEventUuid(Long scheduleId) {
        return UUID.nameUUIDFromBytes(("SHIFT_PUBLISHED_SCHEDULE:" + scheduleId).getBytes(StandardCharsets.UTF_8));
    }
}
