package com.mannschaft.app.reservation.service;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.EmailService;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationHelper;
import com.mannschaft.app.reservation.entity.EmergencyClosureConfirmationEntity;
import com.mannschaft.app.reservation.entity.EmergencyClosureEntity;
import com.mannschaft.app.reservation.repository.EmergencyClosureConfirmationRepository;
import com.mannschaft.app.reservation.repository.EmergencyClosureRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 臨時休業未確認リマインダーバッチ。1分ごとに実行し、以下の2段階で通知する。
 * <ol>
 *   <li>予約時刻の3時間前 — 未確認の患者本人へ再リマインドを送る</li>
 *   <li>予約時刻の2時間前 — まだ未確認なら送信者（院長等）へアラートを送る</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EmergencyClosureReminderBatchService {

    private final EmergencyClosureConfirmationRepository confirmationRepository;
    private final EmergencyClosureRepository closureRepository;
    private final UserRepository userRepository;
    private final NotificationHelper notificationHelper;
    private final EmailService emailService;

    @Scheduled(fixedDelay = 60_000)
    @SchedulerLock(name = "emergencyClosureReminderBatch", lockAtLeastFor = "30s", lockAtMostFor = "5m")
    @Transactional
    public void processUnconfirmedReminders() {
        LocalDateTime now = LocalDateTime.now();

        // --- STEP 1: 患者本人への3時間前リマインド ---
        List<EmergencyClosureConfirmationEntity> patientTargets =
                confirmationRepository.findUnconfirmedForPatientReminder(now, now.plusHours(3));

        for (EmergencyClosureConfirmationEntity confirmation : patientTargets) {
            try {
                sendReminderToPatient(confirmation);
                confirmation.markPatientReminderSent();
                confirmationRepository.save(confirmation);
            } catch (Exception e) {
                log.error("臨時休業患者リマインド送信失敗: confirmationId={}", confirmation.getId(), e);
            }
        }
        if (!patientTargets.isEmpty()) {
            log.info("臨時休業患者リマインド: {}件送信", patientTargets.size());
        }

        // --- STEP 2: 送信者への2時間前アラート ---
        List<EmergencyClosureConfirmationEntity> operatorTargets =
                confirmationRepository.findUnconfirmedApproachingAppointments(now, now.plusHours(2));

        for (EmergencyClosureConfirmationEntity confirmation : operatorTargets) {
            try {
                sendReminderToOperator(confirmation);
                confirmation.markReminderSent();
                confirmationRepository.save(confirmation);
            } catch (Exception e) {
                log.error("臨時休業未確認リマインド送信失敗: confirmationId={}", confirmation.getId(), e);
            }
        }
        if (!operatorTargets.isEmpty()) {
            log.info("臨時休業送信者アラート: {}件送信", operatorTargets.size());
        }
    }

    private void sendReminderToPatient(EmergencyClosureConfirmationEntity confirmation) {
        EmergencyClosureEntity closure = closureRepository.findById(confirmation.getEmergencyClosureId())
                .orElse(null);
        if (closure == null) return;

        UserEntity patient = userRepository.findById(confirmation.getUserId()).orElse(null);
        if (patient == null) return;

        String appointmentStr = confirmation.getAppointmentAt()
                .format(DateTimeFormatter.ofPattern("M月d日 HH:mm"));

        String title = "【再送】" + closure.getSubject();
        String body  = String.format("%s — %sのご予約まで3時間前です。内容のご確認をお願いします。",
                closure.getReason(), appointmentStr);

        // アプリ内通知（EMERGENCY_CLOSURE タイプで送ることで、通知リストに「確認しました」ボタンが表示される）
        notificationHelper.notify(
                patient.getId(),
                "EMERGENCY_CLOSURE",
                NotificationPriority.URGENT,
                title, body,
                "EMERGENCY_CLOSURE",
                confirmation.getEmergencyClosureId(),
                NotificationScopeType.TEAM,
                closure.getTeamId(),
                null,
                closure.getCreatedBy()
        );

        // メールも再送
        String htmlBody = String.format(
                "<p><strong>%s</strong></p><p>%s</p><hr>" +
                "<p>%s</p>",
                title, body, closure.getMessageBody()
        );
        emailService.sendEmail(patient.getEmail(), title, htmlBody);

        log.info("臨時休業患者リマインド送信: closureId={}, patientId={}",
                confirmation.getEmergencyClosureId(), confirmation.getUserId());
    }

    private void sendReminderToOperator(EmergencyClosureConfirmationEntity confirmation) {
        EmergencyClosureEntity closure = closureRepository.findById(confirmation.getEmergencyClosureId())
                .orElse(null);
        if (closure == null) return;

        UserEntity patient = userRepository.findById(confirmation.getUserId()).orElse(null);
        UserEntity operator = userRepository.findById(closure.getCreatedBy()).orElse(null);
        if (patient == null || operator == null) return;

        String patientName = patient.getDisplayName();
        String appointmentStr = confirmation.getAppointmentAt()
                .format(DateTimeFormatter.ofPattern("M月d日 HH:mm"));

        String title = String.format("【要確認】%sさんが臨時休業通知を未確認です", patientName);
        String body  = String.format("%sの予約まで2時間を切りました。連絡が届いていない可能性があります。", appointmentStr);

        // アプリ内通知（WebSocket + PWA Push）→ 送信者へ
        notificationHelper.notify(
                operator.getId(),
                "CLOSURE_UNCONFIRMED_REMINDER",
                NotificationPriority.URGENT,
                title, body,
                "EMERGENCY_CLOSURE",
                confirmation.getEmergencyClosureId(),
                NotificationScopeType.TEAM,
                closure.getTeamId(),
                null,
                null
        );

        // メールも送信
        String htmlBody = String.format(
                "<p><strong>%s</strong></p><p>%s</p><hr>" +
                "<p>患者名: %s</p><p>予約日時: %s</p>",
                title, body, patientName, appointmentStr
        );
        emailService.sendEmail(operator.getEmail(), "【要確認】臨時休業未確認患者様のお知らせ", htmlBody);

        log.info("臨時休業未確認リマインド送信: closureId={}, patientId={}, operatorId={}",
                confirmation.getEmergencyClosureId(), confirmation.getUserId(), operator.getId());
    }
}
