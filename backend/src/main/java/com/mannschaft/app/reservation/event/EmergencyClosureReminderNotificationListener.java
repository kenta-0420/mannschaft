package com.mannschaft.app.reservation.event;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.mail.outbox.EmailOutboxRequest;
import com.mannschaft.app.mail.outbox.EmailOutboxService;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.entity.NotificationEntity;
import com.mannschaft.app.notification.service.NotificationDeliveryRequest;
import com.mannschaft.app.notification.service.NotificationDeliveryRunner;
import com.mannschaft.app.reservation.entity.EmergencyClosureConfirmationEntity;
import com.mannschaft.app.reservation.entity.EmergencyClosureEntity;
import com.mannschaft.app.reservation.repository.EmergencyClosureConfirmationRepository;
import com.mannschaft.app.reservation.repository.EmergencyClosureRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;

/**
 * 臨時休業未確認リマインドの通知配送リスナー（Issue #2834 / CMP-056 第2群ロット1）。
 *
 * <p>{@code EmergencyClosureReminderRunner#markReminderSent} の独立トランザクションが commit された後
 * （{@code AFTER_COMMIT}）に非同期（{@code event-pool}）で発火する。<b>単一受信者</b>の金型として
 * 第1群の {@code ContactInviteUsedNotificationListener} と同型。</p>
 *
 * <h2>削除済み source を参照しないことの確認</h2>
 * <p>{@code sourceType=EMERGENCY_CLOSURE} / {@code sourceId=臨時休業ID} を参照するが、本バッチは
 * 臨時休業行にも確認行にも削除・論理削除を行わない（リマインド送信済み時刻を書くだけ）。
 * よってコミット後発火でも source は生存しており「静かな deny」は発生しない。</p>
 *
 * <h2>文面の材料を読み直す理由（検分是正）</h2>
 * <p>予約日時・件名・理由・本文・チームID・実行者IDはイベントに載せず、{@code confirmationId} /
 * {@code closureId} から読み直す。確定設計の「イベントには ID と種別だけ」への準拠であり、
 * 業務本文と PII を非同期イベントに載せないための是正でもある。上記のとおり本バッチは確認行・
 * 臨時休業行のいずれも削除しないため読み直しは必ず成功する（それでも読めなければ ERROR ログを
 * 残して配送を中止する）。</p>
 *
 * <h2>メール送信を通知と別の try に分ける理由</h2>
 * <p>outbox への enqueue が失敗しても、アプリ内通知は成功している。同じ try にまとめると
 * 成功した通知まで失敗として記録され、観測の解像度が落ちる。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmergencyClosureReminderNotificationListener {

    private static final DateTimeFormatter APPOINTMENT_FORMAT = DateTimeFormatter.ofPattern("M月d日 HH:mm");

    private final NotificationDeliveryRunner notificationDeliveryRunner;
    private final EmailOutboxService emailOutboxService;
    private final MessageSource messageSource;
    private final EmergencyClosureConfirmationRepository confirmationRepository;
    private final EmergencyClosureRepository closureRepository;

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "予約は棚卸し台帳で beta=コア・gate_key=null の常時提供機能であり、"
                    + "臨時休業の未確認リマインドだけを止める停止条件が存在しない。"
                    + "直前の予約に対する周知が届かないと来院事故につながるため常時実行する")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onEmergencyClosureReminderNotification(EmergencyClosureReminderNotificationEvent event) {
        if (event.recipientUserId() == null) {
            return;
        }
        Locale locale = (event.recipientLocale() == null || event.recipientLocale().isBlank())
                ? Locale.JAPANESE : Locale.forLanguageTag(event.recipientLocale());

        // 文面の材料はイベントではなく元の行から読み直す（本バッチは行を削除しないため生存している）。
        EmergencyClosureConfirmationEntity confirmation;
        EmergencyClosureEntity closure;
        try {
            confirmation = confirmationRepository.findById(event.confirmationId()).orElse(null);
            closure = closureRepository.findById(event.closureId()).orElse(null);
        } catch (Exception e) {
            log.error("臨時休業リマインドの元データ読み直しに失敗しました: phase={}, confirmationId={}, closureId={}",
                    event.phase(), event.confirmationId(), event.closureId(), e);
            return;
        }
        if (confirmation == null || closure == null) {
            log.error("臨時休業リマインドの元データが見つかりません（配送を中止）: "
                            + "phase={}, confirmationId={}, closureId={}, confirmationFound={}, closureFound={}",
                    event.phase(), event.confirmationId(), event.closureId(),
                    confirmation != null, closure != null);
            return;
        }

        String appointmentStr = confirmation.getAppointmentAt() == null
                ? "" : confirmation.getAppointmentAt().format(APPOINTMENT_FORMAT);

        String title;
        String body;
        try {
            title = buildTitle(event, closure, locale);
            body = buildBody(event, closure, locale, appointmentStr);
        } catch (Exception e) {
            log.error("臨時休業リマインド文面の組み立てに失敗しました: phase={}, confirmationId={}, closureId={}",
                    event.phase(), event.confirmationId(), event.closureId(), e);
            return;
        }

        try {
            NotificationDeliveryRequest request = buildRequest(event, closure, title, body);
            NotificationEntity created = notificationDeliveryRunner.sendOne(request);
            if (created == null) {
                // visibility deny（例外ではない）。NotificationService 側で既に WARN 済み。
                // deny のみのときは WARN に留め、ERROR と混ぜない。
                log.warn("臨時休業リマインド通知が visibility deny によりスキップされました: "
                                + "recipientUserId={}, notificationType={}, sourceType={}, sourceId={}, phase={}",
                        request.recipientUserId(), request.notificationType(),
                        request.sourceType(), request.sourceId(), event.phase());
            } else {
                log.info("臨時休業リマインド送信: phase={}, closureId={}, patientId={}, recipientUserId={}",
                        event.phase(), event.closureId(), confirmation.getUserId(), event.recipientUserId());
            }
        } catch (Exception e) {
            // 非同期イベント失敗の監査記録（規約上必須）。catch は TX 外なので rollback で消えない。
            log.error("臨時休業リマインド通知の配送に失敗しました: "
                            + "phase={}, recipientUserId={}, confirmationId={}, closureId={}",
                    event.phase(), event.recipientUserId(), event.confirmationId(), event.closureId(), e);
        }

        if (event.recipientEmail() == null || event.recipientEmail().isBlank()) {
            return;
        }
        try {
            emailOutboxService.enqueue(
                    buildEmailRequest(event, confirmation, closure, locale, title, body, appointmentStr));
        } catch (Exception e) {
            log.error("臨時休業リマインドメールの outbox 登録に失敗しました: "
                            + "phase={}, recipientUserId={}, confirmationId={}",
                    event.phase(), event.recipientUserId(), event.confirmationId(), e);
        }
    }

    private String buildTitle(EmergencyClosureReminderNotificationEvent event,
                              EmergencyClosureEntity closure, Locale locale) {
        if (event.phase() == EmergencyClosureReminderNotificationEvent.Phase.PATIENT) {
            return messageSource.getMessage(
                    "notification.reservation.emergencyClosure.patientReminder.title",
                    new Object[]{closure.getSubject()},
                    "【再送】" + closure.getSubject(),
                    locale);
        }
        return messageSource.getMessage(
                "notification.reservation.emergencyClosure.operatorAlert.title",
                new Object[]{event.patientName()},
                "【要確認】" + event.patientName() + "さんが臨時休業通知を未確認です",
                locale);
    }

    private String buildBody(EmergencyClosureReminderNotificationEvent event,
                             EmergencyClosureEntity closure, Locale locale, String appointmentStr) {
        if (event.phase() == EmergencyClosureReminderNotificationEvent.Phase.PATIENT) {
            return messageSource.getMessage(
                    "notification.reservation.emergencyClosure.patientReminder.body",
                    new Object[]{closure.getReason(), appointmentStr},
                    closure.getReason() + " — " + appointmentStr + "のご予約まで3時間前です。内容のご確認をお願いします。",
                    locale);
        }
        return messageSource.getMessage(
                "notification.reservation.emergencyClosure.operatorAlert.body",
                new Object[]{appointmentStr},
                appointmentStr + "の予約まで2時間を切りました。連絡が届いていない可能性があります。",
                locale);
    }

    /** 通知配送要求を組み立てる（業務TX外・AFTER_COMMIT 後に実行される）。 */
    private NotificationDeliveryRequest buildRequest(
            EmergencyClosureReminderNotificationEvent event, EmergencyClosureEntity closure,
            String title, String body) {
        // 患者宛は EMERGENCY_CLOSURE タイプで送ることで、通知リストに「確認しました」ボタンが表示される。
        String notificationType = event.phase() == EmergencyClosureReminderNotificationEvent.Phase.PATIENT
                ? "EMERGENCY_CLOSURE" : "CLOSURE_UNCONFIRMED_REMINDER";
        return new NotificationDeliveryRequest(
                event.recipientUserId(),
                notificationType,
                NotificationPriority.URGENT,
                title,
                body,
                "EMERGENCY_CLOSURE",
                closure.getId(),
                NotificationScopeType.TEAM,
                closure.getTeamId(),
                null,
                // 患者宛の実行者は臨時休業の送信者。送信者宛アラートは自動送信のため実行者を持たない。
                event.phase() == EmergencyClosureReminderNotificationEvent.Phase.PATIENT
                        ? closure.getCreatedBy() : null);
    }

    /** メール本文（是正前の組み立てをそのまま移送）。 */
    private EmailOutboxRequest buildEmailRequest(
            EmergencyClosureReminderNotificationEvent event,
            EmergencyClosureConfirmationEntity confirmation, EmergencyClosureEntity closure, Locale locale,
            String title, String body, String appointmentStr) {
        if (event.phase() == EmergencyClosureReminderNotificationEvent.Phase.PATIENT) {
            String htmlBody = String.format(
                    "<p><strong>%s</strong></p><p>%s</p><hr><p>%s</p>",
                    title, body, closure.getMessageBody());
            return new EmailOutboxRequest(
                    "RESERVATION_EMERGENCY_REMINDER",
                    locale.toLanguageTag(),
                    event.recipientEmail(),
                    Map.of("subject", title, "body", htmlBody),
                    "reservation",
                    "emergency-reminder:" + event.closureId() + ":" + confirmation.getUserId(),
                    null,
                    event.recipientUserId(),
                    null);
        }

        String emailLabelPatient = messageSource.getMessage(
                "notification.reservation.emergencyClosure.operatorAlert.emailLabelPatient", null, "患者名", locale);
        String emailLabelAppointment = messageSource.getMessage(
                "notification.reservation.emergencyClosure.operatorAlert.emailLabelAppointment", null, "予約日時", locale);
        String htmlBody = String.format(
                "<p><strong>%s</strong></p><p>%s</p><hr><p>%s: %s</p><p>%s: %s</p>",
                title, body, emailLabelPatient, event.patientName(), emailLabelAppointment, appointmentStr);
        String emailSubject = messageSource.getMessage(
                "notification.reservation.emergencyClosure.operatorAlert.emailSubject", null,
                "【要確認】臨時休業未確認患者様のお知らせ", locale);
        return new EmailOutboxRequest(
                "RESERVATION_EMERGENCY_UNCONFIRMED",
                locale.toLanguageTag(),
                event.recipientEmail(),
                Map.of("subject", emailSubject, "body", htmlBody),
                "reservation",
                "emergency-unconfirmed:" + event.closureId() + ":" + confirmation.getUserId(),
                null,
                event.recipientUserId(),
                null);
    }
}
