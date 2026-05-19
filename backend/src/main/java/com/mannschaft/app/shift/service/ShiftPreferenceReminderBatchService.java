package com.mannschaft.app.shift.service;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationHelper;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.shift.ShiftErrorCode;
import com.mannschaft.app.shift.ShiftScheduleStatus;
import com.mannschaft.app.shift.dto.ManualRemindResponse;
import com.mannschaft.app.shift.entity.ShiftRequestEntity;
import com.mannschaft.app.shift.entity.ShiftScheduleEntity;
import com.mannschaft.app.shift.repository.ShiftRequestRepository;
import com.mannschaft.app.shift.repository.ShiftScheduleRepository;
import com.mannschaft.app.team.entity.TeamShiftSettingsEntity;
import com.mannschaft.app.team.repository.TeamShiftSettingsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * シフト希望提出リマインドバッチサービス。
 * 提出期限の 48h 前・24h 前に未提出メンバーへ通知を送信する。
 * TODO: Phase 4-1 で低提出率アラート（isLowSubmissionAlerted）を実装
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShiftPreferenceReminderBatchService {

    private final ShiftScheduleRepository scheduleRepository;
    private final ShiftRequestRepository requestRepository;
    private final UserRoleRepository userRoleRepository;
    private final NotificationHelper notificationHelper;
    private final TeamShiftSettingsRepository teamShiftSettingsRepository;
    private final AuditLogService auditLogService;

    /**
     * 10 分ごとに実行。48h前・24h前リマインドを未提出メンバーに送信する。
     */
    // TODO: shiftドメインがroleドメイン（UserRoleRepository）とteamドメイン（TeamShiftSettingsRepository）をまたいでいる。将来はそれぞれのQueryService経由で分離予定。Phase1-E: 2026-05-09
    @BatchEndpoint(name = "shift-preference-reminder", description = "シフト希望提出 48h・24h 前のリマインドを 10 分毎に送信する")
    @Scheduled(cron = "0 */10 * * * *", zone = "Asia/Tokyo")
    @SchedulerLock(name = "shift_preference_reminder", lockAtMostFor = "15m", lockAtLeastFor = "2m")
    @Transactional
    public void processReminders() {
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Tokyo"));
        int sent48h = process48hReminders(now);
        int sent24h = process24hReminders(now);
        log.info("シフト希望リマインドバッチ完了: 48h送信={}, 24h送信={}", sent48h, sent24h);
    }

    private int process48hReminders(LocalDateTime now) {
        List<ShiftScheduleEntity> targets = scheduleRepository
                .findFor48hReminder(now, now.plusHours(48));
        int count = 0;
        for (ShiftScheduleEntity schedule : targets) {
            try {
                // チームのリマインド設定を確認し、48h が無効なら送信をスキップ
                TeamShiftSettingsEntity teamSettings = teamShiftSettingsRepository
                        .findByTeamId(schedule.getTeamId())
                        .orElse(null);
                if (teamSettings != null && !teamSettings.isReminder48hEnabled()) {
                    log.info("48hリマインド無効のためスキップ: teamId={}", schedule.getTeamId());
                    continue;
                }
                sendReminderToUnsubmittedMembers(schedule,
                        "SHIFT_REQUEST_REMINDER_48H",
                        "シフト希望の提出期限 48 時間前です",
                        "シフト「" + schedule.getTitle() + "」の提出期限が 48 時間以内です。まだ提出していない場合はお早めに。");
                schedule.markReminderSent48h();
                scheduleRepository.save(schedule);
                count++;
            } catch (Exception e) {
                // フラグをセットせず次回バッチで再試行
                log.error("48h リマインド送信失敗（スキップ）: scheduleId={}", schedule.getId(), e);
            }
        }
        return count;
    }

    private int process24hReminders(LocalDateTime now) {
        List<ShiftScheduleEntity> targets = scheduleRepository
                .findFor24hReminder(now, now.plusHours(24));
        int count = 0;
        for (ShiftScheduleEntity schedule : targets) {
            try {
                // チームのリマインド設定を確認し、24h が無効なら送信をスキップ
                TeamShiftSettingsEntity teamSettings = teamShiftSettingsRepository
                        .findByTeamId(schedule.getTeamId())
                        .orElse(null);
                if (teamSettings != null && !teamSettings.isReminder24hEnabled()) {
                    log.info("24hリマインド無効のためスキップ: teamId={}", schedule.getTeamId());
                    continue;
                }
                sendReminderToUnsubmittedMembers(schedule,
                        "SHIFT_REQUEST_REMINDER",
                        "シフト希望の提出期限が明日までです",
                        "シフト「" + schedule.getTitle() + "」の提出期限は明日までです。まだ提出していない場合は今すぐご対応ください。");
                schedule.markReminderSent();
                scheduleRepository.save(schedule);
                count++;
            } catch (Exception e) {
                log.error("24h リマインド送信失敗（スキップ）: scheduleId={}", schedule.getId(), e);
            }
        }
        return count;
    }

    private void sendReminderToUnsubmittedMembers(ShiftScheduleEntity schedule,
            String notificationType, String title, String body) {
        List<Long> unsubmitted = resolveUnsubmittedUserIds(schedule);
        if (unsubmitted.isEmpty()) return;

        notificationHelper.notifyAll(
                unsubmitted, notificationType, title, body,
                "SHIFT_SCHEDULE", schedule.getId(),
                NotificationScopeType.TEAM, schedule.getTeamId(),
                "/shifts/schedules/" + schedule.getId(), null);

        log.info("シフト希望リマインド送信: type={}, scheduleId={}, 未提出人数={}",
                notificationType, schedule.getId(), unsubmitted.size());
    }

    /**
     * シフト希望未提出メンバーの ID 一覧を解決する。
     *
     * <p>cron バッチと手動リマインド API の両方から再利用される。</p>
     */
    private List<Long> resolveUnsubmittedUserIds(ShiftScheduleEntity schedule) {
        Set<Long> submittedUserIds = requestRepository
                .findByScheduleIdOrderBySlotDateAsc(schedule.getId())
                .stream()
                .map(ShiftRequestEntity::getUserId)
                .collect(Collectors.toSet());

        // TODO: SUPPORTER・GUEST を除外するロール別フィルタは Phase 4-1 で実装
        return userRoleRepository
                .findUserIdsByScope("TEAM", schedule.getTeamId())
                .stream()
                .filter(uid -> !submittedUserIds.contains(uid))
                .toList();
    }

    /**
     * 管理者の手動操作によるリマインド送信。
     *
     * <p>COLLECTING ステータスのスケジュールにのみ実行可能。cron バッチと同じ
     * 「未提出者抽出 → 通知一斉送信」ロジックを再利用しつつ、監査ログを必ず残す。</p>
     *
     * @param scheduleId スケジュール ID
     * @param userId     呼び出し元（操作した管理者）の ID
     * @return 送信件数と対象ユーザー ID 一覧
     * @throws BusinessException スケジュールが存在しない場合 ({@link ShiftErrorCode#SHIFT_SCHEDULE_NOT_FOUND}) /
     *                           COLLECTING 以外の場合 ({@link ShiftErrorCode#INVALID_SCHEDULE_STATUS})
     */
    @Transactional
    public ManualRemindResponse triggerManualReminder(Long scheduleId, Long userId) {
        ShiftScheduleEntity schedule = scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new BusinessException(ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND));

        if (schedule.getStatus() != ShiftScheduleStatus.COLLECTING) {
            throw new BusinessException(ShiftErrorCode.INVALID_SCHEDULE_STATUS);
        }

        List<Long> unsubmitted = resolveUnsubmittedUserIds(schedule);

        if (!unsubmitted.isEmpty()) {
            notificationHelper.notifyAll(
                    unsubmitted,
                    "SHIFT_REQUEST_REMINDER_MANUAL",
                    "シフト希望提出のリマインド",
                    "シフト「" + schedule.getTitle() + "」の希望提出のお願いです。提出期限までにご対応ください。",
                    "SHIFT_SCHEDULE", schedule.getId(),
                    NotificationScopeType.TEAM, schedule.getTeamId(),
                    "/shifts/schedules/" + schedule.getId(), null);
        }

        // 監査ログ: MANUAL_REMINDER（操作者・スケジュール・チーム・送信件数を記録）
        auditLogService.record(
                AuditEventType.SHIFT_MANUAL_REMINDER_SENT.name(),
                userId, null, schedule.getTeamId(), null,
                null, null, null,
                "{\"schedule_id\":" + schedule.getId()
                        + ",\"reminded_count\":" + unsubmitted.size() + "}");

        log.info("シフト希望手動リマインド送信: scheduleId={}, 未提出人数={}, operator={}",
                schedule.getId(), unsubmitted.size(), userId);

        return ManualRemindResponse.builder()
                .scheduleId(schedule.getId())
                .remindedCount(unsubmitted.size())
                .remindedUserIds(unsubmitted)
                .build();
    }
}
