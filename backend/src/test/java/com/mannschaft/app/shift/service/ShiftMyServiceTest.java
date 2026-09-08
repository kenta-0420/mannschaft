package com.mannschaft.app.shift.service;

import com.mannschaft.app.shift.ShiftAssignmentStatus;
import com.mannschaft.app.shift.ShiftScheduleStatus;
import com.mannschaft.app.shift.dto.MyConfirmedSlotResponse;
import com.mannschaft.app.shift.entity.ShiftAssignmentEntity;
import com.mannschaft.app.shift.entity.ShiftPositionEntity;
import com.mannschaft.app.shift.entity.ShiftScheduleEntity;
import com.mannschaft.app.shift.entity.ShiftSlotEntity;
import com.mannschaft.app.shift.repository.ShiftAssignmentRepository;
import com.mannschaft.app.shift.repository.ShiftPositionRepository;
import com.mannschaft.app.shift.repository.ShiftScheduleRepository;
import com.mannschaft.app.shift.repository.ShiftSlotRepository;
import com.mannschaft.app.team.repository.TeamRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** CMP-260903-0651: 自分の確定シフト一覧から未公開シフト表を遮断する単体テスト。 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CMP-260903-0651: 自分の確定シフト一覧の公開境界")
class ShiftMyServiceTest {

    private static final Long USER_ID = 99L;
    private static final Long TEAM_ID = 1L;
    private static final LocalDate BASE_DATE = LocalDate.of(2026, 9, 8);

    @Mock
    private ShiftAssignmentRepository assignmentRepository;
    @Mock
    private ShiftSlotRepository slotRepository;
    @Mock
    private ShiftScheduleRepository scheduleRepository;
    @Mock
    private ShiftPositionRepository positionRepository;
    @Mock
    private TeamRepository teamRepository;

    @InjectMocks
    private ShiftMyService shiftMyService;

    @Test
    @DisplayName("FULL の PUBLISHED と公開済み ARCHIVED だけを返し、各リポジトリを一括取得する")
    void fullのシフト表だけを返す() {
        List<ShiftScheduleEntity> schedules = List.of(
                schedule(1L, ShiftScheduleStatus.DRAFT, null),
                schedule(2L, ShiftScheduleStatus.COLLECTING, null),
                schedule(3L, ShiftScheduleStatus.ADJUSTING, null),
                schedule(4L, ShiftScheduleStatus.PUBLISHED, null),
                schedule(5L, ShiftScheduleStatus.ARCHIVED, null),
                schedule(6L, ShiftScheduleStatus.ARCHIVED, LocalDateTime.of(2026, 9, 1, 9, 0)));
        givenAssignments(1L, 2L, 3L, 4L, 5L, 6L);
        given(slotRepository.findAllByIdIn(anySet())).willReturn(List.of(
                slot(1L, BASE_DATE, LocalTime.of(9, 0), null),
                slot(2L, BASE_DATE, LocalTime.of(10, 0), null),
                slot(3L, BASE_DATE, LocalTime.of(11, 0), null),
                slot(4L, BASE_DATE, LocalTime.of(12, 0), null),
                slot(5L, BASE_DATE, LocalTime.of(13, 0), null),
                slot(6L, BASE_DATE, LocalTime.of(14, 0), null)));
        given(scheduleRepository.findAllById(anySet())).willReturn(schedules);
        given(teamRepository.findAllById(anySet())).willReturn(List.of());

        List<MyConfirmedSlotResponse> result = shiftMyService.getMyConfirmedSlots(USER_ID);

        assertThat(result).extracting(MyConfirmedSlotResponse::getScheduleId)
                .containsExactly(4L, 6L);
        verify(assignmentRepository).findAllByUserIdAndStatus(USER_ID, ShiftAssignmentStatus.CONFIRMED);
        verify(slotRepository).findAllByIdIn(anySet());
        verify(scheduleRepository).findAllById(anySet());
        verify(teamRepository).findAllById(anySet());
        verify(positionRepository, never()).findAllById(anySet());
    }

    @Test
    @DisplayName("割当がなければ空配列を返し、後続リポジトリを呼ばない")
    void 割当がなければ後続リポジトリを呼ばない() {
        given(assignmentRepository.findAllByUserIdAndStatus(USER_ID, ShiftAssignmentStatus.CONFIRMED))
                .willReturn(List.of());

        assertThat(shiftMyService.getMyConfirmedSlots(USER_ID)).isEmpty();

        verifyNoInteractions(slotRepository, scheduleRepository, positionRepository, teamRepository);
    }

    @Test
    @DisplayName("割当に対応する slot が欠損していれば fail-closed で除外する")
    void slotが欠損していれば除外する() {
        givenAssignments(1L);
        given(slotRepository.findAllByIdIn(anySet())).willReturn(List.of());
        given(scheduleRepository.findAllById(anySet())).willReturn(List.of());
        given(teamRepository.findAllById(anySet())).willReturn(List.of());

        assertThat(shiftMyService.getMyConfirmedSlots(USER_ID)).isEmpty();
    }

    @Test
    @DisplayName("slot に対応する schedule が欠損していれば fail-closed で除外する")
    void scheduleが欠損していれば除外する() {
        givenAssignments(1L);
        given(slotRepository.findAllByIdIn(anySet()))
                .willReturn(List.of(slot(1L, BASE_DATE, LocalTime.NOON, null)));
        given(scheduleRepository.findAllById(anySet())).willReturn(List.of());
        given(teamRepository.findAllById(anySet())).willReturn(List.of());

        assertThat(shiftMyService.getMyConfirmedSlots(USER_ID)).isEmpty();
    }

