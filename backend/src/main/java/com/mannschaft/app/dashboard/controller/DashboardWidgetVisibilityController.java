package com.mannschaft.app.dashboard.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.dashboard.ScopeType;
import com.mannschaft.app.dashboard.dto.UpdateWidgetVisibilityRequest;
import com.mannschaft.app.dashboard.dto.WidgetVisibilityResponse;
import com.mannschaft.app.dashboard.service.DashboardWidgetVisibilityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * F02.2.1 ダッシュボードウィジェット ロール別可視性 コントローラー。
 * <p>
 * チーム／組織それぞれのウィジェット可視性設定（最低必要ロール）の取得と一括更新を提供する。
 * 全エンドポイントで認証必須。GET は MEMBER 以上、PUT は ADMIN 無条件 / DEPUTY_ADMIN は
 * {@code DASHBOARD_WIDGET_VISIBILITY_MANAGE} パーミッション保有時のみ可（Service 層で検証）。
 * </p>
 *
 * @see DashboardWidgetVisibilityService
 */
@RestController
@RequestMapping("/api/v1/dashboard")
@Tag(name = "ダッシュボードウィジェット可視性", description = "F02.2.1 ロール別ウィジェット可視性管理")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class DashboardWidgetVisibilityController {

    private final DashboardWidgetVisibilityService visibilityService;

    // ========================================
    // チーム
    // ========================================

    /**
     * チームのウィジェット可視性設定一覧を取得する。
     */
    @GetMapping("/team/{teamId}/widget-visibility")
    @Operation(summary = "チームウィジェット可視性設定一覧",
            description = "指定チームの全ウィジェットの最低必要ロール一覧を取得する。MEMBER 以上のみアクセス可。")
    public ResponseEntity<ApiResponse<WidgetVisibilityResponse>> getTeamWidgetVisibility(
            @PathVariable Long teamId) {
        Long userId = SecurityUtils.getCurrentUserId();
        WidgetVisibilityResponse response = visibilityService.getSettings(userId, ScopeType.TEAM, teamId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * チームのウィジェット可視性設定を一括更新する。
     */
    @PutMapping("/team/{teamId}/widget-visibility")
    @Operation(summary = "チームウィジェット可視性設定更新",
            description = "指定チームのウィジェット最低必要ロールを一括更新する。"
                    + "ADMIN は無条件、DEPUTY_ADMIN は DASHBOARD_WIDGET_VISIBILITY_MANAGE 権限保有時のみ可。")
    public ResponseEntity<ApiResponse<WidgetVisibilityResponse>> updateTeamWidgetVisibility(
            @PathVariable Long teamId,
            @Valid @RequestBody UpdateWidgetVisibilityRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        WidgetVisibilityResponse response = visibilityService.updateSettings(
                userId, ScopeType.TEAM, teamId, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    // ========================================
    // 組織
    // ========================================

    /**
     * 組織のウィジェット可視性設定一覧を取得する。
     */
    @GetMapping("/organization/{orgId}/widget-visibility")
    @Operation(summary = "組織ウィジェット可視性設定一覧",
            description = "指定組織の全ウィジェットの最低必要ロール一覧を取得する。MEMBER 以上のみアクセス可。")
    public ResponseEntity<ApiResponse<WidgetVisibilityResponse>> getOrgWidgetVisibility(
            @PathVariable Long orgId) {
        Long userId = SecurityUtils.getCurrentUserId();
        WidgetVisibilityResponse response = visibilityService.getSettings(userId, ScopeType.ORGANIZATION, orgId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 組織のウィジェット可視性設定を一括更新する。
     */
    @PutMapping("/organization/{orgId}/widget-visibility")
    @Operation(summary = "組織ウィジェット可視性設定更新",
            description = "指定組織のウィジェット最低必要ロールを一括更新する。"
                    + "ADMIN は無条件、DEPUTY_ADMIN は DASHBOARD_WIDGET_VISIBILITY_MANAGE 権限保有時のみ可。")
    public ResponseEntity<ApiResponse<WidgetVisibilityResponse>> updateOrgWidgetVisibility(
            @PathVariable Long orgId,
            @Valid @RequestBody UpdateWidgetVisibilityRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        WidgetVisibilityResponse response = visibilityService.updateSettings(
                userId, ScopeType.ORGANIZATION, orgId, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
