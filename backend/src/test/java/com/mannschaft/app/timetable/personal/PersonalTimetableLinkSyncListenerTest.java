package com.mannschaft.app.timetable.personal;

import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import com.mannschaft.app.timetable.TimetableChangeType;
import com.mannschaft.app.timetable.WeekPattern;
import com.mannschaft.app.timetable.entity.TimetableChangeEntity;
import com.mannschaft.app.timetable.event.TimetableChangeCreatedEvent;
import com.mannschaft.app.timetable.event.TimetableChangeDeletedEvent;
import com.mannschaft.app.timetable.personal.entity.PersonalTimetableEntity;
import com.mannschaft.app.timetable.personal.entity.PersonalTimetablePeriodEntity;
import com.mannschaft.app.timetable.personal.entity.PersonalTimetableSettingsEntity;
import com.mannschaft.app.timetable.personal.entity.PersonalTimetableSlotEntity;
import com.mannschaft.app.timetable.personal.listener.PersonalTimetableLinkSyncListener;
import com.mannschaft.app.timetable.personal.repository.PersonalTimetablePeriodRepository;
import com.mannschaft.app.timetable.personal.repository.PersonalTimetableRepository;
import com.mannschaft.app.timetable.personal.repository.PersonalTimetableSettingsRepository;
import com.mannschaft.app.timetable.personal.repository.PersonalTimetableSlotRepository;
import com.mannschaft.app.timetable.repository.TimetableChangeRepository;
import com.mannschaft.app.timetable.repository.TimetableSlotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * F03.15 Phase 4 PersonalTimetableLinkSyncListener のユニットテスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PersonalTimetableLinkSyncListener ユニットテスト")
class PersonalTimetableLinkSyncListenerTest {

    private static final Long USER_ID = 100L;
    private static final Long PT_ID = 1L;
    private static final Long SLOT_ID = 11L;
    private static final Long TT_ID = 200L;
    private static final Long CHANGE_ID = 300L;

    @Mock private PersonalTimetableSlotRepository personalSlotRepository;
    @Mock private PersonalTimetableRepository personalTimetableRepository;
    @Mock private PersonalTimetablePeriodRepository personalPeriodRepository;
    @Mock private PersonalTimetableSettingsRepository settingsRepository;
    @Mock private TimetableChangeRepository timetableChangeRepository;
    @Mock private TimetableSlotRepository timetableSlotRepository;
    @Mock private ScheduleRepository scheduleRepository;

    private PersonalTimetableLinkSyncListener listener;

    @BeforeEach
    void setUp() {
        // notificationHelper は Optional 注入なので null のまま動かす
        listener = new PersonalTimetableLinkSyncListener(
                personalSlotRepository,
                personalTimetableRepository,
                personalPeriodRepository,
                settingsRepository,
                timetableChangeRepository,
                timetableSlotRepository,
                scheduleRepository);
    }

    private static PersonalTimetableSlotEntity buildSlot(String dow, int period) {
        PersonalTimetableSlotEntity s = PersonalTimetableSlotEntity.builder()
                .personalTimetableId(PT_ID)
                .dayOfWeek(dow)
                .periodNumber(period)
                .weekPattern(WeekPattern.EVERY)
                .subjectName("ドイツ語Ⅰ")
                .roomName("L棟401")
                .autoSyncChanges(true)
                .linkedTeamId(50L)
                .linkedTimetableId(TT_ID)
                .build();
        try {
            Field idField = s.getClass().getSuperclass().getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(s, SLOT_ID);
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException(ex);
        }
        return s;
    }

    private static PersonalTimetableEntity buildPersonal() {
        PersonalTimetableEntity p = PersonalTimetableEntity.builder()
                .userId(USER_ID)
                .name("test")
                .status(PersonalTimetableStatus.ACTIVE)
                .effectiveFrom(LocalDate.of(2026, 1, 1))
                .effectiveUntil(LocalDate.of(2026, 12, 31))
                .build();
        try {
            Field idField = p.getClass().getSuperclass().getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(p, PT_ID);
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException(ex);
        }
        return p;
    }

    private static PersonalTimetablePeriodEntity buildPeriod(int periodNumber) {
        return PersonalTimetablePeriodEntity.builder()
                .personalTimetableId(PT_ID)
                .periodNumber(periodNumber)
                .label(periodNumber + "限")
                .startTime(LocalTime.of(13, 0))
                .endTime(LocalTime.of(14, 30))
                .isBreak(false)
                .build();
    }

    private static TimetableChangeEntity buildChange(TimetableChangeType type, LocalDate date, Integer period) {
        TimetableChangeEntity c = TimetableChangeEntity.builder()
                .timetableId(TT_ID)
                .targetDate(date)
                .periodNumber(period)
                .changeType(type)
                .reason("教員体調不良")
                .notifyMembers(true)
                .build();
        try {
            Field idField = c.getClass().getSuperclass().getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(c, CHANGE_ID);
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException(ex);
        }
        return c;
    }

