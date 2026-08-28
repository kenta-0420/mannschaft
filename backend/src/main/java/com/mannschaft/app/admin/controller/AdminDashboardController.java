package com.mannschaft.app.admin.controller;

import com.mannschaft.app.admin.dto.AdminDashboardResponse;
import com.mannschaft.app.admin.service.AdminDashboardService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.role.dto.RoleChangeRequest;
import com.mannschaft.app.role.dto.ScopeUserRoleResponse;
import com.mannschaft.app.role.service.RoleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理者ダッシュボードコントローラー（チーム/組織管理者向け）。
 */
@RestController
@RequestMapping("/api/v1/admin/dashboard")
@Tag(name = "管理 - ダッシュボード", description = "F10.1 管理者ダッシュボードAPI")
@RequiredArgsConstructor
public class AdminDashboardController {

    private final AdminDashboardService dashboardService;
    private final RoleService roleService;
    private final AccessControlService accessControlService;

    /**
     * ダッシュボード情報を取得する。
     */
    @GetMapping
    @Operation(summary = "管理者ダッシュボード取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<AdminDashboardResponse>> getDashboard(
            @RequestParam String scopeType,
            @RequestParam Long scopeId) {
        // 認可根治 Wave5: 同クラスの updateUserRole と同水準の scope 認可を敷く
        // （別スコープ ADMIN が scopeType/scopeId を差し替えて越境集計するのを遮断）。
        // 追込: 認可の前に scopeType を検証し、不正値による ScopeType.valueOf の
        // IllegalArgumentException（未処理 500）を 400 へ正規化する。
        AdminScopeTypeValidator.requireSupportedScopeType(scopeType);
        accessControlService.checkAdminOrAbove(SecurityUtils.getCurrentUserId(), scopeId, scopeType);
        AdminDashboardResponse response = dashboardService.getDashboard(scopeType, scopeId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * スコープ内のユーザー一覧を取得する。
     */
    @GetMapping("/users")
    @Operation(summary = "スコープ内ユーザー一覧取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<Page<ScopeUserRoleResponse>>> getUsers(
            @RequestParam String scopeType,
            @RequestParam Long scopeId,
            Pageable pageable) {
        // 認可根治 Wave5: 同上。スコープ内の全ロール割当が誰にでも見えていた状態を根治する。
        // 追込: 認可の前に scopeType を検証（不正値の未処理 500 → 400）。
        AdminScopeTypeValidator.requireSupportedScopeType(scopeType);
        accessControlService.checkAdminOrAbove(SecurityUtils.getCurrentUserId(), scopeId, scopeType);
        // Repository 直叩き＋Entity 生返却をやめ、role ドメインの Service 経由で DTO を取得する。
        Page<ScopeUserRoleResponse> page = roleService.getScopeUsers(scopeId, scopeType, pageable);
        return ResponseEntity.ok(ApiResponse.of(page));
    }

    /**
     * ユーザーのロールを変更する。
     */
    @PatchMapping("/users/{userId}/role")
    @Operation(summary = "ユーザーロール変更")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "変更成功")
    public ResponseEntity<Void> updateUserRole(
            @PathVariable Long userId,
            @RequestParam String scopeType,
            @RequestParam Long scopeId,
            @RequestParam Long roleId) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        // 束1 権限昇格根治（入口二重防御）: 対象スコープの ADMIN/DEPUTY_ADMIN のみロール変更可
        // （別スコープ ADMIN が scopeType/scopeId を差し替えて越境するのを遮断）。
        // 追込: 認可の前に scopeType を検証（不正値の未処理 500 → 400）。
        AdminScopeTypeValidator.requireSupportedScopeType(scopeType);
        accessControlService.checkAdminOrAbove(currentUserId, scopeId, scopeType);
        roleService.changeRole(scopeId, scopeType, userId, new RoleChangeRequest(roleId), currentUserId);
        return ResponseEntity.ok().build();
    }
}
