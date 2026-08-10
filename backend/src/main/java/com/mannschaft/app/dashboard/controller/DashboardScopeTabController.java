package com.mannschaft.app.dashboard.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.dashboard.dto.ScopeTabOrderUpdateRequest;
import com.mannschaft.app.dashboard.dto.ScopeTabPageResponse;
import com.mannschaft.app.dashboard.service.DashboardScopeTabService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * F22.1: 横スワイプ・ダッシュボードのチーム/組織タグ（scope-tabs）コントローラー。
 *
 * <p>タグ一覧取得（GET）と表示順更新（PUT）の 2 本のみを提供する。
 * チーム/組織パネルのウィジェット拡張・統合「要対応」集計は別フェーズ（Wave 2）の担当。</p>
 *
 * <p>認可: クラス全体に {@code @PreAuthorize("isAuthenticated()")}（コントローラ二重防御・02 §2.2）。
 * SecurityConfig の {@code .anyRequest().authenticated()} フォールバックも
 * {@code /api/v1/dashboard/**} をカバーしており、さらにサービス層が
 * {@link com.mannschaft.app.common.SecurityUtils#getCurrentUserId()} で
 * 未認証を 401（COMMON_000）に弾くため、認証は三重に担保される。</p>
 *
 * <p>設計書: docs/features/F22.1_swipe_scope_dashboard/02_api_design.md §3.1 / §3.2</p>
 */
@RestController
@RequestMapping("/api/v1/dashboard/scope-tabs")
@Tag(name = "ダッシュボード横スワイプ")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
public class DashboardScopeTabController {

    private final DashboardScopeTabService scopeTabService;

    /**
     * 表示順適用済みの所属スコープ（タグ）一覧を 6 件/ページで返す。
     */
    @GetMapping
    @Operation(summary = "所属タグ一覧取得",
            description = "ログインユーザーが所属するチーム/組織を表示順適用済みで 6 件/ページ返す")
    public ResponseEntity<ApiResponse<ScopeTabPageResponse>> getScopeTabs(
            @Parameter(description = "スコープ種別（TEAM / ORGANIZATION）", required = true)
            @RequestParam String scopeType,
            @Parameter(description = "0 始まりのページ番号（1 ページ = 6 件）")
            @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "F15.3 フォルダ ID（指定時は自分所有フォルダのみ・当該フォルダの scope に絞り込み）")
            @RequestParam(required = false) Long folderId) {
        ScopeTabPageResponse response = scopeTabService.getScopeTabs(scopeType, page, folderId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * タグの表示順を一括更新（UPSERT）する。
     */
    @PutMapping("/order")
    @Operation(summary = "タグ表示順の一括更新",
            description = "ドラッグ並べ替え確定時に呼ぶ。自分の所属スコープのみ。非所属混入時は全体 403")
    public ResponseEntity<Void> updateOrder(
            @Valid @RequestBody ScopeTabOrderUpdateRequest request) {
        scopeTabService.updateOrder(request);
        return ResponseEntity.noContent().build();
    }
}
