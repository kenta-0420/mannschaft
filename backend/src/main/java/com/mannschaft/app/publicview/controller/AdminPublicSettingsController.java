package com.mannschaft.app.publicview.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.featuregate.AlwaysReachable;
import com.mannschaft.app.common.featuregate.AlwaysReachableCategory;
import com.mannschaft.app.publicview.dto.PublicSettingsResponse;
import com.mannschaft.app.publicview.dto.UpdatePublicSettingsRequest;
import com.mannschaft.app.publicview.service.AdminPublicSettingsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * F19.1 Phase 7: チーム / 組織の公開設定（タイムライン投稿 / イベント）取得・更新 API。
 *
 * <p>ADMIN・DEPUTY または SYSTEM_ADMIN が操作可能。</p>
 *
 * <p>設計書: docs/features/F19.1_public_pages_identity_disclosure.md §6.8 Phase 7</p>
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "公開設定管理 (F19.1 Phase 7)", description = "チーム / 組織のタイムライン / イベント公開設定の管理")
public class AdminPublicSettingsController {

    private final AdminPublicSettingsService adminPublicSettingsService;

    /** 非公開チームも、正準slug解決と既存スコープ管理認可の後に設定を取得する。 */
    @GetMapping("/api/v1/admin/teams/{teamId}/public-settings")
    @AlwaysReachable(category = AlwaysReachableCategory.CORE,
            reason = "管理者が公開機能OFF・非公開状態でも自身のスコープの現在の公開設定を確認する基本設定入口")
    @PreAuthorize("@accessGuard.isScopeAdmin(authentication, @teamService.resolveTeamId(#slug), 'TEAM')")
    @Operation(summary = "チーム公開設定取得", description = "GETのパス識別子はslug。管理認可後に数値IDと現在の2設定を返す。")
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "管理認可済みの設定"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "未認証"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "スコープ管理権限なし"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "対象slugなし・削除済み・ACTIVE対象外")
    })
    public ApiResponse<PublicSettingsResponse> getTeamPublicSettings(@PathVariable("teamId") String slug) {
        return ApiResponse.of(adminPublicSettingsService.getTeamPublicSettings(slug, SecurityUtils.getCurrentUserId()));
    }

    /** 非公開組織も、正準slug解決と既存スコープ管理認可の後に設定を取得する。 */
    @GetMapping("/api/v1/admin/organizations/{organizationId}/public-settings")
    @AlwaysReachable(category = AlwaysReachableCategory.CORE,
            reason = "管理者が公開機能OFF・非公開状態でも自身のスコープの現在の公開設定を確認する基本設定入口")
    @PreAuthorize("@accessGuard.isScopeAdmin(authentication, @organizationService.resolveOrgId(#slug), 'ORGANIZATION')")
    @Operation(summary = "組織公開設定取得", description = "GETのパス識別子はslug。管理認可後に数値IDと現在の2設定を返す。")
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "管理認可済みの設定"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "未認証"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "スコープ管理権限なし"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "対象slugなし・削除済み・ACTIVE対象外")
    })
    public ApiResponse<PublicSettingsResponse> getOrganizationPublicSettings(@PathVariable("organizationId") String slug) {
        return ApiResponse.of(adminPublicSettingsService.getOrganizationPublicSettings(slug, SecurityUtils.getCurrentUserId()));
    }

    /**
     * チームの公開設定（タイムライン投稿 / イベント）を更新する。
     *
     * <p>ADMIN・DEPUTY または SYSTEM_ADMIN が操作可能。</p>
     */
    @PatchMapping("/api/v1/admin/teams/{teamId}/public-settings")
    @PreAuthorize("@accessGuard.isScopeAdmin(authentication, #teamId, 'TEAM')")
    @Operation(
            summary = "チーム公開設定更新",
            description = "ADMIN または SYSTEM_ADMIN が teams の timeline_posts_public / public_events_enabled を変更する（F19.1 Phase 7）。")
    public ResponseEntity<Void> patchTeamPublicSettings(
            @PathVariable Long teamId,
            @Valid @RequestBody UpdatePublicSettingsRequest req) {
        Long operatorId = SecurityUtils.getCurrentUserId();
        adminPublicSettingsService.updateTeamPublicSettings(teamId, operatorId, req);
        return ResponseEntity.noContent().build();
    }

    /**
     * 組織の公開設定（タイムライン投稿 / イベント）を更新する。
     *
     * <p>ADMIN・DEPUTY または SYSTEM_ADMIN が操作可能。</p>
     */
    @PatchMapping("/api/v1/admin/organizations/{organizationId}/public-settings")
    @PreAuthorize("@accessGuard.isScopeAdmin(authentication, #organizationId, 'ORGANIZATION')")
    @Operation(
            summary = "組織公開設定更新",
            description = "ADMIN または SYSTEM_ADMIN が organizations の timeline_posts_public / public_events_enabled を変更する（F19.1 Phase 7）。")
    public ResponseEntity<Void> patchOrganizationPublicSettings(
            @PathVariable Long organizationId,
            @Valid @RequestBody UpdatePublicSettingsRequest req) {
        Long operatorId = SecurityUtils.getCurrentUserId();
        adminPublicSettingsService.updateOrganizationPublicSettings(organizationId, operatorId, req);
        return ResponseEntity.noContent().build();
    }
}
