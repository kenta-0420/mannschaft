package com.mannschaft.app.reservation.service;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.reservation.entity.EmergencyClosureConfirmationEntity;
import com.mannschaft.app.reservation.entity.EmergencyClosureEntity;
import com.mannschaft.app.reservation.event.EmergencyClosureReminderNotificationEvent;
import com.mannschaft.app.reservation.repository.EmergencyClosureConfirmationRepository;
import com.mannschaft.app.reservation.repository.EmergencyClosureRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashSet;
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
 *
 * <h2>Issue #2834 / CMP-056 第2群ロット1 による是正</h2>
 * <p>是正前は<b>バッチ全体を 1 つの {@code @Transactional} で包みながらループ内で 1 件ずつ catch</b>
 * していた。1 件の失敗は握りつぶされたように見えて、実際には rollback-only が残るため
 * コミット時に<b>両ステップ全件のリマインド送信済み記録が巻き戻り</b>、1 分後の再走査で
 * 全員に二重送信される状態だった（通知は送信済みなのに記録だけ消えるため）。
 * 非トランザクションのオーケストレータ ＋ 項目ごと {@link EmergencyClosureReminderRunner}
 * （{@code REQUIRES_NEW}）＋ {@code AFTER_COMMIT} 通知の形へ是正した（CMP-035 の金型）。</p>
 *
 * <h2>分類の判定</h2>
 * <p>本バッチは通知だけでなく<b>業務状態（{@code emergency_closure_confirmations.patient_reminder_sent_at} /
 * {@code reminder_sent_at}）を更新する</b>。この列は二重送信を防ぐ冪等キーであり、通知と同時に
 * 確定しなければならない。よって確定設計の「バッチで業務状態も更新する」に該当し、非TXループ →
 * 項目ごと REQUIRES_NEW → その中の {@code AFTER_COMMIT} で通知、を採る。</p>
 *
 * <h2>順序のトレードオフ（送信済み記録 → コミット → 通知）</h2>
 * <p>是正前は「通知 → 記録」の順だった。本バッチは<b>1 分間隔</b>で走るため、通知先行のままだと
 * 記録が落ちるたびに毎分同じ患者へ URGENT 通知が飛び続ける（通知の嵐）。記録先行にすることで
 * 再送は起きず、配送失敗はリスナー側の構造化 ERROR ログで観測する。</p>
 *
 * <h2>外向き契約</h2>
 * <p>{@code processUnconfirmedReminders} は是正前後とも戻り値 {@code void}。{@code @BatchEndpoint}
 * 経由の管理コンソール実行も戻り値を持たないため、FE / OpenAPI への波及はない。
 * ログの件数表記は「抽出件数」から<b>「実際に確定した件数」</b>へ変わった
 * （是正前の {@code 「{}件送信」} は抽出件数をそのまま出しており、失敗した件も送信済みとして数えていた）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmergencyClosureReminderBatchService {

    private final EmergencyClosureConfirmationRepository confirmationRepository;
    private final EmergencyClosureRepository closureRepository;
    private final UserRepository userRepository;
    private final EmergencyClosureReminderRunner emergencyClosureReminderRunner;

    @BatchEndpoint(name = "reservation-emergency-closure-reminder", description = "臨時休業の未確認患者・送信者リマインドを 1 分毎に処理する")
    @Scheduled(fixedDelay = 60_000)
    @SchedulerLock(name = "emergencyClosureReminderBatch", lockAtLeastFor = "30s", lockAtMostFor = "5m")
    public void processUnconfirmedReminders() {
        LocalDateTime now = LocalDateTime.now();
        processPatientReminders(now);
        processOperatorAlerts(now);
    }

    /** STEP 1: 患者本人への3時間前リマインド。 */
    private void processPatientReminders(LocalDateTime now) {
        List<EmergencyClosureConfirmationEntity> targets =
                confirmationRepository.findUnconfirmedForPatientReminder(now, now.plusHours(3));
        if (targets.isEmpty()) {
            return;
        }

        // N+1 対策: closure / patient を一括取得してからループ処理する（TX 外の読み取り）。
        Map<Long, EmergencyClosureEntity> closureMap = loadClosures(targets);
        Map<Long, UserEntity> patientMap = loadUsers(targets.stream()
                .map(EmergencyClosureConfirmationEntity::getUserId)
                .collect(Collectors.toSet()));

        int sent = 0;
        int skipped = 0;
        int failed = 0;
        Long firstFailedConfirmationId = null;
        for (EmergencyClosureConfirmationEntity confirmation : targets) {
            EmergencyClosureEntity closure = closureMap.get(confirmation.getEmergencyClosureId());
            UserEntity patient = patientMap.get(confirmation.getUserId());
            if (closure == null || patient == null) {
                skipped++;
                continue;
            }
            try {
                EmergencyClosureReminderNotificationEvent event = new EmergencyClosureReminderNotificationEvent(
                        EmergencyClosureReminderNotificationEvent.Phase.PATIENT,
                        confirmation.getId(),
                        closure.getId(),
                        closure.getTeamId(),
                        closure.getSubject(),
                        closure.getReason(),
                        closure.getMessageBody(),
                        confirmation.getAppointmentAt(),
                        patient.getId(),
                        patient.getLastName() + " " + patient.getFirstName(),
                        patient.getId(),
                        patient.getEmail(),
                        patient.getLocale(),
                        closure.getCreatedBy());
                if (emergencyClosureReminderRunner.markReminderSent(event)) {
                    sent++;
                } else {
                    // 抽出後に患者が確認した、または既に記録済み（再実行時も同じ経路に入る）。
                    skipped++;
                }
            } catch (Exception e) {
                // catch は必ずオーケストレータ側（TX 外）で行う。Runner の内側で catch すると
                // rollback-only のトランザクションで記録が消える。
                failed++;
                if (firstFailedConfirmationId == null) {
                    firstFailedConfirmationId = confirmation.getId();
                }
                log.error("臨時休業患者リマインドの確定に失敗: confirmationId={}", confirmation.getId(), e);
            }
        }

        String summary = "臨時休業患者リマインド: 対象={}, 確定={}, スキップ={}, 失敗={}, firstFailedConfirmationId={}";
        if (failed > 0) {
            log.error(summary, targets.size(), sent, skipped, failed, firstFailedConfirmationId);
        } else {
            log.info(summary, targets.size(), sent, skipped, failed, firstFailedConfirmationId);
        }
    }

    /** STEP 2: 送信者への2時間前アラート。 */
    private void processOperatorAlerts(LocalDateTime now) {
        List<EmergencyClosureConfirmationEntity> targets =
                confirmationRepository.findUnconfirmedApproachingAppointments(now, now.plusHours(2));
        if (targets.isEmpty()) {
            return;
        }

        // N+1 対策: closure / patient / operator を一括取得してからループ処理する（TX 外の読み取り）。
        Map<Long, EmergencyClosureEntity> closureMap = loadClosures(targets);
        Set<Long> userIds = new HashSet<>(targets.stream()
                .map(EmergencyClosureConfirmationEntity::getUserId)
                .collect(Collectors.toSet()));
        userIds.addAll(closureMap.values().stream()
                .map(EmergencyClosureEntity::getCreatedBy)
                .collect(Collectors.toSet()));
        Map<Long, UserEntity> userMap = loadUsers(userIds);

        int sent = 0;
        int skipped = 0;
        int failed = 0;
        Long firstFailedConfirmationId = null;
        for (EmergencyClosureConfirmationEntity confirmation : targets) {
            EmergencyClosureEntity closure = closureMap.get(confirmation.getEmergencyClosureId());
            if (closure == null) {
                skipped++;
                continue;
            }
            UserEntity patient = userMap.get(confirmation.getUserId());
            UserEntity operator = userMap.get(closure.getCreatedBy());
            if (patient == null || operator == null) {
                skipped++;
                continue;
            }
            try {
                EmergencyClosureReminderNotificationEvent event = new EmergencyClosureReminderNotificationEvent(
                        EmergencyClosureReminderNotificationEvent.Phase.OPERATOR,
                        confirmation.getId(),
                        closure.getId(),
                        closure.getTeamId(),
                        closure.getSubject(),
                        closure.getReason(),
                        closure.getMessageBody(),
                        confirmation.getAppointmentAt(),
                        patient.getId(),
                        patient.getLastName() + " " + patient.getFirstName(),
                        operator.getId(),
                        operator.getEmail(),
                        operator.getLocale(),
                        null);
                if (emergencyClosureReminderRunner.markReminderSent(event)) {
                    sent++;
                } else {
                    skipped++;
                }
            } catch (Exception e) {
                failed++;
                if (firstFailedConfirmationId == null) {
                    firstFailedConfirmationId = confirmation.getId();
                }
                log.error("臨時休業未確認リマインドの確定に失敗: confirmationId={}", confirmation.getId(), e);
            }
        }

        String summary = "臨時休業送信者アラート: 対象={}, 確定={}, スキップ={}, 失敗={}, firstFailedConfirmationId={}";
        if (failed > 0) {
            log.error(summary, targets.size(), sent, skipped, failed, firstFailedConfirmationId);
        } else {
            log.info(summary, targets.size(), sent, skipped, failed, firstFailedConfirmationId);
        }
    }

    private Map<Long, EmergencyClosureEntity> loadClosures(List<EmergencyClosureConfirmationEntity> targets) {
        Set<Long> closureIds = targets.stream()
                .map(EmergencyClosureConfirmationEntity::getEmergencyClosureId)
                .collect(Collectors.toSet());
        return closureRepository.findAllById(closureIds).stream()
                .collect(Collectors.toMap(EmergencyClosureEntity::getId, e -> e));
    }

    private Map<Long, UserEntity> loadUsers(Set<Long> userIds) {
        return userRepository.findByIdIn(userIds).stream()
                .collect(Collectors.toMap(UserEntity::getId, u -> u));
    }
}
