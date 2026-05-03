package com.mannschaft.app.shift.service;

import com.mannschaft.app.todo.TodoStatus;
import com.mannschaft.app.todo.entity.TodoEntity;
import com.mannschaft.app.todo.repository.TodoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * シフト ARCHIVED 時 Todo 自動キャンセルサービス。F03.5 Phase 4-γ。
 * ARCHIVED 遷移したスケジュールに紐づく自動作成 Todo（linked_shift_slot_id IS NOT NULL）を
 * OPEN / IN_PROGRESS から CANCELLED へ一括遷移する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class ShiftArchivedTodoCancelService {

    private final TodoRepository todoRepository;

    /**
     * 指定スケジュールに紐づくシフト自動作成 Todo を CANCELLED にする。
     *
     * @param scheduleId アーカイブされたシフトスケジュールID
     * @return キャンセルした件数
     */
    public int cancelShiftLinkedTodos(Long scheduleId) {
        List<TodoEntity> targets = todoRepository
                .findOpenShiftLinkedTodosByScheduleId(scheduleId);
        if (targets.isEmpty()) {
            log.info("シフトARCHIVED時Todoキャンセル対象なし: scheduleId={}", scheduleId);
            return 0;
        }
        targets.forEach(todo -> todo.changeStatus(TodoStatus.CANCELLED, null));
        todoRepository.saveAll(targets);
        log.info("シフトARCHIVED時Todo自動キャンセル: scheduleId={}, 件数={}", scheduleId, targets.size());
        return targets.size();
    }
}
