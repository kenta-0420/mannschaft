package com.mannschaft.app.reservation.service;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.EmailService;
import com.mannschaft.app.mail.outbox.EmailOutboxRequest;
import com.mannschaft.app.mail.outbox.EmailOutboxService;
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
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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
    private final EmailOutboxService emailOutboxService;

    @BatchEndpoint(name = "reservation-emergency-closure-reminder", description = "臨時休業の未確認患者・送信者リマインドを 1 分毎に処理する")
    @Scheduled(fixedDelay = 60_000)
    @SchedulerLock(name = "emergencyClosureReminderBatch", lockAtLeastFor = "30s", lockAtMostFor = "5m")
    @Transactional
    public void processUnconfirmedReminders() {
        LocalDateTime now = LocalDateTime.now();

        // --- STEP 1: 患者本人への3時間前リマインド ---
        List<EmergencyClosureConfirmationEntity> patientTargets =
                confirmationRepository.findUnconfirmedForPatientReminder(now, now.plusHours(3));

        if (!patientTargets.isEmpty()) {
            // N+1 対策: closure / patient を一括取得してからループ処理する
            Set<Long> step1ClosureIds = patientTargets.stream()
                    .map(EmergencyClosureConfirmationEntity::getEmergencyClosureId)
                    .collect(Collectors.toSet());
            Set<Long> step1PatientIds = patientTargets.stream()
                    .map(EmergencyClosureConfirmationEntity::getUserId)
                    .collect(Collectors.toSet());
            Map<Long, EmergencyClosureEntity> step1ClosureMap = closureRepository.findAllById(step1ClosureIds)
                    .stream()
                    .collect(Collectors.toMap(EmergencyClosureEntity::getId, e -> e));
            Map<Long, UserEntity> step1PatientMap = userRepository.findByIdIn(step1PatientIds)
                    .stream()
                    .collect(Collectors.toMap(UserEntity::getId, u -> u));

            for (EmergencyClosureConfirmationEntity confirmation : patientTargets) {
                EmergencyClosureEntity closure = step1ClosureMap.get(confirmation.getEmergencyClosureId());
                UserEntity patient = step1PatientMap.get(confirmation.getUserId());
                if (closure == null || patient == null) continue;
                try {
                    sendReminderToPatient(confirmation, closure, patient);
                    confirmation.markPatientReminderSent();
                    confirmationRepository.save(confirmation);
                } catch (Exception e) {
                    log.error("臨時休業患者リマインド送信失敗: confirmationId={}", confirmation.getId(), e);
                }
            }
            log.info("臨時休業患者リマインド: {}件送信", patientTargets.size());
        }

        // --- STEP 2: 送信者への2時間前アラート ---
        List<EmergencyClosureConfirmationEntity> operatorTargets =
                confirmationRepository.findUnconfirmedApproachingAppointments(now, now.plusHours(2));

        if (!operatorTargets.isEmpty()) {
            // N+1 対策: closure / patient / operator を一括取得してからループ処理する
            Set<Long> step2ClosureIds = operatorTargets.stream()
                    .map(EmergencyClosureConfirmationEntity::getEmergencyClosureId)
                    .collect(Collectors.toSet());
            Map<Long, EmergencyClosureEntity> step2ClosureMap = closureRepository.findAllById(step2ClosureIds)
                    .stream()
                    .collect(Collectors.toMap(EmergencyClosureEntity::getId, e -> e));

            // patient + operator (closure.getCreatedBy()) を一度にバッチ取得
            Set<Long> step2PatientIds = operatorTargets.stream()
                    .map(EmergencyClosureConfirmationEntity::getUserId)
                    .collect(Collectors.toSet());
            Set<Long> step2OperatorIds = step2ClosureMap.values().stream()
                    .map(EmergencyClosureEntity::getCreatedBy)
                    .collect(Collectors.toSet());
            Set<Long> step2AllUserIds = new java.util.HashSet<>(step2PatientIds);
            step2AllUserIds.addAll(step2OperatorIds);
            Map<Long, UserEntity> step2UserMap = userRepository.findByIdIn(step2AllUserIds)
                    .stream()
                    .collect(Collectors.toMap(UserEntity::getId, u -> u));

            for (EmergencyClosureConfirmationEntity confirmation : operatorTargets) {
                EmergencyClosureEntity closure = step2ClosureMap.get(confirmation.getEmergencyClosureId());
                if (closure == null) continue;
                UserEntity patient = step2UserMap.get(confirmation.getUserId());
                UserEntity operator = step2UserMap.get(closure.getCreatedBy());
                if (patient == null || operator == null) continue;
                try {
                    sendReminderToOperator(confirmation, closure, patient, operator);
                    confirmation.markReminderSent();
                    confirmationRepository.save(confirmation);
                } catch (Exception e) {
                    log.error("臨時休業未確認リマインド送信失敗: confirmationId={}", confirmation.getId(), e);
                }
            }
            log.info("臨時休業送信者アラート: {}件送信", operatorTargets.size());
        }
    }

    private void sendReminderToPatient(
            EmergencyClosureConfirmationEntity confirmation,
            EmergencyClosureEntity closure,
            UserEntity patient) {

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

        // #13: メールも再送 (outbox 経由)
        String htmlBody = String.format(
                "<p><strong>%s</strong></p><p>%s</p><hr>" +
                "<p>%s</p>",
                title, body, closure.getMessageBody()
        );
        emailOutboxService.enqueue(new EmailOutboxRequest(
                "RESERVATION_EMERGENCY_REMINDER",
                "ja",
                patient.getEmail(),
                Map.of("subject", title, "body", htmlBody),
                "reservation",
                "emergency-reminder:" + confirmation.getEmergencyClosureId() + ":" + patient.getId(),
                null,
                patient.getId(),
                null
        ));

        log.info("臨時休業患者リマインド送信: closureId={}, patientId={}",
                confirmation.getEmergencyClosureId(), confirmation.getUserId());
    }

    private void sendReminderToOperator(
            EmergencyClosureConfirmationEntity confirmation,
            EmergencyClosureEntity closure,
            UserEntity patient,
            UserEntity operator) {

        String patientName = patient.getLastName() + " " + patient.getFirstName();
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

        // #14: メールも送信 (outbox 経由)
        String htmlBody = String.format(
                "<p><strong>%s</strong></p><p>%s</p><hr>" +
                "<p>患者名: %s</p><p>予約日時: %s</p>",
                title, body, patientName, appointmentStr
        );
        emailOutboxService.enqueue(new EmailOutboxRequest(
                "RESERVATION_EMERGENCY_UNCONFIRMED",
                "ja",
                operator.getEmail(),
                Map.of("subject", "【要確認】臨時休業未確認患者様のお知らせ", "body", htmlBody),
                "reservation",
                "emergency-unconfirmed:" + confirmation.getEmergencyClosureId() + ":" + patient.getId(),
                null,
                operator.getId(),
                null
        ));

        log.info("臨時休業未確認リマインド送信: closureId={}, patientId={}, operatorId={}",
                confirmation.getEmergencyClosureId(), confirmation.getUserId(), operator.getId());
    }
}
