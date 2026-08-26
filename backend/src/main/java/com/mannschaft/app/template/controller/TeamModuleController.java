package com.mannschaft.app.template.controller;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.team.service.TeamService;
import com.mannschaft.app.template.dto.TeamModuleCatalogResponse;
import com.mannschaft.app.template.dto.TeamModuleResponse;
import com.mannschaft.app.template.dto.ToggleModuleRequest;
import com.mannschaft.app.template.service.ModuleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * チームモジュール管理コントローラー。チーム単位のモジュール有効化・テンプレート適用を提供する。
 */
@RestController
@RequestMapping("/api/v1/teams/{slug}/modules")
@Tag(name = "チームモジュール管理")
@RequiredArgsConstructor
public class TeamModuleController {

    private final ModuleService moduleService;
    private final TeamService teamService;
    private final AccessControlService accessControlService;


    /**
     * チームの有効モジュール一覧を取得する。
     * MEMBER以上のユーザーが参照できる（SUPPORTER/GUEST/未加入は403）。
     *
     * @param slug チームスラッグ（URL識別子）
     */
    @GetMapping
    @Operation(summary = "チームモジュール一覧取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<TeamModuleResponse>>> getTeamModules(
            @PathVariable String slug) {
        Long teamId = teamService.resolveTeamId(slug);
        // MEMBER 以上であることを確認（SUPPORTER/GUEST/未加入は 403）
        accessControlService.checkMembership(SecurityUtils.getCurrentUserId(), teamId, "TEAM");
        return ResponseEntity.ok(ApiResponse.of(moduleService.getTeamModules(teamId)));
    }

    /**
     * チーム機能設定タブ向けの「利用可能 OPTIONAL モジュールのカタログ＋有効状態」を取得する。
     * MEMBER 以上のユーザーが参照できる（SUPPORTER/GUEST/未加入は 403）。
     *
     * @param slug チームスラッグ（URL識別子）
     * @return カタログ＋有効状態レスポンス
     */
    @GetMapping("/catalog")
    @Operation(summary = "チーム機能カタログ＋有効状態取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<TeamModuleCatalogResponse>> getTeamModuleCatalog(
            @PathVariable String slug) {
        Long teamId = teamService.resolveTeamId(slug);
        // MEMBER 以上であることを確認（SUPPORTER/GUEST/未加入は 403）
        accessControlService.checkMembership(SecurityUtils.getCurrentUserId(), teamId, "TEAM");
        return ResponseEntity.ok(ApiResponse.of(moduleService.getTeamModuleCatalog(teamId)));
    }

    /**
     * チームのモジュール有効/無効を切り替える。
     * ADMINのみ実行可能（DEPUTY_ADMIN以下は403）。
     *
     * @param slug チームスラッグ（URL識別子）
     */
    @PatchMapping("/{moduleId}/toggle")
    @Operation(summary = "モジュール有効/無効切替")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "切替成功")
    public ResponseEntity<Void> toggleTeamModule(
            @PathVariable String slug,
            @PathVariable Long moduleId,
            @Valid @RequestBody ToggleModuleRequest request) {
        Long teamId = teamService.resolveTeamId(slug);
        Long currentUserId = SecurityUtils.getCurrentUserId();
        // ADMINのみ許可（手本: OrganizationModuleController#toggleOrganizationModule）
        if (!accessControlService.isAdmin(currentUserId, teamId, "TEAM")) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
        moduleService.toggleTeamModule(teamId, request, currentUserId);
        return ResponseEntity.ok().build();
    }

    /**
     * テンプレートの推奨モジュールをチームに自動適用する。
     * ADMINのみ実行可能（DEPUTY_ADMIN以下は403）。
     *
     * @param slug チームスラッグ（URL識別子）
     */
    @PutMapping("/template")
    @Operation(summary = "テンプレート適用")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "適用成功")
    public ResponseEntity<Void> applyTemplate(
            @PathVariable String slug,
            @RequestParam Long templateId) {
        Long teamId = teamService.resolveTeamId(slug);
        Long currentUserId = SecurityUtils.getCurrentUserId();
        // ADMINのみ許可（テンプレート一括適用はチーム全体のモジュール設定を書き換えるため）
        if (!accessControlService.isAdmin(currentUserId, teamId, "TEAM")) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
        moduleService.applyTemplate(teamId, templateId, currentUserId);
        return ResponseEntity.ok().build();
    }
}
