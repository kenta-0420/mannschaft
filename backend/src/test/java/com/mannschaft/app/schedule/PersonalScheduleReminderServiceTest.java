package com.mannschaft.app.schedule;

import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.schedule.entity.PersonalScheduleReminderEntity;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.event.ReminderNotificationEvent;
import com.mannschaft.app.schedule.repository.PersonalScheduleReminderRepository;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import com.mannschaft.app.schedule.service.PersonalScheduleReminderService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.MessageSource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link PersonalScheduleReminderService} の単体テスト（機能55 第二陣）。
 * due リマインダーの所有者通知発火と markAsNotified を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PersonalScheduleReminderService 単体テスト")
class PersonalScheduleReminderServiceTest {

    @Mock
    private PersonalScheduleReminderRepository reminderRepository;

    @Mock
    private ScheduleRepository scheduleRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private MessageSource messageSource;

    @InjectMocks
    private PersonalScheduleReminderService service;

    private static final Long SCHEDULE_ID = 10L;
    private static final Long OWNER_ID = 99L;

    private void stubMessages() {
        given(messageSource.getMessage(anyString(), any(), anyString(), any()))
                .willAnswer(invocation -> invocation.getArgument(2));
    }

    @Test
    @DisplayName("due_RELATIVE_所有者へ通知発火しnotified=true")
    void due_RELATIVE_所有者へ通知発火() {
        // given
        PersonalScheduleReminderEntity reminder = PersonalScheduleReminderEntity.builder()
                .scheduleId(SCHEDULE_ID)
                .reminderKind(ReminderKind.RELATIVE)
                .remindBeforeMinutes(30)
                .notified(false)
                .build();
        given(reminderRepository.findDueReminders()).willReturn(List.of(reminder));
        given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(
                ScheduleEntity.builder()
                        .userId(OWNER_ID)
                        .title("歯医者")
                        .startAt(LocalDateTime.now().plusMinutes(10))
                        .build()));
        stubMessages();

        // when
        service.processDueReminders();

        // then
        ArgumentCaptor<ReminderNotificationEvent> captor =
                ArgumentCaptor.forClass(ReminderNotificationEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().getScopeType()).isEqualTo(NotificationScopeType.PERSONAL);
        assertThat(captor.getValue().getRecipientUserIds()).containsExactly(OWNER_ID);
        assertThat(captor.getValue().getScopeId()).isEqualTo(OWNER_ID);
        assertThat(reminder.getNotified()).isTrue();
        verify(reminderRepository).save(reminder);
    }

    @Test
    @DisplayName("due_ABSOLUTE_所有者へ通知発火")
    void due_ABSOLUTE_所有者へ通知発火() {
        // given
        PersonalScheduleReminderEntity reminder = PersonalScheduleReminderEntity.builder()
                .scheduleId(SCHEDULE_ID)
                .reminderKind(ReminderKind.ABSOLUTE)
                .remindAt(LocalDateTime.now().minusMinutes(1))
                .notified(false)
                .build();
        given(reminderRepository.findDueReminders()).willReturn(List.of(reminder));
        given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(
                ScheduleEntity.builder()
                        .userId(OWNER_ID)
                        .title("会議")
                        .startAt(LocalDateTime.now().plusHours(1))
                        .build()));
        stubMessages();

        // when
        service.processDueReminders();

        // then
        verify(eventPublisher).publishEvent(any(ReminderNotificationEvent.class));
        assertThat(reminder.getNotified()).isTrue();
    }

    @Test
    @DisplayName("due対象なし_何もしない")
    void due対象なし_何もしない() {
        // given
        given(reminderRepository.findDueReminders()).willReturn(List.of());

        // when
        service.processDueReminders();

        // then
        verify(eventPublisher, never()).publishEvent(any(ReminderNotificationEvent.class));
        verify(reminderRepository, never()).save(any());
    }

    @Test
    @DisplayName("予定削除済み_通知せずnotifiedにマーク")
    void 予定削除済み_通知せずnotifiedにマーク() {
        // given
        PersonalScheduleReminderEntity reminder = PersonalScheduleReminderEntity.builder()
                .scheduleId(SCHEDULE_ID)
                .reminderKind(ReminderKind.RELATIVE)
                .remindBeforeMinutes(30)
                .notified(false)
                .build();
        given(reminderRepository.findDueReminders()).willReturn(List.of(reminder));
        given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.empty());
        stubMessages();

        // when
        service.processDueReminders();

        // then
        verify(eventPublisher, never()).publishEvent(any(ReminderNotificationEvent.class));
        assertThat(reminder.getNotified()).isTrue();
        verify(reminderRepository).save(reminder);
    }
}
