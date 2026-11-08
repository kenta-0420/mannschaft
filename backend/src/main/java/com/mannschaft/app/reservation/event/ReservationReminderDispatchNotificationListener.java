package com.mannschaft.app.reservation.event;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationDeliveryRequest;
import com.mannschaft.app.notification.service.NotificationDeliveryResult;
import com.mannschaft.app.notification.service.NotificationDeliveryRunner;
import com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser;
import com.mannschaft.app.reservation.service.ReservationReminderDispatchRunner;
import com.mannschaft.app.team.service.TeamService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

/**
 * 予約リマインドの通知配送リスナー（Issue #2997 / CMP-260827-1152 G3）。
 *
 * <p>{@link ReservationReminderDispatchRunner#publishOne} の項目TXが commit された後（{@code AFTER_COMMIT}）に
 * 非同期（{@code event-pool}）で発火する。配送の<b>前</b>に {@link ReservationReminderDispatchRunner#claim}
 * （条件付き UPDATE の先取り）で送信済みの印を取得し、取得できなければ他の配送が担当済みなので何もしない。</p>
 *
 * <ul>
 *   <li>配送が成功した、または deny（{@code VISIBILITY_DENIED}）なら、取得した印（SENT）を残す。</li>
 *   <li>配送が例外で失敗したら {@link ReservationReminderDispatchRunner#release} で PENDING に戻す（次周回で再送）。</li>
 *   <li>戻す処理が失敗したときは ERROR ログのみ（SENT が残るので再送されない。例外は外に出さない）。</li>
 *   <li><b>既知の限界（M1）</b>: claim の後でプロセスが落ちると、そのリマインドは失われる。</li>
 * </ul>
 *
 * <p>受信者は予約者本人 1 人。通知種別 {@code RESERVATION_REMINDER}・sourceType {@code RESERVATION}・sourceId＝予約ID・
 * スコープ TEAM・遷移先＝チームの予約画面・実行者なし（システム発）は是正前と同一。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationReminderDispatchNotificationListener {

    /** 設計書 §F04.3 連携の通知タイプ。 */
    static final String NOTIFICATION_TYPE = "RESERVATION_REMINDER";

    /** 通知 sourceType（F00 visibility / 受信権の判定キー）。 */
    static final String SOURCE_TYPE = "RESERVATION";

    private static final DateTimeFormatter SLOT_AT_FORMAT = DateTimeFormatter.ofPattern("M月d日 HH:mm");

    private final NotificationDeliveryRunner notificationDeliveryRunner;
    private final ReservationReminderDispatchRunner reminderRunner;
    private final UserLocaleCache userLocaleCache;
    private final MessageSource messageSource;
    private final Clock clock;
    /** 遷移先 URL の slug 解決用（FE ルートは slug のみ受ける。Repository は直接使わず Service 経由）。 */
    private final TeamService teamService;

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "対応する gate_key が無く停止条件を宣言できないため常時実行する。予約リマインドの配信。"
                    + "機能単位の閉栓が要るようになった時点で gate_key の発行から検討すること")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onReservationReminderDispatch(ReservationReminderDispatchEvent event) {
        if (event.userId() == null) {
            return;
        }

        // 配送の前に印を先取りする。取得できなければ他の配送が担当済みなので何もしない。
        // 戻すときの照合キーにするため、本番 DDL の sent_at は秒精度の DATETIME（V3.065）なので、秒に丸める。
        // 小数秒を持たせると保存時に切り捨てられ、release の sent_at = claimedAt が一致せず SENT が残って再送されない。
        LocalDateTime claimedAt = LocalDateTime.now(clock.withZone(UserZoneLocalDateTimeParser.SERVER_ZONE))
                .truncatedTo(ChronoUnit.SECONDS);
        try {
            if (!reminderRunner.claim(event.reminderId(), claimedAt)) {
                log.info("予約リマインドは配送済みまたは他の配送が担当済みのためスキップ: reminderId={}, reservationId={}",
                        event.reminderId(), event.reservationId());
                return;
            }
        } catch (Exception e) {
            log.error("予約リマインドの送信済み印の先取りに失敗したため配送しません（次周回で再試行）: reminderId={}, reservationId={}",
                    event.reminderId(), event.reservationId(), e);
            return;
        }

        try {
            NotificationDeliveryRequest request = buildRequest(event);
            NotificationDeliveryResult result = notificationDeliveryRunner.sendOne(request);
            if (result == NotificationDeliveryResult.VISIBILITY_DENIED) {
                // visibility deny（例外ではない）。NotificationService 側で既に WARN 済み。印は残す。
                log.warn("予約リマインドが visibility deny によりスキップされました: recipientUserId={}, "
                                + "notificationType={}, sourceType={}, sourceId={}",
                        request.recipientUserId(), request.notificationType(),
                        request.sourceType(), request.sourceId());
            } else {
                log.info("予約リマインド送出: reservationId={}, userId={}, slotDate={}, slotStartTime={}",
                        event.reservationId(), event.userId(), event.slotDate(), event.slotStartTime());
            }
        } catch (Exception e) {
            // 非同期イベント失敗の監査記録（規約上必須）。catch は TX 外なので rollback で消えない。
            log.error("予約リマインドの配送に失敗しました（印を戻して次周回で再送）: reminderId={}, reservationId={}, userId={}",
                    event.reminderId(), event.reservationId(), event.userId(), e);
            releaseClaim(event, claimedAt);
        }
    }

    /** 配送が例外で失敗したとき、先取りした印を戻して次周回で再送されるようにする。 */
    private void releaseClaim(ReservationReminderDispatchEvent event, LocalDateTime claimedAt) {
        try {
            reminderRunner.release(event.reminderId(), claimedAt);
        } catch (Exception e) {
            log.error("予約リマインドの送信済み印を戻せませんでした（配送失敗だが再送されない）: reminderId={}, reservationId={}",
                    event.reminderId(), event.reservationId(), e);
        }
    }

    /** チームの予約画面への遷移先。slug が解決できなければ数値 URL は作らず通知一覧へ逃がす。 */
    private String buildActionUrl(Long teamId) {
        String slug = teamService.getSlugById(teamId);
        return slug != null ? "/teams/" + slug + "/reservations" : "/notifications";
    }

    /** 通知配送要求を組み立てる（業務TX外・AFTER_COMMIT 後に実行される）。 */
    private NotificationDeliveryRequest buildRequest(ReservationReminderDispatchEvent event) {
        Locale locale = Locale.forLanguageTag(userLocaleCache.getLocale(event.userId()));
        String slotAtStr = event.slotDate().atTime(event.slotStartTime()).format(SLOT_AT_FORMAT);
        String slotTitle = event.slotTitle() != null ? event.slotTitle()
                : messageSource.getMessage("notification.reservation.common.defaultSlotTitle", null, "ご予約", locale);

        String title = messageSource.getMessage(
                "notification.reservation.reminder.title", null, "予約リマインド", locale);
        String body = messageSource.getMessage(
                "notification.reservation.reminder.body",
                new Object[]{slotAtStr, slotTitle},
                slotAtStr + " に「" + slotTitle + "」のご予約があります。",
                locale);
        return new NotificationDeliveryRequest(
                event.userId(),
                NOTIFICATION_TYPE,
                NotificationPriority.NORMAL,
                title,
                body,
                SOURCE_TYPE,
                event.reservationId(),
                NotificationScopeType.TEAM,
                event.teamId(),
                buildActionUrl(event.teamId()),
                null);
    }
}
