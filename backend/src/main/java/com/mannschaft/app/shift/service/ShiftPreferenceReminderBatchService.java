package com.mannschaft.app.shift.service;

import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationHelper;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.shift.entity.ShiftRequestEntity;
import com.mannschaft.app.shift.entity.ShiftScheduleEntity;
import com.mannschaft.app.shift.repository.ShiftRequestRepository;
import com.mannschaft.app.shift.repository.ShiftScheduleRepository;
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

    /**
     * 10 分ごとに実行。48h前・24h前リマインドを未提出メンバーに送信する。
     */
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
        // 提出済みユーザー ID セット
        Set<Long> submittedUserIds = requestRepository
                .findByScheduleIdOrderBySlotDateAsc(schedule.getId())
                .stream()
                .map(ShiftRequestEntity::getUserId)
                .collect(Collectors.toSet());

        // チーム全員から提出済みを除いた未提出者
        // TODO: SUPPORTER・GUEST を除外するロール別フィルタは Phase 4-1 で実装
        List<Long> unsubmitted = userRoleRepository
                .findUserIdsByScope("TEAM", schedule.getTeamId())
                .stream()
                .filter(uid -> !submittedUserIds.contains(uid))
                .toList();

        if (unsubmitted.isEmpty()) return;

        notificationHelper.notifyAll(
                unsubmitted, notificationType, title, body,
                "SHIFT_SCHEDULE", schedule.getId(),
                NotificationScopeType.TEAM, schedule.getTeamId(),
                "/shifts/schedules/" + schedule.getId(), null);

        log.info("シフト希望リマインド送信: type={}, scheduleId={}, 未提出人数={}",
                notificationType, schedule.getId(), unsubmitted.size());
    }
}
