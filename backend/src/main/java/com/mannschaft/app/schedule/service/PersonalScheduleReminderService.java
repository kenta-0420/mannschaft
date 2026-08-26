package com.mannschaft.app.schedule.service;

import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.schedule.entity.PersonalScheduleReminderEntity;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.event.ReminderNotificationEvent;
import com.mannschaft.app.schedule.repository.PersonalScheduleReminderRepository;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

/**
 * 個人予定リマインダー処理サービス（機能55 第二陣 — リマインド根治）。
 *
 * <p>第一陣までは {@link PersonalScheduleReminderRepository#findDueReminders()} を駆動する
 * バッチが存在せず、個人予定リマインダーは永遠に発火しなかった。本サービスで due 判定済みの
 * リマインダーを所有者へ通知し、{@code markAsNotified()} で送信済みにする。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PersonalScheduleReminderService {

    private final PersonalScheduleReminderRepository reminderRepository;
    private final ScheduleRepository scheduleRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final MessageSource messageSource;

    /**
     * due 判定済みの個人予定リマインダーを処理する。
     *
     * <p>RELATIVE/ABSOLUTE 双方の due リマインダーについて、予定の所有者（userId）へ
     * {@link ReminderNotificationEvent} を発火し IN_APP + PUSH を配信する。送信後に
     * {@code markAsNotified()} で再送を防ぐ。</p>
     */
    @Transactional
    public void processDueReminders() {
        List<PersonalScheduleReminderEntity> due = reminderRepository.findDueReminders();
        if (due.isEmpty()) {
            return;
        }

        Locale locale = LocaleContextHolder.getLocale();
        String title = messageSource.getMessage(
                "notification.schedule.reminder.title", null,
                "まもなく予定の時刻です", locale);
        String bodyPrefix = messageSource.getMessage(
                "notification.schedule.reminder.body.personal", null,
                "まもなく予定が始まります: ", locale);

        int processed = 0;
        for (PersonalScheduleReminderEntity reminder : due) {
            ScheduleEntity schedule = scheduleRepository.findById(reminder.getScheduleId()).orElse(null);
            if (schedule == null || schedule.getUserId() == null) {
                // 所有者不在（削除済み等）は通知できないが、再走査を避けるため通知済みにする
                reminder.markAsNotified();
                reminderRepository.save(reminder);
                continue;
            }

            eventPublisher.publishEvent(new ReminderNotificationEvent(
                    reminder.getScheduleId(),
                    NotificationScopeType.PERSONAL,
                    schedule.getUserId(),
                    List.of(schedule.getUserId()),
                    title,
                    bodyPrefix + schedule.getTitle(),
                    "/schedules/" + reminder.getScheduleId()));

            reminder.markAsNotified();
            reminderRepository.save(reminder);
            processed++;
        }

        if (processed > 0) {
            log.info("個人予定リマインダー処理完了: 処理件数={}", processed);
        }
    }
}
