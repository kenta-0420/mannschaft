package com.mannschaft.app.reservation.event;

import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.stream.Collectors;

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

    /** Issue #2715 ロットC-3: 受信者 locale の解決（D-5: auth の UserRepository を直接呼ばない）。 */
    private final UserLocaleCache userLocaleCache;

    /** Issue #2715 ロットC-3: 通知件名・本文を受信者 locale で組み立てるために用いる。 */
    private final MessageSource messageSource;

    /**
     * 強行キャンセルイベントを受信し、申込者へ「管理者により予約がキャンセルされた」旨を通知する。
     *
     * <p>受信者は 1 イベントにつき 1 名（{@link ReservationForceCancelledByBlockEvent#getUserId()}）
     * のため、locale 解決はここでは単発 {@link UserLocaleCache#getLocale(Long)} で十分であり
     * バルク API は不要（N+1 の懸念自体が生じない・ループ無し）。</p>
     *
     * @param event 強行キャンセルイベント
     */
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onForceCancelled(ReservationForceCancelledByBlockEvent event) {
        try {
            Locale locale = Locale.forLanguageTag(userLocaleCache.getLocale(event.getUserId()));
            String title = messageSource.getMessage(
                    "notification.reservation.forceCancelled.title", null,
                    "ご予約がキャンセルされました", locale);
            notificationHelper.notify(
                    event.getUserId(),
                    NOTIFICATION_TYPE,
                    title,
                    buildBody(event, locale),
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
     *
     * <p>Issue #2543: グループ予約の兄弟行まで含め、<b>そのユーザーについて実際にキャンセルされた
     * 枠を全て列挙</b>する。「枠が 1 つ消えた」ように見える本文と実際の消失数の不一致を防ぐ。</p>
     *
     * <p>Issue #2715 ロットC-3: 受信者 locale で組み立てる。</p>
     */
    private String buildBody(ReservationForceCancelledByBlockEvent event, Locale locale) {
        String slotList = event.getCancelledSlots().stream()
                .map(slot -> formatSlot(slot, locale))
                .collect(Collectors.joining("、"));
        String reason = event.getBlockReason() != null && !event.getBlockReason().isBlank()
                ? messageSource.getMessage(
                        "notification.reservation.forceCancelled.reasonSuffix",
                        new Object[]{event.getBlockReason()}, "（" + event.getBlockReason() + "）", locale)
                : "";
        return messageSource.getMessage(
                "notification.reservation.forceCancelled.body",
                new Object[]{reason, slotList},
                "以下のご予約は、この時間帯が毎週の予約不可時間" + reason + "に設定されたためキャンセルとなりました。"
                        + "ご不便をおかけして申し訳ありません。別の時間帯でのご予約をご検討ください。\n" + slotList,
                locale);
    }

    private String formatSlot(ReservationForceCancelledByBlockEvent.CancelledSlot slot, Locale locale) {
        String slotAt = slot.slotStartAt() != null
                ? slot.slotStartAt().format(SLOT_AT_FORMAT)
                : messageSource.getMessage(
                        "notification.reservation.forceCancelled.defaultSlotAt", null, "お申し込み", locale);
        String title = slot.slotTitle() != null
                ? slot.slotTitle()
                : messageSource.getMessage(
                        "notification.reservation.common.defaultSlotTitle", null, "ご予約", locale);
        return messageSource.getMessage(
                "notification.reservation.forceCancelled.slotFormat",
                new Object[]{slotAt, title}, slotAt + " の「" + title + "」", locale);
    }
}
