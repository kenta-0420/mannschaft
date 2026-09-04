package com.mannschaft.app.event.listener;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.event.event.EventCareNotificationTriggerEvent;
import com.mannschaft.app.family.service.CareEventNotificationService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * F03.12 ケア対象者見守り通知の配送リスナー（Issue #2990 L5 TX_NOTIFY_BARE 是正）。
 *
 * <h2>是正前の欠陥 — 何が巻き戻っていたか</h2>
 * <p>是正前は 4 つの業務メソッドが {@code @Transactional} の内側から
 * {@code CareEventNotificationService#notifyCheckin} /
 * {@code #notifyRsvpConfirmed} を直接呼んでいた。これらは既定の {@code REQUIRED} 伝播で
 * 呼び出し元の業務トランザクションに参加するため、見守り者への通知（{@code createNotification}）や
 * 通知ログ（{@code event_care_notification_logs}）の INSERT が失敗すると、
 * <b>業務処理ごと巻き戻っていた</b>。巻き戻る内容は次のとおり。</p>
 * <ul>
 *   <li>{@code EventCheckinService#staffCheckin} — チケットの {@code use()}（使用済み化）、
 *       {@code event_checkins} 行、イベントのチェックイン数カウンタ加算。
 *       会場入口でスタッフが QR をスキャンしたのに、来場記録が一切残らない。</li>
 *   <li>{@code EventCheckinService#selfCheckin} — 同上（セルフチェックイン経路）。</li>
 *   <li>{@code EventRsvpService#submitRsvp} — {@code event_rsvp_responses} 行。
 *       利用者の出欠回答そのものが失われる。</li>
 *   <li>{@code EventRollCallService#submitRollCall} — 点呼セッション 1 回ぶんの
 *       {@code event_checkins} 行（新規・更新）が<b>全件</b>巻き戻る。
 *       ケア対象者 1 人の見守り通知の失敗で、その回に点呼した参加者全員の出欠が消える。</li>
 * </ul>
 *
 * <h2>是正後</h2>
 * <p>業務サービスは {@link EventCareNotificationTriggerEvent} を publish するだけに留め、
 * 本リスナーが {@code AFTER_COMMIT} + {@code @Async("event-pool")} で受け取って
 * {@code CareEventNotificationService} を呼ぶ。ケア対象者 1 人ぶんの失敗が他を巻き添えに
 * しないよう、ループ内 {@code try/catch} で隔離する（{@link EventDismissalNotificationListener}
 * の {@code notifyGuardiansForCareRecipients} と同型）。</p>
 *
 * <h2>{@code CareEventNotificationService} は変更していない</h2>
 * <p>同サービスは「通知そのものが業務目的」のクラスであり CMP-056 / #2990 の一括適用対象外である。
 * 変えたのは<b>呼び出し位置</b>だけで、ケアリンク判定・冪等チェック・本文組み立て・通知ログ記録は
 * すべて同サービスの中に残っている。{@code EventDismissalNotificationListener} が
 * 解散通知について既に取った方針と同一である。</p>
 *
 * <h2>配信面の等価性</h2>
 * <p>是正前も {@code createNotification} + {@code dispatchService.dispatch} を
 * {@code CareEventNotificationService} 内で行っていた。呼び出し位置だけを移したため、
 * Push / WebSocket の有無は変わらない（{@code NotificationDeliveryRunner} への置換は行わない。
 * 冪等ログ記録と通知作成を 1 トランザクションに保つ必要があるため）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EventCareNotificationTriggerListener {

    private final CareEventNotificationService careEventNotificationService;

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "ケア対象者の見守り通知は行事（CORE）の一部であり棚卸し台帳に停止用の gate_key を"
                    + "持たない。落とすと保護者・見守り者は子や高齢の家族が会場へ到着したか否かを"
                    + "知る手段を失い、不在アラートの前提も崩れる。イベントは再生されないため常時実行する")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onEventCareNotificationTrigger(EventCareNotificationTriggerEvent event) {
        List<Long> recipients = event.careRecipientUserIds();
        if (event.eventId() == null || event.kind() == null || recipients == null || recipients.isEmpty()) {
            return;
        }

        int failed = 0;
        Long firstFailedUserId = null;
        for (Long careRecipientUserId : recipients) {
            try {
                switch (event.kind()) {
                    case RSVP_CONFIRMED ->
                            careEventNotificationService.notifyRsvpConfirmed(careRecipientUserId, event.eventId());
                    case CHECKIN ->
                            careEventNotificationService.notifyCheckin(careRecipientUserId, event.eventId());
                }
            } catch (Exception e) {
                failed++;
                if (firstFailedUserId == null) {
                    firstFailedUserId = careRecipientUserId;
                }
                // 非同期イベント失敗の監査記録（規約上必須）。catch は業務TX外なので rollback で消えない。
                log.error("ケア対象者見守り通知の配送に失敗しました: eventId={}, kind={}, careRecipientUserId={}",
                        event.eventId(), event.kind(), careRecipientUserId, e);
            }
        }

        if (failed > 0) {
            log.error("ケア対象者見守り通知の一括配送の結果: eventId={}, kind={}, total={}, failed={}, "
                            + "firstFailedUserId={}",
                    event.eventId(), event.kind(), recipients.size(), failed, firstFailedUserId);
        }
    }
}
