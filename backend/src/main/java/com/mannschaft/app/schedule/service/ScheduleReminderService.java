package com.mannschaft.app.schedule.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.schedule.AttendanceStatus;
import com.mannschaft.app.schedule.ScheduleErrorCode;
import com.mannschaft.app.schedule.dto.CreateReminderRequest;
import com.mannschaft.app.schedule.dto.ReminderResponse;
import com.mannschaft.app.schedule.entity.ScheduleAttendanceEntity;
import com.mannschaft.app.schedule.entity.ScheduleAttendanceReminderEntity;
import com.mannschaft.app.schedule.repository.ScheduleAttendanceReminderRepository;
import com.mannschaft.app.schedule.repository.ScheduleAttendanceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * リマインダー管理サービス。リマインダーの作成・一覧取得・即時リマインド・バッチ処理を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ScheduleReminderService {

    private static final int MAX_REMINDERS_PER_SCHEDULE = 5;

    private final ScheduleAttendanceReminderRepository reminderRepository;
    private final ScheduleAttendanceRepository attendanceRepository;

    /**
     * リマインダーを作成する。最大5件まで。
     *
     * @param scheduleId スケジュールID
     * @param requests   リマインダー作成リクエストリスト
     * @return 作成されたリマインダー一覧
     */
    @Transactional
    public List<ReminderResponse> createReminders(Long scheduleId, List<CreateReminderRequest> requests) {
        long existingCount = reminderRepository.countByScheduleId(scheduleId);
        if (existingCount + requests.size() > MAX_REMINDERS_PER_SCHEDULE) {
            throw new BusinessException(ScheduleErrorCode.MAX_REMINDERS_EXCEEDED);
        }

        List<ReminderResponse> responses = requests.stream()
                .map(req -> {
                    ScheduleAttendanceReminderEntity reminder = ScheduleAttendanceReminderEntity.builder()
                            .scheduleId(scheduleId)
                            .remindAt(req.getRemindAt())
                            .build();

                    reminder = reminderRepository.save(reminder);
                    return toReminderResponse(reminder);
                })
                .toList();

        log.info("リマインダー作成: scheduleId={}, 件数={}", scheduleId, requests.size());
        return responses;
    }

    /**
     * スケジュールに紐付くリマインダー一覧を取得する。
     *
     * @param scheduleId スケジュールID
     * @return リマインダー一覧
     */
    public List<ReminderResponse> getReminders(Long scheduleId) {
        return reminderRepository.findByScheduleIdOrderByRemindAtAsc(scheduleId).stream()
                .map(this::toReminderResponse)
                .toList();
    }

    /**
     * 即時リマインドを送信する。未回答者（UNDECIDED）を抽出して通知イベントを発行する。
     *
     * @param scheduleId スケジュールID
     */
    @Transactional
    public void sendReminder(Long scheduleId) {
        List<ScheduleAttendanceEntity> undecided = attendanceRepository
                .findByScheduleIdAndStatus(scheduleId, AttendanceStatus.UNDECIDED);

        if (undecided.isEmpty()) {
            log.info("リマインド対象者なし: scheduleId={}", scheduleId);
            return;
        }

        List<Long> undecidedUserIds = undecided.stream()
                .map(ScheduleAttendanceEntity::getUserId)
                .toList();

        // 将来実装: 通知機能実装後に連携してリマインド通知イベントを発行
        // eventPublisher.publishEvent(new ReminderNotificationEvent(scheduleId, undecidedUserIds));

        log.info("即時リマインド送信: scheduleId={}, 対象者数={}", scheduleId, undecidedUserIds.size());
    }

    /**
     * バッチ処理用: 未送信かつリマインド日時を過ぎたリマインダーを処理する。
     * is_sent=false かつ remind_at が現在時刻より前のリマインダーを対象とする。
     */
    @Transactional
    public void processScheduledReminders() {
        LocalDateTime now = LocalDateTime.now();
        List<ScheduleAttendanceReminderEntity> pendingReminders = reminderRepository
                .findByIsSentFalseAndRemindAtBeforeOrderByRemindAtAsc(now);

        for (ScheduleAttendanceReminderEntity reminder : pendingReminders) {
            sendReminder(reminder.getScheduleId());
            reminder.markAsSent();
            reminderRepository.save(reminder);
        }

        if (!pendingReminders.isEmpty()) {
            log.info("バッチリマインダー処理完了: 処理件数={}", pendingReminders.size());
        }
    }

    // --- プライベートメソッド ---

    /**
     * エンティティをリマインダーレスポンスDTOに変換する。
     */
    private ReminderResponse toReminderResponse(ScheduleAttendanceReminderEntity entity) {
        return new ReminderResponse(
                entity.getId(),
                entity.getRemindAt(),
                entity.getIsSent(),
                entity.getSentAt());
    }
}
