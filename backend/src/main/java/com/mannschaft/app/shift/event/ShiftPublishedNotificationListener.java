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
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
                    sourceEventUuid(event.getScheduleId(), event.getPublishedAt()), // 冪等キー: 公開イベント（scheduleId×publishedAt）
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
     * 「スケジュール公開」という論理イベントから冪等キー UUID を決定的に導出する。
     *
     * <p>冪等キーは {@code scheduleId × publishedAt}（公開時刻）で構成する。同一 AFTER_COMMIT の二重発火は
     * 同一 publishedAt ゆえ同一 UUID となり {@code uk_fanout_idempotency} で 1 ジョブに収束する（正当な抑止）。
     * 一方 PUBLISHED→ADJUSTING→再 PUBLISHED の<b>再公開</b>は publishedAt が更新されるため別 UUID となり、新ジョブが
     * 立って再通知される（scheduleId だけだと再公開通知が恒久抑止される回帰を防ぐ）。</p>
     *
     * <p>publishedAt が万一 null の場合（想定外だが防御）は scheduleId のみで導出する。</p>
     */
    private static UUID sourceEventUuid(Long scheduleId, LocalDateTime publishedAt) {
        String seed = "SHIFT_PUBLISHED_SCHEDULE:" + scheduleId + ":"
                + (publishedAt == null ? "-" : String.valueOf(publishedAt.toInstant(ZoneOffset.UTC).toEpochMilli()));
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
    }
}
