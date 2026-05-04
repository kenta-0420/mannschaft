package com.mannschaft.app.shiftbudget.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.shiftbudget.dto.TodoBudgetLinkCreateRequest;
import com.mannschaft.app.shiftbudget.dto.TodoBudgetLinkResponse;
import com.mannschaft.app.shiftbudget.service.TodoBudgetLinkService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * F08.7 TODO/プロジェクト 予算紐付 コントローラー（Phase 9-γ / API #7-#8）。
 *
 * <p>API 一覧:</p>
 * <ul>
 *   <li>{@code POST   /api/v1/todo-budget/links} — 紐付作成</li>
 *   <li>{@code DELETE /api/v1/todo-budget/links/{id}} — 紐付削除（物理）</li>
 * </ul>
 *
 * <p>共通: {@code X-Organization-Id} ヘッダで組織スコープを強制（多テナント分離）。</p>
 *
 * <p>権限（設計書 §6.1）:</p>
 * <ul>
 *   <li>{@code MANAGE_TODO}（= 対象 project/todo のスコープに対する ADMIN_OR_ABOVE）</li>
 *   <li>{@code BUDGET_VIEW}（allocation 組織スコープ）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/todo-budget/links")
@Tag(name = "TODO 予算紐付 (F08.7)",
     description = "Phase 9-γ: TODO/プロジェクトと予算割当の紐付 API")
@RequiredArgsConstructor
public class TodoBudgetLinkController {

    private final TodoBudgetLinkService linkService;

    @PostMapping
    @Operation(summary = "TODO/プロジェクトと予算割当を紐付ける",
               description = "project_id と todo_id はどちらか一方のみ指定。"
                       + "link_amount と link_percentage はどちらか一方（両方 NULL = 割当全額）。")
    public ResponseEntity<ApiResponse<TodoBudgetLinkResponse>> createLink(
            @RequestHeader("X-Organization-Id") Long organizationId,
            @Valid @RequestBody TodoBudgetLinkCreateRequest request) {
        TodoBudgetLinkResponse response = linkService.createLink(organizationId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "TODO/プロジェクトの予算紐付を削除する（物理 DELETE）")
    public ResponseEntity<Void> deleteLink(
            @RequestHeader("X-Organization-Id") Long organizationId,
            @PathVariable("id") Long id) {
        linkService.deleteLink(organizationId, id);
        return ResponseEntity.noContent().build();
    }
}
