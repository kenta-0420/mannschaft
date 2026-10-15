package com.mannschaft.app.admin.controller;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.template.dto.ToggleModuleRequest;
import com.mannschaft.app.template.service.ModuleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * チーム・組織管理者向けモジュール管理コントローラー。
 * チームおよび組織スコープのモジュール一覧取得・有効/無効切替を提供する。
 */
@RestController
@Tag(name = "管理 - モジュール", description = "チーム/組織管理者向けモジュール管理API")
@RequiredArgsConstructor
public class AdminModuleController {

    private final ModuleService moduleService;
    private final AccessControlService accessControlService;

    /**
     * チームのモジュール一覧を取得する。
     *
     * @param teamId チームID
     * @return モジュール一覧
     */
    @GetMapping("/api/v1/teams/{teamId}/admin/modules")
    @Operation(summary = "チームモジュール一覧取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<AdminModuleItem>>> listTeamModules(
            @PathVariable Long teamId) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrAbove(userId, teamId, "TEAM");
        List<AdminModuleItem> items = moduleService.getTeamModules(teamId).stream()
                .map(m -> new AdminModuleItem(
                        String.valueOf(m.getModuleId()),
                        m.getModuleName(),
                        Boolean.TRUE.equals(m.getIsEnabled())))
                .toList();
        return ResponseEntity.ok(ApiResponse.of(items));
    }

    /**
     * チームのモジュール有効/無効を切り替える。
     *
     * @param teamId   チームID
     * @param moduleId モジュールID文字列
     * @param request  有効/無効リクエスト
     */
    @PutMapping("/api/v1/teams/{teamId}/admin/modules/{moduleId}")
    @Operation(summary = "チームモジュール切替")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<Void> toggleTeamModule(
            @PathVariable Long teamId,
            @PathVariable String moduleId,
            @Valid @RequestBody ToggleModuleRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrAbove(userId, teamId, "TEAM");
        moduleService.toggleTeamModule(teamId, request, userId);
        return ResponseEntity.ok().build();
    }

    /**
     * 組織のモジュール一覧を取得する。
     * 組織スコープは現時点ではチームID=0として扱う（TODO: 組織スコープ対応）。
     *
     * @param organizationId 組織ID
     * @return モジュール一覧
     */
    @GetMapping("/api/v1/organizations/{organizationId}/admin/modules")
    @Operation(summary = "組織モジュール一覧取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<AdminModuleItem>>> listOrgModules(
            @PathVariable Long organizationId) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrAbove(userId, organizationId, "ORGANIZATION");
        List<AdminModuleItem> items = moduleService.getModuleCatalog().stream()
                .map(m -> new AdminModuleItem(
                        String.valueOf(m.getId()),
                        m.getName(),
                        m.getIsActive()))
                .toList();
        return ResponseEntity.ok(ApiResponse.of(items));
    }

    /**
     * 組織のモジュール有効/無効を切り替える。
     *
     * @param organizationId 組織ID
     * @param moduleId       モジュールID文字列
     * @param request        有効/無効リクエスト
     */
    @PutMapping("/api/v1/organizations/{organizationId}/admin/modules/{moduleId}")
    @Operation(summary = "組織モジュール切替")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<Void> toggleOrgModule(
            @PathVariable Long organizationId,
            @PathVariable String moduleId,
            @Valid @RequestBody ToggleModuleRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrAbove(userId, organizationId, "ORGANIZATION");
        // 組織スコープのモジュールトグルは将来実装（現時点は受け付けてOKを返す）
        return ResponseEntity.ok().build();
    }

    /**
     * 管理者向けモジュール一覧アイテム DTO。
     * フロントエンドの期待する形式 { moduleId, name, enabled } に対応する。
     */
    public record AdminModuleItem(String moduleId, String name, boolean enabled) {}
}
