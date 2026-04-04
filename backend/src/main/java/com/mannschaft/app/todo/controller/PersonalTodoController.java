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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
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
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(todoService.createTodo(TodoScopeType.PERSONAL, userId, request, userId));
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
}
