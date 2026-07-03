package com.mannschaft.app.reservation.event;

import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.mail.outbox.EmailOutboxRequest;
import com.mannschaft.app.mail.outbox.EmailOutboxService;
import com.mannschaft.app.reservation.entity.ReservationEntity;
import com.mannschaft.app.reservation.entity.ReservationNotificationRecipientEntity;
import com.mannschaft.app.reservation.entity.ReservationSlotEntity;
import com.mannschaft.app.reservation.repository.ReservationNotificationRecipientRepository;
import com.mannschaft.app.reservation.repository.ReservationRepository;
import com.mannschaft.app.reservation.repository.ReservationSlotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 予約通知メール送出リスナー（機能D・§5.D）。
 *
 * <p>メンバーの予約成立（{@link ReservationCreatedEvent}）ごとに、
 * {@code reservation_notification_recipients} に登録された<b>有効宛先</b>へ
 * 「日時＋メニュー＋予約者名」をメール送出（{@code EmailOutboxService.enqueue}）する。
 * 1 record = 1 宛先。</p>
 *
 * <p><b>既存の管理者通知（{@link ReservationAdminNotificationEventListener}・アプリ内/Push で
 * ADMIN ユーザーへ）とは役割分離した別クラス・別 Bean</b>。同一 {@code ReservationCreatedEvent} を
 * 別リスナーが購読するだけで、二重通知・混同はしない（D はメールで任意アドレスへ）。</p>
 *
 * <p><b>トランザクション設計（必須）</b>: enqueue が DB 書き込みを伴うため
 * {@code @TransactionalEventListener(AFTER_COMMIT)} ＋ {@code @Transactional(REQUIRES_NEW)} ＋ {@code @Async}。
 * 素の {@code @Transactional}（=REQUIRED）は {@code TransactionalEventListenerFactory} の起動時
 * バリデーションで ApplicationContext がロード不能になり全 SpringBootTest が巻き添えで落ちる
 * 既知の重大地雷（feedback_transactional_event_listener_requires_new）。リマインドリスナーと同じ轍。</p>
 *
 * <p><b>「日時」は来店日時であって申込時刻ではない</b>: 本文の「日時」は来店予定日時
 * （{@code slot_date} + {@code start_time}）であって、イベントの {@code bookedAtFormatted}
 * （＝申込時刻 {@code reservations.booked_at}）ではない。イベントには slot の日時が無いため、
 * リスナーが {@code reservationId → reservation → slot} を辿って来店日時を組み立てる。
 * メニューは {@code event.slotTitle} をそのまま使う（slot 再解決不要）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationRecipientEmailEventListener {

    /** 来店日時の整形フォーマット（例: 2026/07/03 10:00）。 */
    private static final DateTimeFormatter ARRIVAL_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm");

    /** EmailOutbox テンプレート種別（スルー方式・§5.D の3点セット）。 */
    private static final String TEMPLATE_KIND = "RESERVATION_RECEIVED_NOTIFY";

    /** 送出元ドメイン（EmailOutboxRequest 必須項目）。 */
    private static final String SOURCE_DOMAIN = "reservation";

    /** ロケール（MVP は 'ja' 固定・必須項目）。 */
    private static final String LOCALE = "ja";

    private final ReservationNotificationRecipientRepository recipientRepository;
    private final ReservationRepository reservationRepository;
    private final ReservationSlotRepository slotRepository;
    private final NameResolverService nameResolverService;
    private final EmailOutboxService emailOutboxService;

    /**
     * 予約作成イベントを受信し、有効宛先ごとにメールを enqueue する。
     *
     * <p>AUTO / MANUAL の両方で予約作成時に送出する（承認待ちでも「予約が入った」ことを店側に伝える）。</p>
     *
     * @param event 予約作成イベント
     */
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onReservationCreated(ReservationCreatedEvent event) {
        List<ReservationNotificationRecipientEntity> recipients =
                recipientRepository.findByTeamIdAndIsEnabledTrue(event.getTeamId());
        if (recipients.isEmpty()) {
            return;
        }

        // reservationId → reservation → slot を辿って「来店日時」を組む（メニューはイベントから直接）。
        ReservationEntity reservation = reservationRepository.findById(event.getReservationId()).orElse(null);
        if (reservation == null) {
            log.warn("予約通知メール: 予約が解決できないため送出スキップ: reservationId={}", event.getReservationId());
            return;
        }
        ReservationSlotEntity slot = slotRepository.findById(reservation.getReservationSlotId()).orElse(null);
        if (slot == null) {
            log.warn("予約通知メール: 枠が解決できないため送出スキップ: reservationId={}, slotId={}",
                    event.getReservationId(), reservation.getReservationSlotId());
            return;
        }

        LocalDateTime arrivalAt = LocalDateTime.of(slot.getSlotDate(), slot.getStartTime());
        String arrivalFormatted = arrivalAt.format(ARRIVAL_FORMATTER);
        String menuTitle = event.getSlotTitle();
        String reserverName = nameResolverService.resolveUserFullName(event.getActorUserId());

        String subject = "予約が入りました";
        String body = buildBody(arrivalFormatted, menuTitle, reserverName, event.getBookedAtFormatted());

        for (ReservationNotificationRecipientEntity recipient : recipients) {
            // 1 宛先の送出失敗は他宛先を巻き込まない（行単位 try/catch・握りつぶさない）。
            try {
                emailOutboxService.enqueue(new EmailOutboxRequest(
                        TEMPLATE_KIND,
                        LOCALE,
                        recipient.getEmail(),
                        Map.of("subject", subject, "body", body),
                        SOURCE_DOMAIN,
                        // 冪等キーの nonce: 同一予約×同一 email は同一キーになり二重送出を防ぐ。
                        "reservation-notify:" + event.getReservationId() + ":" + recipient.getEmail(),
                        null,   // idempotencyKey は enqueue 側で合成（userId=null→"0"）
                        null,   // userId=null（非ユーザー宛先を許可）
                        null    // organizationId
                ));
            } catch (Exception e) {
                log.error("予約通知メールの enqueue に失敗しました: reservationId={}, teamId={}, recipientId={}",
                        event.getReservationId(), event.getTeamId(), recipient.getId(), e);
            }
        }
    }

    /**
     * メール本文を組み立てる。「日時」＝来店日時（slot 日時）。申込日時は補助として併記する。
     */
    private String buildBody(String arrivalFormatted, String menuTitle, String reserverName,
                             String bookedAtFormatted) {
        StringBuilder sb = new StringBuilder();
        sb.append("予約が入りました。\n\n");
        sb.append("日時: ").append(arrivalFormatted).append("\n");
        sb.append("メニュー: ").append(menuTitle != null ? menuTitle : "（未設定）").append("\n");
        sb.append("予約者名: ").append(reserverName).append("\n");
        if (bookedAtFormatted != null && !bookedAtFormatted.isBlank()) {
            sb.append("\n（申込日時: ").append(bookedAtFormatted).append("）");
        }
        return sb.toString();
    }
}
