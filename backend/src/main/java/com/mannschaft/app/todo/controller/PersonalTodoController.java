package com.mannschaft.app.todo.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.todo.TodoScopeType;
import com.mannschaft.app.todo.dto.CreateTodoRequest;
import com.mannschaft.app.todo.dto.TodoResponse;
import com.mannschaft.app.todo.service.TodoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.ArrayList;
import com.mannschaft.app.common.SecurityUtils;

/**
 * 個人TODOコントローラー。全スコープ横断の自分のTODO一覧を提供する。
 */
@RestController
@RequestMapping("/api/v1/todos")
@Tag(name = "TODO（個人）", description = "F02.3 個人TODO管理")
@RequiredArgsConstructor
public class PersonalTodoController {

    private final TodoService todoService;


    /**
     * 個人TODOを作成する。
     */
    @PostMapping
    @Operation(summary = "個人TODO作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<TodoResponse>> createPersonalTodo(
            @Valid @RequestBody CreateTodoRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        // 個人TODOは作成者を担当者として自動追加（findMyTodosはassignee経由で取得するため）
        List<Long> assigneeIds = new ArrayList<>();
        if (request.getAssigneeIds() != null) {
            assigneeIds.addAll(request.getAssigneeIds());
        }
        if (!assigneeIds.contains(userId)) {
            assigneeIds.add(userId);
        }
        CreateTodoRequest enriched = new CreateTodoRequest(
                request.getTitle(), request.getDescription(), request.getProjectId(),
                request.getMilestoneId(), request.getPriority(), request.getDueDate(),
                request.getDueTime(), request.getSortOrder(), assigneeIds,
                request.getParentId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(todoService.createTodo(TodoScopeType.PERSONAL, userId, enriched, userId));
    }

    /**
     * 自分に割り当てられた全TODOを取得する（全スコープ横断）。
     */
    @GetMapping("/my")
    @Operation(summary = "自分のTODO一覧（全スコープ横断）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<TodoResponse>>> getMyTodos() {
        return ResponseEntity.ok(todoService.getMyTodos(SecurityUtils.getCurrentUserId()));
    }

    /**
     * 個人TODOの直接の子TODO一覧を取得する。
     */
    @GetMapping("/{id}/children")
    @Operation(summary = "個人TODO子一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<TodoResponse>>> getChildTodos(@PathVariable Long id) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(todoService.getChildTodos(TodoScopeType.PERSONAL, userId, id));
    }
}
