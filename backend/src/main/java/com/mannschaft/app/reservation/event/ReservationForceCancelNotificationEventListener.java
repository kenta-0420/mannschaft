package com.mannschaft.app.reservation.event;

import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.format.DateTimeFormatter;

/**
 * 定期予約不可枠の強行登録で予約をキャンセルされた申込者へ通知するリスナー
 * （F03.4.5 §6.2 W2-5・殿の裁定 2026-07-30）。
 *
 * <p><b>トランザクション設計</b>: キャンセルは登録 TX の副作用であるため
 * {@code @TransactionalEventListener(AFTER_COMMIT)} で購読し、{@code @Async("event-pool")} で
 * 管理者の登録応答をブロックしない（{@link ReservationWaitlistNotificationEventListener} と同型）。
 * ロールバックされた登録では通知が飛ばない（幻のキャンセル通知を作らない）。
 * 本リスナー自身には {@code @Transactional} を付けない（AFTER_COMMIT 時点で ambient tx が無く、
 * かつ通知失敗で全 SpringBootTest を巻き添えにしないため・
 * {@code feedback_transactional_event_listener_requires_new} の作法）。</p>
 *
 * <p><b>通知種別は既存の {@code RESERVATION_CANCELLED} を再利用する</b>（HIGH / sourceType=RESERVATION）。
 * 実態は「管理者による予約キャンセル」そのものであり、新種別を足すと
 * {@code NotificationType} は全ドメイン共有の enum なので種別数ガード
 * （{@code NotificationTypeTest}）や通知設定 UI まで巻き添えにする。意味が既存種別と完全に一致する場合に
 * 新種別を作るのは不要な結合を増やすだけである。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationForceCancelNotificationEventListener {

    /** 既存の通知種別を再利用する（{@code NotificationType.RESERVATION_CANCELLED}・HIGH）。 */
    static final String NOTIFICATION_TYPE = "RESERVATION_CANCELLED";

    /** 通知 sourceType（F00 visibility / 受信権の判定キー・予約ドメイン共通）。 */
    static final String SOURCE_TYPE = "RESERVATION";

    private static final DateTimeFormatter SLOT_AT_FORMAT = DateTimeFormatter.ofPattern("M月d日 HH:mm");

    private final NotificationHelper notificationHelper;

    /**
     * 強行キャンセルイベントを受信し、申込者へ「管理者により予約がキャンセルされた」旨を通知する。
     *
     * @param event 強行キャンセルイベント
     */
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onForceCancelled(ReservationForceCancelledByBlockEvent event) {
        try {
            notificationHelper.notify(
                    event.getUserId(),
                    NOTIFICATION_TYPE,
                    "ご予約がキャンセルされました",
                    buildBody(event),
                    SOURCE_TYPE,
                    event.getReservationId(),
                    NotificationScopeType.TEAM,
                    event.getTeamId(),
                    "/teams/" + event.getTeamId() + "/reservations",
                    null);
        } catch (Exception e) {
            // AFTER_COMMIT の副作用失敗。キャンセルは既にコミット済みのため波及させず、正直に記録する
            // （通知が届かなかったことを運用が検知できるよう ERROR で残す）。
            log.error("強行キャンセルの申込者通知に失敗しました: teamId={}, reservationId={}, userId={}",
                    event.getTeamId(), event.getReservationId(), event.getUserId(), e);
        }
    }

    /**
     * 通知本文を組み立てる。事由ラベルは管理者の自由入力だが、
     * 本通知は<b>当該予約の本人だけ</b>に届く（会員全員への公開ではない）ため、
     * {@code is_public} に関わらず理由として提示してよい（§4.4 の PII 注意は公開表示の話）。
     */
    private String buildBody(ReservationForceCancelledByBlockEvent event) {
        String slotAt = event.getSlotStartAt() != null
                ? event.getSlotStartAt().format(SLOT_AT_FORMAT) : "お申し込み";
        String title = event.getSlotTitle() != null ? event.getSlotTitle() : "ご予約";
        String reason = event.getBlockReason() != null && !event.getBlockReason().isBlank()
                ? "（" + event.getBlockReason() + "）" : "";
        return String.format(
                "%s の「%s」は、この時間帯が毎週の予約不可時間%sに設定されたためキャンセルとなりました。"
                        + "ご不便をおかけして申し訳ありません。別の時間帯でのご予約をご検討ください。",
                slotAt, title, reason);
    }
}
