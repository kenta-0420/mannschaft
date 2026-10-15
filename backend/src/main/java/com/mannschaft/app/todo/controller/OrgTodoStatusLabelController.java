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
 * 組織スコープ TODO ステータスラベル管理 API（F02.3.1 Phase 1a）。
 *
 * <p><b>認可</b>: 一覧は当該組織の<b>メンバーに限定</b>、CRUD は当該組織の <b>ADMIN に限定</b>する
 * （設計書 §2 の権限マトリクス。DEPUTY_ADMIN は CRUD 不可）。判定の実体は
 * {@code TodoStatusLabelService#validateScopeAccess} が担い、非メンバー／非 ADMIN は 403。
 * 更新・削除では path の組織 ID とラベル本体のスコープの一致を先に照合し、
 * 不一致は 404 で存在を秘匿する（BOLA/IDOR 対策）。</p>
 */
@RestController
@RequestMapping("/api/v1/organizations/{orgId}/todo-status-labels")
@Tag(name = "TODO ステータスラベル（組織）", description = "F02.3.1 組織スコープのカスタムステータスラベル CRUD")
@RequiredArgsConstructor
public class OrgTodoStatusLabelController {

    private final TodoStatusLabelService labelService;

    @GetMapping
    @Operation(summary = "組織ステータスラベル一覧（SYSTEM 既定 + 組織スコープ）")
    public ResponseEntity<ApiResponse<List<TodoStatusLabelResponse>>> list(@PathVariable Long orgId) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(
                labelService.list(TodoStatusLabelScope.ORGANIZATION, orgId, userId)));
    }

    @PostMapping
    @Operation(summary = "組織ステータスラベル作成（ADMIN/DEPUTY_ADMIN）")
    public ResponseEntity<ApiResponse<TodoStatusLabelResponse>> create(
            @PathVariable Long orgId,
            @Valid @RequestBody CreateTodoStatusLabelRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        TodoStatusLabelResponse response = labelService.create(
                TodoStatusLabelScope.ORGANIZATION, orgId, request, userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    @PutMapping("/{labelId}")
    @Operation(summary = "組織ステータスラベル更新（ADMIN のみ）")
    public ResponseEntity<ApiResponse<TodoStatusLabelResponse>> update(
            @PathVariable Long orgId,
            @PathVariable Long labelId,
            @Valid @RequestBody UpdateTodoStatusLabelRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(
                labelService.update(labelId, TodoStatusLabelScope.ORGANIZATION, orgId, request, userId)));
    }

    @DeleteMapping("/{labelId}")
    @Operation(summary = "組織ステータスラベル削除（ADMIN のみ）")
    public ResponseEntity<Void> delete(@PathVariable Long orgId, @PathVariable Long labelId) {
        Long userId = SecurityUtils.getCurrentUserId();
        labelService.delete(labelId, TodoStatusLabelScope.ORGANIZATION, orgId, userId);
        return ResponseEntity.noContent().build();
    }
}
