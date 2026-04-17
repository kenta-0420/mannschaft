package com.mannschaft.app.todo.service;

import com.mannschaft.app.todo.TodoScopeType;
import com.mannschaft.app.todo.dto.GanttTodoResponse;
import com.mannschaft.app.todo.entity.TodoEntity;
import com.mannschaft.app.todo.repository.TodoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * TODOガントバーサービス。ガントバー表示用のTODO一覧を提供する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TodoGanttService {

    private final TodoRepository todoRepository;

    /**
     * ガントバー用TODO一覧を取得する。
     *
     * <p>fromDate〜toDateの範囲と期間が交差するTODO（startDate・dueDateの両方が非NULL）を返す。
     * 各TODOのchildIdsは同じ取得結果から構築する。</p>
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param fromDate  検索開始日
     * @param toDate    検索終了日
     * @return ガントバー用TODO一覧
     */
    public List<GanttTodoResponse> getGanttTodos(
            TodoScopeType scopeType, Long scopeId,
            LocalDate fromDate, LocalDate toDate) {

        List<TodoEntity> todos = todoRepository.findGanttTodos(scopeType, scopeId, fromDate, toDate);

        // IDセットを取得してchildIds構築を効率化
        Map<Long, List<Long>> parentToChildIds = todos.stream()
                .filter(t -> t.getParentId() != null)
                .collect(Collectors.groupingBy(
                        TodoEntity::getParentId,
                        Collectors.mapping(TodoEntity::getId, Collectors.toList())
                ));

        return todos.stream()
                .map(todo -> new GanttTodoResponse(
                        todo.getId(),
                        todo.getTitle(),
                        todo.getStartDate(),
                        todo.getDueDate(),
                        todo.getProgressRate(),
                        todo.getProgressManual(),
                        todo.getStatus().name(),
                        todo.getPriority().name(),
                        todo.getParentId(),
                        todo.getDepth(),
                        parentToChildIds.getOrDefault(todo.getId(), List.of())
                ))
                .toList();
    }
}
