package com.mannschaft.app.schedule.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.dto.UpdateScheduleRequest;
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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

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
    @DisplayName("THIS_AND_FOLLOWINGは選択行と以降の非例外子だけを一度ずつ数える")
    void thisAndFollowing_countsSelectedAndFollowingNonExceptionChildrenWithoutDuplicate() {
        // given
        ScheduleEntity selected = child(10L, LocalDateTime.of(2026, 9, 10, 10, 0), false);
        ScheduleEntity past = child(11L, LocalDateTime.of(2026, 9, 3, 10, 0), false);
        ScheduleEntity selectedFromQuery = child(10L, LocalDateTime.of(2026, 9, 10, 10, 0), false);
        ScheduleEntity exception = child(12L, LocalDateTime.of(2026, 9, 17, 10, 0), true);
        ScheduleEntity following = child(13L, LocalDateTime.of(2026, 9, 24, 10, 0), false);
        when(scheduleRepository.findByParentScheduleIdOrderByStartAtAsc(1L))
                .thenReturn(List.of(past, selectedFromQuery, exception, following));
        AtomicInteger applied = new AtomicInteger();

        // when
        long affectedCount = service.updateRecurringSchedule(selected, null, "THIS_AND_FOLLOWING",
                (schedule, ignored) -> { applied.incrementAndGet(); return schedule; }).affectedCount();

        // then
        assertThat(affectedCount).isEqualTo(2);
        assertThat(applied).hasValue(2);
    }

    @Test
    @DisplayName("THIS_AND_FOLLOWINGは選択行が例外でも起点1件を数える")
    void thisAndFollowing_countsSelectedExceptionAndFutureNonExceptionChildren() {
        // given
        ScheduleEntity selectedException = child(10L, LocalDateTime.of(2026, 9, 10, 10, 0), true);
        ScheduleEntity futureException = child(11L, LocalDateTime.of(2026, 9, 17, 10, 0), true);
        ScheduleEntity futureNonException = child(12L, LocalDateTime.of(2026, 9, 24, 10, 0), false);
        when(scheduleRepository.findByParentScheduleIdOrderByStartAtAsc(1L))
                .thenReturn(List.of(selectedException, futureException, futureNonException));
        AtomicInteger applied = new AtomicInteger();

        // when
        long affectedCount = service.updateRecurringSchedule(selectedException, null, "THIS_AND_FOLLOWING",
                (schedule, ignored) -> { applied.incrementAndGet(); return schedule; }).affectedCount();

        // then
        assertThat(affectedCount).isEqualTo(2);
        assertThat(applied).hasValue(2);
    }

    @Test
    @DisplayName("ALLは親と全非例外子を数え、例外子を含めない")
    void all_countsParentAndAllNonExceptionChildren() {
        // given
        ScheduleEntity selected = child(10L, LocalDateTime.of(2026, 9, 10, 10, 0), false);
        ScheduleEntity parent = ScheduleEntity.builder().id(1L).title("parent")
                .startAt(LocalDateTime.of(2026, 9, 3, 10, 0)).build();
        when(scheduleRepository.findById(1L)).thenReturn(java.util.Optional.of(parent));
        when(scheduleRepository.findByParentScheduleIdOrderByStartAtAsc(1L))
                .thenReturn(List.of(selected, child(11L, LocalDateTime.of(2026, 9, 17, 10, 0), true),
                        child(12L, LocalDateTime.of(2026, 9, 24, 10, 0), false)));
        AtomicInteger applied = new AtomicInteger();

        // when
        long affectedCount = service.updateRecurringSchedule(selected, null, "ALL",
                (schedule, ignored) -> { applied.incrementAndGet(); return schedule; }).affectedCount();

        // then
        assertThat(affectedCount).isEqualTo(3);
        assertThat(applied).hasValue(3);
    }

    @Test
    @DisplayName("THIS_ONLYは単発の実更新1件を返す")
    void thisOnly_countsOne() {
        // given
        ScheduleEntity schedule = ScheduleEntity.builder().id(1L).title("single")
                .startAt(LocalDateTime.of(2026, 9, 10, 10, 0)).build();
        AtomicInteger applied = new AtomicInteger();

        // when
        long affectedCount = service.updateRecurringSchedule(schedule, null, "THIS_ONLY",
                (target, ignored) -> { applied.incrementAndGet(); return target; }).affectedCount();

        // then
        assertThat(affectedCount).isOne();
        assertThat(applied).hasValue(1);
    }

    @Test
    @DisplayName("THIS_ONLYの子は更新後の値と例外フラグを同時に返す")
    void thisOnly_returnsUpdatedExceptionChild() {
        ScheduleEntity child = child(10L, LocalDateTime.of(2026, 9, 10, 10, 0), false);

        ScheduleRecurrenceService.RecurringScheduleUpdateResult result =
                service.updateRecurringSchedule(child, null, "THIS_ONLY",
                (target, ignored) -> target.toBuilder().title("updated").build());

        assertThat(result.selectedSchedule().getTitle()).isEqualTo("updated");
        assertThat(result.selectedSchedule().getIsException()).isTrue();
        assertThat(result.affectedCount()).isOne();
    }

    @Test
    @DisplayName("THIS_AND_FOLLOWINGは選択行の更新後Entityを返す")
    void thisAndFollowing_returnsUpdatedSelectedSchedule() {
        ScheduleEntity child = child(10L, LocalDateTime.of(2026, 9, 10, 10, 0), false);
        when(scheduleRepository.findByParentScheduleIdOrderByStartAtAsc(1L)).thenReturn(List.of(child));

        ScheduleRecurrenceService.RecurringScheduleUpdateResult result =
                service.updateRecurringSchedule(child, null, "THIS_AND_FOLLOWING",
                (target, ignored) -> target.toBuilder().title("updated").build());

        assertThat(result.selectedSchedule().getTitle()).isEqualTo("updated");
    }

    @Test
    @DisplayName("ALLは選択例外を更新せず元のEntityを返す")
    void all_doesNotApplySelectedException() {
        ScheduleEntity selectedException = child(10L, LocalDateTime.of(2026, 9, 10, 10, 0), true);
        ScheduleEntity parent = ScheduleEntity.builder().id(1L).title("parent")
                .startAt(LocalDateTime.of(2026, 9, 3, 10, 0)).build();
        when(scheduleRepository.findById(1L)).thenReturn(java.util.Optional.of(parent));
        when(scheduleRepository.findByParentScheduleIdOrderByStartAtAsc(1L)).thenReturn(List.of(selectedException));
        List<Long> appliedIds = new ArrayList<>();

        ScheduleRecurrenceService.RecurringScheduleUpdateResult result =
                service.updateRecurringSchedule(selectedException, null, "ALL",
                (target, ignored) -> { appliedIds.add(target.getId()); return target.toBuilder().title("updated").build(); });

        assertThat(appliedIds).containsExactly(1L);
        assertThat(result.selectedSchedule()).isSameAs(selectedException);
    }

    private ScheduleEntity child(Long id, LocalDateTime startAt, boolean exception) {
        return ScheduleEntity.builder().id(id).parentScheduleId(1L).title("child")
                .startAt(startAt).isException(exception).build();
    }

    private UpdateScheduleRequest requestWithTimes(String title, LocalDateTime startAt, LocalDateTime endAt) {
        return new UpdateScheduleRequest(title, null, null,
                startAt.atOffset(ZoneOffset.ofHours(9)),
                endAt.atOffset(ZoneOffset.ofHours(9)), null,
                null, null, null, null, null, null, null, null,
                null, null, null, null, null);
    }

    @Test
    @DisplayName("THIS_AND_FOLLOWING: タイトルだけの編集で後続行の日時を複製しない")
    void followingTitleEditKeepsDistinctTimes() {
        ScheduleEntity selected = child(10L, LocalDateTime.of(2026, 9, 10, 10, 0), false)
                .toBuilder().endAt(LocalDateTime.of(2026, 9, 10, 11, 0)).build();
        ScheduleEntity following = child(11L, LocalDateTime.of(2026, 9, 17, 10, 0), false)
                .toBuilder().endAt(LocalDateTime.of(2026, 9, 17, 11, 0)).build();
        when(scheduleRepository.findByParentScheduleIdOrderByStartAtAsc(1L))
                .thenReturn(List.of(selected, following));
        Map<Long, UpdateScheduleRequest> applied = new HashMap<>();
        UpdateScheduleRequest request = requestWithTimes("new", selected.getStartAt(), selected.getEndAt());

        service.updateRecurringSchedule(selected, request, "THIS_AND_FOLLOWING",
                (entity, update) -> { applied.put(entity.getId(), update); return entity; });

        assertThat(applied.get(10L).getStartAt()).isEqualTo(request.getStartAt());
        assertThat(applied.get(11L).getTitle()).isEqualTo("new");
        assertThat(applied.get(11L).getStartAt()).isNull();
        assertThat(applied.get(11L).getEndAt()).isNull();
    }

    @Test
    @DisplayName("THIS_AND_FOLLOWING: 後続行の元日時へ同じ時刻差分を適用する")
    void followingTimeEditShiftsEachOccurrence() {
        ScheduleEntity selected = child(10L, LocalDateTime.of(2026, 9, 10, 10, 0), false)
                .toBuilder().endAt(LocalDateTime.of(2026, 9, 10, 11, 0)).build();
        ScheduleEntity following = child(11L, LocalDateTime.of(2026, 9, 17, 10, 0), false)
                .toBuilder().endAt(LocalDateTime.of(2026, 9, 17, 11, 0)).build();
        when(scheduleRepository.findByParentScheduleIdOrderByStartAtAsc(1L))
                .thenReturn(List.of(selected, following));
        Map<Long, UpdateScheduleRequest> applied = new HashMap<>();
        UpdateScheduleRequest request = requestWithTimes("new",
                LocalDateTime.of(2026, 9, 10, 10, 30), LocalDateTime.of(2026, 9, 10, 11, 30));

        service.updateRecurringSchedule(selected, request, "THIS_AND_FOLLOWING",
                (entity, update) -> {
                    applied.put(entity.getId(), update);
                    if (entity.getId().equals(10L)) {
                        entity.updateScheduleFields(entity.getTitle(), entity.getDescription(),
                                entity.getLocation(), LocalDateTime.of(2026, 9, 10, 10, 30),
                                LocalDateTime.of(2026, 9, 10, 11, 30), entity.getColor());
                    }
                    return entity;
                });

        assertThat(applied.get(11L).getStartAt()).isEqualTo(
                OffsetDateTime.of(2026, 9, 17, 10, 30, 0, 0, ZoneOffset.ofHours(9)));
        assertThat(applied.get(11L).getEndAt()).isEqualTo(
                OffsetDateTime.of(2026, 9, 17, 11, 30, 0, 0, ZoneOffset.ofHours(9)));
    }

    @Test
    @DisplayName("ALL: API互換の全範囲更新でも子行へ親の絶対日時を複製しない")
    void allTitleEditKeepsChildTimes() {
        ScheduleEntity parent = ScheduleEntity.builder().id(1L).title("parent")
                .startAt(LocalDateTime.of(2026, 9, 3, 10, 0))
                .endAt(LocalDateTime.of(2026, 9, 3, 11, 0)).build();
        ScheduleEntity child = child(10L, LocalDateTime.of(2026, 9, 10, 10, 0), false)
                .toBuilder().endAt(LocalDateTime.of(2026, 9, 10, 11, 0)).build();
        when(scheduleRepository.findById(1L)).thenReturn(java.util.Optional.of(parent));
        when(scheduleRepository.findByParentScheduleIdOrderByStartAtAsc(1L))
                .thenReturn(List.of(child));
        Map<Long, UpdateScheduleRequest> applied = new HashMap<>();
        UpdateScheduleRequest request = requestWithTimes("new", parent.getStartAt(), parent.getEndAt());

        service.updateRecurringSchedule(parent, request, "ALL",
                (entity, update) -> { applied.put(entity.getId(), update); return entity; });

        assertThat(applied.get(10L).getStartAt()).isNull();
        assertThat(applied.get(10L).getEndAt()).isNull();
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
