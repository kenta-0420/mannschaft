package com.mannschaft.app.shift.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.shift.entity.ShiftSlotEntity;
import com.mannschaft.app.shift.repository.ShiftSlotRepository;
import com.mannschaft.app.todo.TodoPriority;
import com.mannschaft.app.todo.TodoScopeType;
import com.mannschaft.app.todo.TodoStatus;
import com.mannschaft.app.todo.entity.TodoEntity;
import com.mannschaft.app.todo.repository.TodoRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/**
 * {@link ShiftToTaskService} のユニットテスト。F03.5 Phase 4-β。
 */
@ExtendWith(MockitoExtension.class)
class ShiftToTaskServiceTest {

    @Mock private ShiftSlotRepository slotRepository;
    @Mock private TodoRepository todoRepository;
    @Spy  private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private ShiftToTaskService shiftToTaskService;

    @Nested
    @DisplayName("createTodosForSchedule")
    class CreateTodosForSchedule {

        @Test
        @DisplayName("割り当てユーザーありスロット → Todo が save される")
        void 割り当てあり_Todo作成される() {
            ShiftSlotEntity slot = buildSlot(1L, "[10, 20]");
            given(slotRepository.findByScheduleIdOrderBySlotDateAscStartTimeAsc(100L))
                    .willReturn(List.of(slot));

            shiftToTaskService.createTodosForSchedule(100L, 1L, 999L);

            verify(todoRepository, times(2)).save(any(TodoEntity.class));
        }

        @Test
        @DisplayName("assignedUserIds が空のスロット → Todo が作成されない")
        void 割り当てなし_スキップされる() {
            ShiftSlotEntity slot = buildSlot(1L, "[]");
            given(slotRepository.findByScheduleIdOrderBySlotDateAscStartTimeAsc(100L))
                    .willReturn(List.of(slot));

            shiftToTaskService.createTodosForSchedule(100L, 1L, 999L);

            verify(todoRepository, never()).save(any());
        }

        @Test
        @DisplayName("スロットが 0 件 → save が呼ばれない")
        void スロットなし_saveAll不要() {
            given(slotRepository.findByScheduleIdOrderBySlotDateAscStartTimeAsc(100L))
                    .willReturn(List.of());

            shiftToTaskService.createTodosForSchedule(100L, 1L, 999L);

            verify(todoRepository, never()).save(any());
        }

        @Test
        @DisplayName("DataIntegrityViolationException → スキップして処理継続")
        void 重複例外時_スキップ() {
            ShiftSlotEntity slot = buildSlot(1L, "[10]");
            given(slotRepository.findByScheduleIdOrderBySlotDateAscStartTimeAsc(100L))
                    .willReturn(List.of(slot));
            given(todoRepository.save(any())).willThrow(new DataIntegrityViolationException("duplicate"));

            assertThatCode(() -> shiftToTaskService.createTodosForSchedule(100L, 1L, 999L))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("作成される Todo の title / dueDate / scopeType / linkedShiftSlotId を検証")
        void Todo内容が正しい() {
            ShiftSlotEntity slot = buildSlot(1L, "[10]");
            given(slotRepository.findByScheduleIdOrderBySlotDateAscStartTimeAsc(100L))
                    .willReturn(List.of(slot));
            ArgumentCaptor<TodoEntity> captor = ArgumentCaptor.forClass(TodoEntity.class);
            given(todoRepository.save(captor.capture())).willReturn(null);

            shiftToTaskService.createTodosForSchedule(100L, 1L, 999L);

            TodoEntity created = captor.getValue();
            assertThat(created.getScopeType()).isEqualTo(TodoScopeType.PERSONAL);
            assertThat(created.getScopeId()).isEqualTo(10L);
            assertThat(created.getTitle()).contains("シフト勤務:");
            assertThat(created.getDueDate()).isEqualTo(LocalDate.of(2026, 6, 1));
            assertThat(created.getDueTime()).isEqualTo(LocalTime.of(9, 0));
            assertThat(created.getPriority()).isEqualTo(TodoPriority.MEDIUM);
            assertThat(created.getStatus()).isEqualTo(TodoStatus.OPEN);
            assertThat(created.getLinkedScheduleId()).isEqualTo(100L);
            assertThat(created.getLinkedShiftSlotId()).isEqualTo(1L);
            assertThat(created.getCreatedBy()).isEqualTo(999L);
        }

        @Test
        @DisplayName("assignedUserIds が null のスロット → スキップされる")
        void assignedUserIdsがnull_スキップ() {
            ShiftSlotEntity slot = buildSlot(1L, null);
            given(slotRepository.findByScheduleIdOrderBySlotDateAscStartTimeAsc(100L))
                    .willReturn(List.of(slot));

            shiftToTaskService.createTodosForSchedule(100L, 1L, 999L);

            verify(todoRepository, never()).save(any());
        }
    }

    private ShiftSlotEntity buildSlot(Long id, String assignedUserIds) {
        ShiftSlotEntity slot = ShiftSlotEntity.builder()
                .scheduleId(100L)
                .slotDate(LocalDate.of(2026, 6, 1))
                .startTime(LocalTime.of(9, 0))
                .endTime(LocalTime.of(18, 0))
                .assignedUserIds(assignedUserIds)
                .build();
        ReflectionTestUtils.setField(slot, "id", id);
        return slot;
    }
}
