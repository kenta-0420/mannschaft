package com.mannschaft.app.todo.service;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.todo.TodoErrorCode;
import com.mannschaft.app.todo.dto.AddAssigneeRequest;
import com.mannschaft.app.todo.dto.AssigneeResponse;
import com.mannschaft.app.todo.entity.TodoAssigneeEntity;
import com.mannschaft.app.todo.entity.TodoEntity;
import com.mannschaft.app.todo.repository.TodoAssigneeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * TODO担当者サービス。担当者の追加・削除を担当する。
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TodoAssigneeService {

    private final TodoAssigneeRepository assigneeRepository;
    private final TodoResponseConverter responseConverter;
    private final TodoService todoService;

    /**
     * 担当者を追加する。
     *
     * @param todoId  Todo ID
     * @param request 担当者追加リクエスト
     * @param userId  操作ユーザーID
     * @return 追加された担当者
     */
    @Transactional
    public ApiResponse<AssigneeResponse> addAssignee(Long todoId, AddAssigneeRequest request, Long userId) {
        TodoEntity todo = todoService.findTodoOrThrow(todoId);
        // F02.7: ロック中 TODO の担当者変更は 423 Locked
        todoService.assertNotMilestoneLocked(todo);

        if (assigneeRepository.existsByTodoIdAndUserId(todoId, request.getUserId())) {
            throw new BusinessException(TodoErrorCode.ASSIGNEE_ALREADY_EXISTS);
        }

        TodoAssigneeEntity assignee = TodoAssigneeEntity.builder()
                .todoId(todoId)
                .userId(request.getUserId())
                .assignedBy(userId)
                .build();

        assignee = assigneeRepository.save(assignee);
        return ApiResponse.of(responseConverter.toAssigneeResponse(assignee));
    }

    /**
     * 担当者を削除する。
     *
     * @param todoId       Todo ID
     * @param targetUserId 削除対象のユーザーID
     */
    @Transactional
    public void removeAssignee(Long todoId, Long targetUserId) {
        TodoEntity todo = todoService.findTodoOrThrow(todoId);
        // F02.7: ロック中 TODO の担当者変更は 423 Locked
        todoService.assertNotMilestoneLocked(todo);

        TodoAssigneeEntity assignee = assigneeRepository.findByTodoIdAndUserId(todoId, targetUserId)
                .orElseThrow(() -> new BusinessException(TodoErrorCode.ASSIGNEE_NOT_FOUND));
        assigneeRepository.delete(assignee);
    }
}