    @Test
    @DisplayName("CANCEL: 個人スケジュールに [休講] レコードを INSERT する")
    void onChangeCreated_CANCEL_INSERT() {
        // 2026-05-04 は MON
        LocalDate target = LocalDate.of(2026, 5, 4);
        TimetableChangeEntity change = buildChange(TimetableChangeType.CANCEL, target, 3);

        given(timetableChangeRepository.findById(CHANGE_ID)).willReturn(Optional.of(change));
        given(timetableChangeRepository
                .findByTimetableIdAndTargetDateAndPeriodNumberIsNull(TT_ID, target))
                .willReturn(Optional.empty());
        given(personalSlotRepository.findByLinkedTimetableId(TT_ID))
                .willReturn(List.of(buildSlot("MON", 3)));
        given(personalTimetableRepository.findById(PT_ID))
                .willReturn(Optional.of(buildPersonal()));
        given(settingsRepository.findById(USER_ID)).willReturn(Optional.empty()); // デフォルト ON
        given(personalPeriodRepository.findByPersonalTimetableIdOrderByPeriodNumberAsc(PT_ID))
                .willReturn(List.of(buildPeriod(3)));
        given(scheduleRepository.findByExternalRef(any())).willReturn(Optional.empty());
        given(scheduleRepository.save(any(ScheduleEntity.class)))
                .willAnswer(inv -> inv.getArgument(0));

        listener.onChangeCreated(new TimetableChangeCreatedEvent(
                CHANGE_ID, TT_ID, null, TimetableChangeType.CANCEL, target, true, true));

        verify(personalSlotRepository).findByLinkedTimetableId(TT_ID);
        verify(personalTimetableRepository).findById(PT_ID);
        verify(settingsRepository).findById(USER_ID);
        verify(personalPeriodRepository).findByPersonalTimetableIdOrderByPeriodNumberAsc(PT_ID);
        ArgumentCaptor<ScheduleEntity> captor = ArgumentCaptor.forClass(ScheduleEntity.class);
        verify(scheduleRepository).save(captor.capture());
        ScheduleEntity saved = captor.getValue();
        assertThat(saved.getTitle()).contains("[休講]");
        assertThat(saved.getUserId()).isEqualTo(USER_ID);
        assertThat(saved.getExternalRef()).isEqualTo("F03.15:" + CHANGE_ID + ":" + SLOT_ID);
    }

    @Test
    @DisplayName("DAY_OFF と CANCEL の重複時、CANCEL は無視される")
    void onChangeCreated_DAY_OFF優先() {
        LocalDate target = LocalDate.of(2026, 5, 4);
        TimetableChangeEntity cancel = buildChange(TimetableChangeType.CANCEL, target, 3);
        TimetableChangeEntity dayOff = TimetableChangeEntity.builder()
                .timetableId(TT_ID)
                .targetDate(target)
                .periodNumber(null)
                .changeType(TimetableChangeType.DAY_OFF)
                .build();

        given(timetableChangeRepository.findById(CHANGE_ID)).willReturn(Optional.of(cancel));
        given(timetableChangeRepository
                .findByTimetableIdAndTargetDateAndPeriodNumberIsNull(TT_ID, target))
                .willReturn(Optional.of(dayOff));

        listener.onChangeCreated(new TimetableChangeCreatedEvent(
                CHANGE_ID, TT_ID, null, TimetableChangeType.CANCEL, target, true, true));

        verify(scheduleRepository, never()).save(any());
    }

    @Test
    @DisplayName("auto_sync_changes = false のコマはスキップ")
    void onChangeCreated_auto_sync_OFF() {
        LocalDate target = LocalDate.of(2026, 5, 4);
        TimetableChangeEntity change = buildChange(TimetableChangeType.CANCEL, target, 3);
        PersonalTimetableSlotEntity slot = buildSlot("MON", 3);
        slot.linkTo(50L, TT_ID, null, false); // auto_sync OFF

        given(timetableChangeRepository.findById(CHANGE_ID)).willReturn(Optional.of(change));
        given(timetableChangeRepository
                .findByTimetableIdAndTargetDateAndPeriodNumberIsNull(TT_ID, target))
                .willReturn(Optional.empty());
        given(personalSlotRepository.findByLinkedTimetableId(TT_ID)).willReturn(List.of(slot));

        listener.onChangeCreated(new TimetableChangeCreatedEvent(
                CHANGE_ID, TT_ID, null, TimetableChangeType.CANCEL, target, true, true));

        verify(scheduleRepository, never()).save(any());
    }

    @Test
    @DisplayName("CANCEL 削除時: external_ref で紐付くスケジュールを論理削除")
    void onChangeDeleted_論理削除() {
        ScheduleEntity sch = ScheduleEntity.builder()
                .userId(USER_ID).title("[休講] X").externalRef("F03.15:" + CHANGE_ID + ":" + SLOT_ID)
                .build();
        given(scheduleRepository.findByExternalRefPrefix("F03.15:" + CHANGE_ID + ":%"))
                .willReturn(List.of(sch));
        given(scheduleRepository.save(any(ScheduleEntity.class)))
                .willAnswer(inv -> inv.getArgument(0));

        listener.onChangeDeleted(new TimetableChangeDeletedEvent(CHANGE_ID, TT_ID));

        verify(scheduleRepository).save(any(ScheduleEntity.class));
    }
}
