package com.mannschaft.app.schedule.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.schedule.AttendanceStatus;
import com.mannschaft.app.schedule.ReminderKind;
import com.mannschaft.app.schedule.ScheduleErrorCode;
import com.mannschaft.app.schedule.dto.CreateReminderRequest;
import com.mannschaft.app.schedule.dto.UpdateReminderRequest;
import com.mannschaft.app.schedule.dto.ReminderResponse;
import com.mannschaft.app.schedule.entity.ScheduleAttendanceEntity;
import com.mannschaft.app.schedule.entity.ScheduleAttendanceReminderEntity;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.repository.ScheduleAttendanceReminderRepository;
import com.mannschaft.app.schedule.repository.ScheduleAttendanceRepository;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mannschaft.app.schedule.event.ReminderNotificationEvent;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

/**
 * リマインダー管理サービス。リマインダーの作成・一覧取得・即時リマインド・バッチ処理を担当する。
 *
 * <p>機能55 第二陣でリマインダーの相対指定（開始N分前）／絶対指定（固定日時）の両対応と、
 * 通知の実配線（{@link ReminderNotificationEvent} 発火 → IN_APP + PUSH）を行う。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ScheduleReminderService {

    private static final int MAX_REMINDERS_PER_SCHEDULE = 5;
    private static final String SOURCE_TYPE_SCHEDULE = "SCHEDULE";

    private final ScheduleAttendanceReminderRepository reminderRepository;
    private final ScheduleAttendanceRepository attendanceRepository;
    private final ScheduleRepository scheduleRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final MessageSource messageSource;

    /**
     * リマインダーを作成する。最大5件まで。
     *
     * <p>機能55 第二陣: {@link CreateReminderRequest#effectiveKind()} に応じて、ABSOLUTE は
     * {@code remindAt} を、RELATIVE は {@code remindBeforeMinutes} を保存する。RELATIVE 時の
     * {@code remindAt} は NULL のまま保持し、バッチが親予定の開始時刻から実効時刻を解決する。</p>
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
                    ReminderKind kind = req.effectiveKind();
                    ScheduleAttendanceReminderEntity reminder = ScheduleAttendanceReminderEntity.builder()
                            .scheduleId(scheduleId)
                            .reminderKind(kind)
                            .remindAt(kind == ReminderKind.ABSOLUTE ? req.getRemindAt() : null)
                            .remindBeforeMinutes(kind == ReminderKind.RELATIVE ? req.getRemindBeforeMinutes() : null)
                            .build();

                    reminder = reminderRepository.save(reminder);
                    return toReminderResponse(reminder);
                })
                .toList();

        log.info("リマインダー作成: scheduleId={}, 件数={}", scheduleId, requests.size());
        return responses;
    }

    /**
     * リマインダーを更新する（差し替え）。既存を全削除して新規リストを登録する（機能55 BE対応）。
     *
     * <p>空リストを渡すと全削除のみ（再登録なし）となる。
     * null は呼び出し元で「変更なし」として処理するため、このメソッドには渡さない。
     * 編集コンテキストのため {@link UpdateReminderRequest} を受け取り、過去日時の絶対指定も許容する。</p>
     *
     * @param scheduleId スケジュールID
     * @param requests   新規リマインダーリスト（空リスト可。null 禁止）
     */
    @Transactional
    public void updateReminders(Long scheduleId, List<UpdateReminderRequest> requests) {
        // 先に既存をすべて削除
        reminderRepository.deleteByScheduleId(scheduleId);

        // 空リストなら削除のみで終了
        if (requests.isEmpty()) {
            log.info("リマインダー全削除: scheduleId={}", scheduleId);
            return;
        }

        // 上限チェック
        if (requests.size() > MAX_REMINDERS_PER_SCHEDULE) {
            throw new BusinessException(ScheduleErrorCode.MAX_REMINDERS_EXCEEDED);
        }

        // 新規登録（編集コンテキストのため過去日時も許容して直接エンティティ保存）
        requests.forEach(req -> {
            ReminderKind kind = req.effectiveKind();
            ScheduleAttendanceReminderEntity reminder = ScheduleAttendanceReminderEntity.builder()
                    .scheduleId(scheduleId)
                    .reminderKind(kind)
                    .remindAt(kind == ReminderKind.ABSOLUTE ? req.getRemindAt() : null)
                    .remindBeforeMinutes(kind == ReminderKind.RELATIVE ? req.getRemindBeforeMinutes() : null)
                    .build();
            reminderRepository.save(reminder);
        });

        log.info("リマインダー更新完了: scheduleId={}, 件数={}", scheduleId, requests.size());
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
     * 即時リマインドを送信する。
     *
     * <p>出欠回答が必要な予定は未回答者（UNDECIDED）へ、出欠不要の予定は全出欠対象者へ
     * リマインド通知イベントを発行する。{@link ReminderNotificationEvent} を介して
     * IN_APP + PUSH を配信する。</p>
     *
     * @param scheduleId スケジュールID
     */
    @Transactional
    public void sendReminder(Long scheduleId) {
        ScheduleEntity schedule = scheduleRepository.findById(scheduleId).orElse(null);
        if (schedule == null) {
            log.warn("リマインド対象予定なし（削除済み等）: scheduleId={}", scheduleId);
            return;
        }

        List<Long> recipientUserIds = resolveRecipients(schedule);
        if (recipientUserIds.isEmpty()) {
            log.info("リマインド対象者なし: scheduleId={}", scheduleId);
            return;
        }

        Locale locale = LocaleContextHolder.getLocale();
        boolean attendanceRequired = Boolean.TRUE.equals(schedule.getAttendanceRequired());
        String title = messageSource.getMessage(
                "notification.schedule.reminder.title", null,
                "まもなく予定の時刻です", locale);
        String bodyKey = attendanceRequired
                ? "notification.schedule.reminder.body.unanswered"
                : "notification.schedule.reminder.body.upcoming";
        String defaultBody = attendanceRequired
                ? "出欠が未回答の予定があります: "
                : "まもなく予定が始まります: ";
        String body = messageSource.getMessage(bodyKey, null, defaultBody, locale) + schedule.getTitle();

        NotificationScopeType scopeType = resolveScopeType(schedule);
        Long scopeId = resolveScopeId(schedule);

        eventPublisher.publishEvent(new ReminderNotificationEvent(
                scheduleId, scopeType, scopeId, recipientUserIds,
                title, body, "/schedules/" + scheduleId));

        log.info("即時リマインド送信: scheduleId={}, 対象者数={}", scheduleId, recipientUserIds.size());
    }

    /**
     * バッチ処理用: 未送信かつ実効リマインド時刻を過ぎたリマインダーを処理する。
     *
     * <p>機能55 第二陣で実効時刻ベースに改修。ABSOLUTE は {@code remind_at <= now}、
     * RELATIVE は親予定の {@code start_at - remind_before_minutes <= now}（親予定の開始時刻を
     * 取得し {@link ScheduleAttendanceReminderEntity#effectiveRemindAt} で解決）を due 判定とする。
     * 未送信（{@code is_sent = false}）のみを対象とし、送信後に {@code markAsSent()} する。</p>
     */
    @Transactional
    public void processScheduledReminders() {
        LocalDateTime now = LocalDateTime.now();
        List<ScheduleAttendanceReminderEntity> pendingReminders =
                reminderRepository.findByIsSentFalse();

        int processed = 0;
        for (ScheduleAttendanceReminderEntity reminder : pendingReminders) {
            LocalDateTime effectiveAt = resolveEffectiveRemindAt(reminder);
            if (effectiveAt == null || effectiveAt.isAfter(now)) {
                continue; // 実効時刻が解決不能、または未到来はスキップ
            }
            sendReminder(reminder.getScheduleId());
            reminder.markAsSent();
            reminderRepository.save(reminder);
            processed++;
        }

        if (processed > 0) {
            log.info("バッチリマインダー処理完了: 処理件数={}", processed);
        }
    }

    // --- プライベートメソッド ---

    /**
     * リマインダーの実効リマインド時刻を解決する。
     * RELATIVE の場合は親予定の開始時刻を取得して {@code effectiveRemindAt} に渡す。
     */
    private LocalDateTime resolveEffectiveRemindAt(ScheduleAttendanceReminderEntity reminder) {
        if (reminder.getReminderKind() == ReminderKind.RELATIVE) {
            ScheduleEntity schedule = scheduleRepository.findById(reminder.getScheduleId()).orElse(null);
            if (schedule == null) {
                return null;
            }
            return reminder.effectiveRemindAt(schedule.getStartAt());
        }
        return reminder.effectiveRemindAt(null);
    }

    /**
     * リマインド対象ユーザーIDを解決する。
     * 出欠必須予定は未回答者（UNDECIDED）、出欠不要予定は全出欠対象者を返す。
     */
    private List<Long> resolveRecipients(ScheduleEntity schedule) {
        boolean attendanceRequired = Boolean.TRUE.equals(schedule.getAttendanceRequired());
        List<ScheduleAttendanceEntity> targets = attendanceRequired
                ? attendanceRepository.findByScheduleIdAndStatus(schedule.getId(), AttendanceStatus.UNDECIDED)
                : attendanceRepository.findByScheduleIdOrderByUserIdAsc(schedule.getId());
        return targets.stream()
                .map(ScheduleAttendanceEntity::getUserId)
                .distinct()
                .toList();
    }

    private NotificationScopeType resolveScopeType(ScheduleEntity schedule) {
        if (schedule.getOrganizationId() != null) {
            return NotificationScopeType.ORGANIZATION;
        }
        if (schedule.getTeamId() != null) {
            return NotificationScopeType.TEAM;
        }
        return NotificationScopeType.PERSONAL;
    }

    private Long resolveScopeId(ScheduleEntity schedule) {
        if (schedule.getOrganizationId() != null) {
            return schedule.getOrganizationId();
        }
        if (schedule.getTeamId() != null) {
            return schedule.getTeamId();
        }
        return schedule.getUserId();
    }

    /**
     * エンティティをリマインダーレスポンスDTOに変換する。
     */
    private ReminderResponse toReminderResponse(ScheduleAttendanceReminderEntity entity) {
        return ReminderResponse.builder()
                .id(entity.getId())
                .reminderKind(entity.getReminderKind() != null ? entity.getReminderKind().name() : null)
                .remindAt(entity.getRemindAt())
                .remindBeforeMinutes(entity.getRemindBeforeMinutes())
                .isSent(entity.getIsSent())
                .sentAt(entity.getSentAt())
                .build();
    }
}
