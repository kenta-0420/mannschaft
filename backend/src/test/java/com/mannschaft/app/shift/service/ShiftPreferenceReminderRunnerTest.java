package com.mannschaft.app.shift.service;

import com.mannschaft.app.shift.ShiftScheduleStatus;
import com.mannschaft.app.shift.entity.ShiftScheduleEntity;
import com.mannschaft.app.shift.event.ShiftPreferenceReminderKind;
import com.mannschaft.app.shift.event.ShiftPreferenceReminderNotificationEvent;
import com.mannschaft.app.shift.repository.ShiftScheduleRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link ShiftPreferenceReminderRunner} のユニットテスト（Issue #2997 / CMP-260827-1152 G6）。
 *
 * <p>AC-D: 送信済みの印（{@code is_reminder_sent_48h} / {@code is_reminder_sent}）の契約を固定する。
 * 印の確定と配送要求の publish は 1 スケジュール単位で完結し、再実行しても二重に印を立てず・二重に publish しない。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ShiftPreferenceReminderRunner 単体テスト")
class ShiftPreferenceReminderRunnerTest {

    private static final Long SCHEDULE_ID = 1L;
    private static final Long TEAM_ID = 10L;

    @Mock private ShiftScheduleRepository scheduleRepository;
    @Mock private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private ShiftPreferenceReminderRunner runner;

    private ShiftScheduleEntity collecting() {
        ShiftScheduleEntity entity = ShiftScheduleEntity.builder()
                .teamId(TEAM_ID)
                .title("テストシフト")
                .status(ShiftScheduleStatus.COLLECTING)
                .requestDeadline(LocalDateTime.now().plusHours(30))
                .endDate(LocalDate.now().plusDays(7))
                .build();
        ReflectionTestUtils.setField(entity, "id", SCHEDULE_ID);
        return entity;
    }

    @Test
    @DisplayName("48h: 印を立てて保存し、未提出者への配送要求を publish する")
    void marks48hAndPublishes() {
        ShiftScheduleEntity schedule = collecting();
        given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(schedule));

        assertThat(runner.remindOne(SCHEDULE_ID, ShiftPreferenceReminderKind.REMINDER_48H, List.of(101L, 102L)))
                .isTrue();

        assertThat(schedule.getIsReminderSent48h()).isTrue();
        assertThat(schedule.getIsReminderSent()).as("24h の印は触らない").isFalse();
        verify(scheduleRepository).save(schedule);
        ArgumentCaptor<ShiftPreferenceReminderNotificationEvent> captor =
                ArgumentCaptor.forClass(ShiftPreferenceReminderNotificationEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().kind()).isEqualTo(ShiftPreferenceReminderKind.REMINDER_48H);
        assertThat(captor.getValue().recipientUserIds()).containsExactly(101L, 102L);
        assertThat(captor.getValue().teamId()).isEqualTo(TEAM_ID);
        assertThat(captor.getValue().scheduleTitle()).isEqualTo("テストシフト");
    }

    @Test
    @DisplayName("24h: 24h の印だけを立てる")
    void marks24hOnly() {
        ShiftScheduleEntity schedule = collecting();
        given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(schedule));

        assertThat(runner.remindOne(SCHEDULE_ID, ShiftPreferenceReminderKind.REMINDER_24H, List.of(101L))).isTrue();

        assertThat(schedule.getIsReminderSent()).isTrue();
        assertThat(schedule.getIsReminderSent48h()).isFalse();
    }

    @Test
    @DisplayName("全員提出済み（未提出者が空）でも印は立つが、配送要求は publish しない（是正前と同じ）")
    void emptyRecipientsStillMarksButDoesNotPublish() {
        ShiftScheduleEntity schedule = collecting();
        given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(schedule));

        assertThat(runner.remindOne(SCHEDULE_ID, ShiftPreferenceReminderKind.REMINDER_48H, List.of())).isTrue();

        assertThat(schedule.getIsReminderSent48h()).isTrue();
        verify(scheduleRepository).save(schedule);
        verify(eventPublisher, never()).publishEvent(any(ShiftPreferenceReminderNotificationEvent.class));
    }

    @Test
    @DisplayName("AC-D: 既に印が立っているスケジュールには何もせず false（再実行で再送しない）")
    void alreadyMarkedIsNoop() {
        ShiftScheduleEntity schedule = collecting();
        schedule.markReminderSent48h();
        given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(schedule));

        assertThat(runner.remindOne(SCHEDULE_ID, ShiftPreferenceReminderKind.REMINDER_48H, List.of(101L))).isFalse();

        verify(scheduleRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any(ShiftPreferenceReminderNotificationEvent.class));
    }

    @Test
    @DisplayName("抽出後に COLLECTING でなくなっていたら何もせず false")
    void noLongerCollectingIsNoop() {
        ShiftScheduleEntity schedule = ShiftScheduleEntity.builder()
                .teamId(TEAM_ID).title("x").status(ShiftScheduleStatus.DRAFT).endDate(LocalDate.now()).build();
        ReflectionTestUtils.setField(schedule, "id", SCHEDULE_ID);
        given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(schedule));

        assertThat(runner.remindOne(SCHEDULE_ID, ShiftPreferenceReminderKind.REMINDER_48H, List.of(101L))).isFalse();

        verify(scheduleRepository, never()).save(any());
    }

    @Test
    @DisplayName("スケジュールが存在（または論理削除）しなければ何もせず false")
    void missingIsNoop() {
        given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.empty());

        assertThat(runner.remindOne(SCHEDULE_ID, ShiftPreferenceReminderKind.REMINDER_48H, List.of(101L))).isFalse();

        verify(scheduleRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any(ShiftPreferenceReminderNotificationEvent.class));
    }
}
