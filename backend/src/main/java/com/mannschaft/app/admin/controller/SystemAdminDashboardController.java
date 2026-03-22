package com.mannschaft.app.admin.controller;

import com.mannschaft.app.admin.dto.SystemAdminDashboardResponse;
import com.mannschaft.app.admin.service.SystemAdminDashboardService;
import com.mannschaft.app.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * システム管理者ダッシュボードコントローラー。
 */
@RestController
@RequestMapping("/api/v1/system-admin/dashboard")
@Tag(name = "システム管理 - ダッシュボード", description = "F10.1 システム管理者ダッシュボードAPI")
@RequiredArgsConstructor
public class SystemAdminDashboardController {

    private final SystemAdminDashboardService dashboardService;

    /**
     * システム管理者ダッシュボード情報を取得する。
     */
    @GetMapping
    @Operation(summary = "システム管理者ダッシュボード取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<SystemAdminDashboardResponse>> getDashboard() {
        SystemAdminDashboardResponse response = dashboardService.getDashboard();
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 全組織一覧を取得する。
     */
    @GetMapping("/organizations")
    @Operation(summary = "全組織一覧取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<String>> getOrganizations() {
        // TODO: 組織機能のリポジトリ実装後に連携
        return ResponseEntity.ok(ApiResponse.of("TODO: 組織一覧を返す"));
    }

    /**
     * 全チーム一覧を取得する。
     */
    @GetMapping("/teams")
    @Operation(summary = "全チーム一覧取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<String>> getTeams() {
        // TODO: チーム機能のリポジトリ実装後に連携
        return ResponseEntity.ok(ApiResponse.of("TODO: チーム一覧を返す"));
    }

    /**
     * 全ユーザー一覧を取得する。
     */
    @GetMapping("/users")
    @Operation(summary = "全ユーザー一覧取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<String>> getUsers() {
        // TODO: ユーザー機能のリポジトリ実装後に連携
        return ResponseEntity.ok(ApiResponse.of("TODO: ユーザー一覧を返す"));
    }

    /**
     * 組織を凍結する。
     */
    @PatchMapping("/organizations/{organizationId}/freeze")
    @Operation(summary = "組織凍結")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "凍結成功")
    public ResponseEntity<ApiResponse<String>> freezeOrganization(@PathVariable Long organizationId) {
        // TODO: 組織機能のサービス実装後に連携
        return ResponseEntity.ok(ApiResponse.of("TODO: 組織凍結を実行"));
    }

    /**
     * 組織の凍結を解除する。
     */
    @PatchMapping("/organizations/{organizationId}/unfreeze")
    @Operation(summary = "組織凍結解除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "解除成功")
    public ResponseEntity<ApiResponse<String>> unfreezeOrganization(@PathVariable Long organizationId) {
        // TODO: 組織機能のサービス実装後に連携
        return ResponseEntity.ok(ApiResponse.of("TODO: 組織凍結解除を実行"));
    }
}