    @Test
    @DisplayName("schedule status が null なら fail-closed で除外する")
    void statusがnullなら除外する() {
        givenAssignments(1L);
        given(slotRepository.findAllByIdIn(anySet()))
                .willReturn(List.of(slot(1L, BASE_DATE, LocalTime.NOON, null)));
        given(scheduleRepository.findAllById(anySet()))
                .willReturn(List.of(schedule(1L, null, null)));
        given(teamRepository.findAllById(anySet())).willReturn(List.of());

        assertThat(shiftMyService.getMyConfirmedSlots(USER_ID)).isEmpty();
    }

    @Test
    @DisplayName("position 未設定でも公開済み割当を返し、positionName は null のままにする")
    void position未設定を保持する() {
        givenAssignments(1L);
        given(slotRepository.findAllByIdIn(anySet()))
                .willReturn(List.of(slot(1L, BASE_DATE, LocalTime.NOON, null)));
        given(scheduleRepository.findAllById(anySet()))
                .willReturn(List.of(schedule(1L, ShiftScheduleStatus.PUBLISHED, null)));
        given(teamRepository.findAllById(anySet())).willReturn(List.of());

        List<MyConfirmedSlotResponse> result = shiftMyService.getMyConfirmedSlots(USER_ID);

        assertThat(result).singleElement()
                .extracting(MyConfirmedSlotResponse::getPositionName)
                .isNull();
        verifyNoInteractions(positionRepository);
    }

    @Test
    @DisplayName("複数の position を一括取得してそれぞれの名称を返す")
    void position名を一括取得する() {
        givenAssignments(1L, 2L);
        given(slotRepository.findAllByIdIn(anySet())).willReturn(List.of(
                slot(1L, BASE_DATE, LocalTime.NOON, 10L),
                slot(2L, BASE_DATE, LocalTime.of(13, 0), 20L)));
        given(scheduleRepository.findAllById(anySet())).willReturn(List.of(
                schedule(1L, ShiftScheduleStatus.PUBLISHED, null),
                schedule(2L, ShiftScheduleStatus.PUBLISHED, null)));
        given(teamRepository.findAllById(anySet())).willReturn(List.of());
        given(positionRepository.findAllById(anySet()))
                .willReturn(List.of(position(10L, "受付"), position(20L, "会計")));

        List<MyConfirmedSlotResponse> result = shiftMyService.getMyConfirmedSlots(USER_ID);

        assertThat(result).extracting(MyConfirmedSlotResponse::getPositionName)
                .containsExactly("受付", "会計");
        verify(positionRepository).findAllById(anySet());
    }

    @Test
    @DisplayName("返却順は slot の入力順によらず日付、開始時刻の昇順になる")
    void 日付と開始時刻で並べる() {
        givenAssignments(1L, 2L, 3L);
        given(slotRepository.findAllByIdIn(anySet())).willReturn(List.of(
                slot(1L, BASE_DATE.plusDays(1), LocalTime.of(9, 0), null),
                slot(2L, BASE_DATE, LocalTime.of(13, 0), null),
                slot(3L, BASE_DATE, LocalTime.of(8, 0), null)));
        given(scheduleRepository.findAllById(anySet())).willReturn(List.of(
                schedule(1L, ShiftScheduleStatus.PUBLISHED, null),
                schedule(2L, ShiftScheduleStatus.PUBLISHED, null),
                schedule(3L, ShiftScheduleStatus.PUBLISHED, null)));
        given(teamRepository.findAllById(anySet())).willReturn(List.of());

        List<MyConfirmedSlotResponse> result = shiftMyService.getMyConfirmedSlots(USER_ID);

        assertThat(result).extracting(MyConfirmedSlotResponse::getSlotId)
                .containsExactly(3L, 2L, 1L);
    }

    private void givenAssignments(Long... slotIds) {
        List<ShiftAssignmentEntity> assignments = Arrays.stream(slotIds)
                .map(this::assignment)
                .toList();
        given(assignmentRepository.findAllByUserIdAndStatus(USER_ID, ShiftAssignmentStatus.CONFIRMED))
                .willReturn(assignments);
    }

    private ShiftScheduleEntity schedule(
            Long id, ShiftScheduleStatus status, LocalDateTime publishedAt) {
        ShiftScheduleEntity schedule = ShiftScheduleEntity.builder()
                .teamId(TEAM_ID)
                .title("schedule-" + id)
                .status(status)
                .publishedAt(publishedAt)
                .build();
        ReflectionTestUtils.setField(schedule, "id", id);
        return schedule;
    }

    private ShiftPositionEntity position(Long id, String name) {
        ShiftPositionEntity position = ShiftPositionEntity.builder()
                .teamId(TEAM_ID)
                .name(name)
                .build();
        ReflectionTestUtils.setField(position, "id", id);
        return position;
    }

    private ShiftSlotEntity slot(Long id, LocalDate date, LocalTime startTime, Long positionId) {
        ShiftSlotEntity slot = ShiftSlotEntity.builder()
                .scheduleId(id)
                .slotDate(date)
                .startTime(startTime)
                .endTime(startTime.plusHours(1))
                .positionId(positionId)
                .build();
        ReflectionTestUtils.setField(slot, "id", id);
        return slot;
    }

    private ShiftAssignmentEntity assignment(Long slotId) {
        return ShiftAssignmentEntity.builder()
                .slotId(slotId)
                .userId(USER_ID)
                .assignedBy(1L)
                .status(ShiftAssignmentStatus.CONFIRMED)
                .build();
    }
}
