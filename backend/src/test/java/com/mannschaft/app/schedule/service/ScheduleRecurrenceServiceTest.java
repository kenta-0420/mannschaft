package com.mannschaft.app.schedule.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.dto.RecurrenceRuleDto;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ScheduleRecurrenceService} の繰り返し展開ロジック単体テスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ScheduleRecurrenceService 繰り返し展開ロジック 単体テスト")
class ScheduleRecurrenceServiceTest {

    @Mock
    private ScheduleRepository scheduleRepository;
    @Mock
    private ScheduleTargetService scheduleTargetService;

    private ScheduleRecurrenceService service;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        service = new ScheduleRecurrenceService(scheduleRepository, scheduleTargetService, objectMapper);
    }

    private ScheduleEntity buildParent(LocalDateTime startAt, String recurrenceRuleJson) {
        return ScheduleEntity.builder()
                .title("test")
                .startAt(startAt)
                .recurrenceRule(recurrenceRuleJson)
                .build();
    }

    private List<LocalDate> captureChildDates(int expectedCount) {
        ArgumentCaptor<ScheduleEntity> captor = ArgumentCaptor.forClass(ScheduleEntity.class);
        verify(scheduleRepository, times(expectedCount)).save(captor.capture());
        return captor.getAllValues().stream()
                .map(e -> e.getStartAt().toLocalDate())
                .toList();
    }

    @Test
    @DisplayName("CMP-107 詳細GET: 保存済み繰り返しルールをDTOへ復元し、単発はnull")
    void detailRecurrenceRuleDecoding() {
        RecurrenceRuleDto rule = service.deserializeRecurrenceRule(
                "{\"type\":\"WEEKLY\",\"interval\":1,\"daysOfWeek\":[\"MONDAY\"],"
                        + "\"endType\":\"COUNT\",\"count\":4}");

        assertThat(rule.type()).isEqualTo("WEEKLY");
        assertThat(rule.daysOfWeek()).containsExactly("MONDAY");
        assertThat(rule.count()).isEqualTo(4);
        assertThat(service.deserializeRecurrenceRule(null)).isNull();
        assertThat(service.deserializeRecurrenceRule("")).isNull();
    }

    private ScheduleEntity recurringRow(long id, Long parentId, LocalDateTime startAt, boolean exception) {
        return ScheduleEntity.builder()
                .id(id)
                .parentScheduleId(parentId)
                .title("繰り返し予定")
                .startAt(startAt)
                .isException(exception)
                .build();
    }

    @Test
    @DisplayName("CMP-107: THIS_AND_FOLLOWINGは起点以降の非例外行を一意に数える")
    void thisAndFollowingReturnsDistinctAppliedRowCount() {
        ScheduleEntity current = recurringRow(2L, 99L, LocalDateTime.of(2026, 9, 12, 10, 0), false);
        ScheduleEntity previous = recurringRow(1L, 99L, LocalDateTime.of(2026, 9, 5, 10, 0), false);
        ScheduleEntity exception = recurringRow(3L, 99L, LocalDateTime.of(2026, 9, 19, 10, 0), true);
        ScheduleEntity following = recurringRow(4L, 99L, LocalDateTime.of(2026, 9, 26, 10, 0), false);
        when(scheduleRepository.findByParentScheduleIdOrderByStartAtAsc(99L))
                .thenReturn(List.of(previous, current, exception, following));
        List<Long> appliedIds = new ArrayList<>();

        long affectedCount = service.updateRecurringSchedule(
                current, null, "THIS_AND_FOLLOWING", (schedule, request) -> appliedIds.add(schedule.getId()));

        assertThat(affectedCount).isEqualTo(2L);
        assertThat(appliedIds).containsOnly(2L, 4L);
    }

    @Test
    @DisplayName("CMP-107: 親からのTHIS_AND_FOLLOWINGは親と非例外子を一意に数える")
    void thisAndFollowingFromParentIncludesParentAndNonExceptionChildren() {
        ScheduleEntity parent = recurringRow(99L, null, LocalDateTime.of(2026, 9, 1, 10, 0), false)
                .toBuilder().recurrenceRule("{}").build();
        ScheduleEntity child = recurringRow(1L, 99L, LocalDateTime.of(2026, 9, 8, 10, 0), false);
        ScheduleEntity exception = recurringRow(2L, 99L, LocalDateTime.of(2026, 9, 15, 10, 0), true);
        when(scheduleRepository.findByParentScheduleIdOrderByStartAtAsc(99L))
                .thenReturn(List.of(child, exception));

        long affectedCount = service.updateRecurringSchedule(
                parent, null, "THIS_AND_FOLLOWING", (schedule, request) -> { });

        assertThat(affectedCount).isEqualTo(2L);
    }

    @Test
    @DisplayName("CMP-107: ALLは親と非例外子だけを一意に数える")
    void allIncludesParentAndExcludesExceptionChildren() {
        ScheduleEntity current = recurringRow(1L, 99L, LocalDateTime.of(2026, 9, 8, 10, 0), false);
        ScheduleEntity parent = recurringRow(99L, null, LocalDateTime.of(2026, 9, 1, 10, 0), false);
        ScheduleEntity normal1 = recurringRow(1L, 99L, LocalDateTime.of(2026, 9, 8, 10, 0), false);
        ScheduleEntity exception = recurringRow(2L, 99L, LocalDateTime.of(2026, 9, 15, 10, 0), true);
        ScheduleEntity normal2 = recurringRow(3L, 99L, LocalDateTime.of(2026, 9, 22, 10, 0), false);
        when(scheduleRepository.findById(99L)).thenReturn(java.util.Optional.of(parent));
        when(scheduleRepository.findByParentScheduleIdOrderByStartAtAsc(99L))
                .thenReturn(List.of(normal1, exception, normal2));

        long affectedCount = service.updateRecurringSchedule(
                current, null, "ALL", (schedule, request) -> { });

        assertThat(affectedCount).isEqualTo(3L);
    }

    @Test
    @DisplayName("daily_interval1_count3: children [9/6, 9/7, 9/8]")
    void daily_interval1_count3() {
        ScheduleEntity parent = buildParent(
                LocalDateTime.of(2026, 9, 5, 10, 0),
                "{\"type\":\"DAILY\",\"interval\":1,\"endType\":\"COUNT\",\"count\":3}"
        );
        when(scheduleRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.expandRecurrenceSchedules(parent);

        List<LocalDate> dates = captureChildDates(3);
        assertThat(dates).containsExactly(
                LocalDate.of(2026, 9, 6),
                LocalDate.of(2026, 9, 7),
                LocalDate.of(2026, 9, 8)
        );
    }

    @Test
    @DisplayName("daily_interval2_count2: children [9/7, 9/9]")
    void daily_interval2_count2() {
        ScheduleEntity parent = buildParent(
                LocalDateTime.of(2026, 9, 5, 10, 0),
                "{\"type\":\"DAILY\",\"interval\":2,\"endType\":\"COUNT\",\"count\":2}"
        );
        when(scheduleRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.expandRecurrenceSchedules(parent);

        List<LocalDate> dates = captureChildDates(2);
        assertThat(dates).containsExactly(
                LocalDate.of(2026, 9, 7),
                LocalDate.of(2026, 9, 9)
        );
    }

    @Test
    @DisplayName("weekly_interval1_count3: children [9/12, 9/19, 9/26]")
    void weekly_interval1_count3() {
        ScheduleEntity parent = buildParent(
                LocalDateTime.of(2026, 9, 5, 10, 0),
                "{\"type\":\"WEEKLY\",\"interval\":1,\"endType\":\"COUNT\",\"count\":3}"
        );
        when(scheduleRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.expandRecurrenceSchedules(parent);

        List<LocalDate> dates = captureChildDates(3);
        assertThat(dates).containsExactly(
                LocalDate.of(2026, 9, 12),
                LocalDate.of(2026, 9, 19),
                LocalDate.of(2026, 9, 26)
        );
    }

    @Test
    @DisplayName("monthly_lastDay_anchor: 2027/1/31 COUNT=3 -> [2/28, 3/31, 4/30] (bug1 regression)")
    void monthly_lastDay_anchor() {
        ScheduleEntity parent = buildParent(
                LocalDateTime.of(2027, 1, 31, 10, 0),
                "{\"type\":\"MONTHLY\",\"interval\":1,\"endType\":\"COUNT\",\"count\":3}"
        );
        when(scheduleRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.expandRecurrenceSchedules(parent);

        List<LocalDate> dates = captureChildDates(3);
        assertThat(dates).containsExactly(
                LocalDate.of(2027, 2, 28),
                LocalDate.of(2027, 3, 31),
                LocalDate.of(2027, 4, 30)
        );
    }

    @Test
    @DisplayName("yearly_count2_notCutOff: 2026/9/5 COUNT=2 -> [2027/9/5, 2028/9/5] (bug2 regression)")
    void yearly_count2_notCutOff() {
        ScheduleEntity parent = buildParent(
                LocalDateTime.of(2026, 9, 5, 10, 0),
                "{\"type\":\"YEARLY\",\"interval\":1,\"endType\":\"COUNT\",\"count\":2}"
        );
        when(scheduleRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.expandRecurrenceSchedules(parent);

        List<LocalDate> dates = captureChildDates(2);
        assertThat(dates).containsExactly(
                LocalDate.of(2027, 9, 5),
                LocalDate.of(2028, 9, 5)
        );
    }

    @Test
    @DisplayName("daily_endDate: base 9/5 endDate 9/9 -> [9/6, 9/7, 9/8, 9/9]")
    void daily_endDate() {
        ScheduleEntity parent = buildParent(
                LocalDateTime.of(2026, 9, 5, 10, 0),
                "{\"type\":\"DAILY\",\"interval\":1,\"endType\":\"DATE\",\"endDate\":\"2026-09-09\"}"
        );
        when(scheduleRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.expandRecurrenceSchedules(parent);

        List<LocalDate> dates = captureChildDates(4);
        assertThat(dates).containsExactly(
                LocalDate.of(2026, 9, 6),
                LocalDate.of(2026, 9, 7),
                LocalDate.of(2026, 9, 8),
                LocalDate.of(2026, 9, 9)
        );
    }
}
