package com.mannschaft.app.todo.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.todo.TodoStatusLabelScope;
import com.mannschaft.app.todo.dto.CreateTodoStatusLabelRequest;
import com.mannschaft.app.todo.dto.TodoStatusLabelResponse;
import com.mannschaft.app.todo.dto.UpdateTodoStatusLabelRequest;
import com.mannschaft.app.todo.service.TodoStatusLabelService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 個人スコープ TODO ステータスラベル管理 API（F02.3.1 Phase 1a）。
 *
 * <p><b>認可</b>: スコープ ID は常に {@code SecurityUtils.getCurrentUserId()} で確定した
 * 認証主体の ID を渡し、リクエストからは指定できない（自己スコープ）。加えて
 * {@code TodoStatusLabelService} が「ラベル本体のスコープ == 操作ユーザー」を照合し、
 * 他ユーザーのラベル ID を指定した更新・削除は 404 で存在を秘匿する（BOLA/IDOR 対策）。
 * 契約は {@code TodoStatusLabelScopeContractIT} で固定する。</p>
 */
@RestController
@RequestMapping("/api/v1/users/me/todo-status-labels")
@Tag(name = "TODO ステータスラベル（個人）", description = "F02.3.1 個人スコープのカスタムステータスラベル CRUD")
@RequiredArgsConstructor
public class UserTodoStatusLabelController {

    private final TodoStatusLabelService labelService;

    @GetMapping
    @Operation(summary = "個人ステータスラベル一覧（SYSTEM 既定 + 個人スコープ）")
    public ResponseEntity<ApiResponse<List<TodoStatusLabelResponse>>> list() {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(
                labelService.list(TodoStatusLabelScope.PERSONAL, userId, userId)));
    }

    @PostMapping
    @Operation(summary = "個人ステータスラベル作成")
    public ResponseEntity<ApiResponse<TodoStatusLabelResponse>> create(
            @Valid @RequestBody CreateTodoStatusLabelRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        TodoStatusLabelResponse response = labelService.create(
                TodoStatusLabelScope.PERSONAL, userId, request, userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    @PutMapping("/{labelId}")
    @Operation(summary = "個人ステータスラベル更新")
    public ResponseEntity<ApiResponse<TodoStatusLabelResponse>> update(
            @PathVariable Long labelId,
            @Valid @RequestBody UpdateTodoStatusLabelRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(
                labelService.update(labelId, TodoStatusLabelScope.PERSONAL, userId, request, userId)));
    }

    @DeleteMapping("/{labelId}")
    @Operation(summary = "個人ステータスラベル削除")
    public ResponseEntity<Void> delete(@PathVariable Long labelId) {
        Long userId = SecurityUtils.getCurrentUserId();
        labelService.delete(labelId, TodoStatusLabelScope.PERSONAL, userId, userId);
        return ResponseEntity.noContent().build();
    }
}
