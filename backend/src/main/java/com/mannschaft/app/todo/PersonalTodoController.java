package com.mannschaft.app.todo;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.todo.dto.TodoResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 個人TODOコントローラー。全スコープ横断の自分のTODO一覧を提供する。
 */
@RestController
@RequestMapping("/api/v1/todos")
@Tag(name = "TODO（個人）", description = "F02.3 個人TODO管理")
@RequiredArgsConstructor
public class PersonalTodoController {

    private final TodoService todoService;

    // TODO: JwtAuthenticationFilter実装時にSecurityContextHolderから取得に変更
    private Long getCurrentUserId() {
        return 1L;
    }

    /**
     * 自分に割り当てられた全TODOを取得する（全スコープ横断）。
     */
    @GetMapping("/my")
    @Operation(summary = "自分のTODO一覧（全スコープ横断）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<TodoResponse>>> getMyTodos() {
        return ResponseEntity.ok(todoService.getMyTodos(getCurrentUserId()));
    }
}
