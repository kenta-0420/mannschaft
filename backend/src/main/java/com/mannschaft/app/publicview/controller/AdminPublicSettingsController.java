package com.mannschaft.app.publicview.controller;

import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.publicview.dto.UpdatePublicSettingsRequest;
import com.mannschaft.app.publicview.service.AdminPublicSettingsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * F19.1 Phase 7: チーム / 組織の公開設定（タイムライン投稿 / イベント）PATCH API。
 *
 * <p>ADMIN または SYSTEM_ADMIN のみ操作可能。</p>
 *
 * <p>設計書: docs/features/F19.1_public_pages_identity_disclosure.md §6.8 Phase 7</p>
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "公開設定管理 (F19.1 Phase 7)", description = "チーム / 組織のタイムライン / イベント公開設定の管理")
public class AdminPublicSettingsController {

    private final AdminPublicSettingsService adminPublicSettingsService;

    /**
     * チームの公開設定（タイムライン投稿 / イベント）を更新する。
     *
     * <p>ADMIN または SYSTEM_ADMIN のみ操作可能。</p>
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
     * <p>ADMIN または SYSTEM_ADMIN のみ操作可能。</p>
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
