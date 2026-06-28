package com.mannschaft.app.schedule.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
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

    private ScheduleRecurrenceService service;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        service = new ScheduleRecurrenceService(scheduleRepository, objectMapper);
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