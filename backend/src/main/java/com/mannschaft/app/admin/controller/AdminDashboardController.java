package com.mannschaft.app.admin.controller;

import com.mannschaft.app.admin.dto.AdminDashboardResponse;
import com.mannschaft.app.admin.service.AdminDashboardService;
import com.mannschaft.app.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.mannschaft.app.common.SecurityUtils;

/**
 * 管理者ダッシュボードコントローラー（チーム/組織管理者向け）。
 */
@RestController
@RequestMapping("/api/v1/admin/dashboard")
@Tag(name = "管理 - ダッシュボード", description = "F10.1 管理者ダッシュボードAPI")
@RequiredArgsConstructor
public class AdminDashboardController {

    private final AdminDashboardService dashboardService;


    /**
     * ダッシュボード情報を取得する。
     */
    @GetMapping
    @Operation(summary = "管理者ダッシュボード取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<AdminDashboardResponse>> getDashboard(
            @RequestParam String scopeType,
            @RequestParam Long scopeId) {
        AdminDashboardResponse response = dashboardService.getDashboard(scopeType, scopeId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * スコープ内のユーザー一覧を取得する。
     */
    @GetMapping("/users")
    @Operation(summary = "スコープ内ユーザー一覧取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<String>> getUsers(
            @RequestParam String scopeType,
            @RequestParam Long scopeId) {
        // TODO: ユーザー機能のリポジトリ実装後に連携
        return ResponseEntity.ok(ApiResponse.of("TODO: ユーザー一覧を返す"));
    }

    /**
     * ユーザーのロールを変更する。
     */
    @PatchMapping("/users/{userId}/role")
    @Operation(summary = "ユーザーロール変更")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "変更成功")
    public ResponseEntity<ApiResponse<String>> updateUserRole(
            @PathVariable Long userId,
            @RequestParam String role) {
        // TODO: ユーザー機能のサービス実装後に連携
        return ResponseEntity.ok(ApiResponse.of("TODO: ロール変更を実行"));
    }
}
