package com.mannschaft.app.translation.controller;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.translation.service.ContentTranslationService;
import com.mannschaft.app.translation.service.ContentTranslationService.ChangeStatusRequest;
import com.mannschaft.app.translation.service.ContentTranslationService.ContentTranslationResponse;
import com.mannschaft.app.translation.service.ContentTranslationService.CreateTranslationRequest;
import com.mannschaft.app.translation.service.ContentTranslationService.TranslationDashboardResponse;
import com.mannschaft.app.translation.service.ContentTranslationService.TranslationSummaryResponse;
import com.mannschaft.app.translation.service.ContentTranslationService.UpdateTranslationRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 翻訳コンテンツ管理コントローラー。
 * 翻訳の作成・取得・更新・ステータス変更・公開・削除・一覧・ダッシュボードを提供する。
 * チーム・組織スコープに対応する。
 */
@RestController
@RequiredArgsConstructor
public class ContentTranslationController {

    private final ContentTranslationService contentTranslationService;
    private final AccessControlService accessControlService;

    // ========================================
    // チームスコープ
    // ========================================

    /**
     * チームの翻訳コンテンツを新規作成する。
     * 認可: MEMBERでも WRITE_TRANSLATION 権限があれば作成可能。ここでは MEMBER以上を許可。
     */
    @PostMapping("/api/v1/teams/{teamId}/translations")
    public ResponseEntity<ApiResponse<ContentTranslationResponse>> createTeamTranslation(
            @PathVariable Long teamId,
            @Valid @RequestBody CreateTranslationRequest req) {

        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkMembership(userId, teamId, "TEAM");

        req.setScopeType("TEAM");
        req.setScopeId(teamId);

        ApiResponse<ContentTranslationResponse> response =
                contentTranslationService.createTranslation(userId, req);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * チームの翻訳コンテンツをIDで取得する。
     * 認可: MEMBERでも閲覧可能
     */
    @GetMapping("/api/v1/teams/{teamId}/translations/{id}")
    public ApiResponse<ContentTranslationResponse> getTeamTranslation(
            @PathVariable Long teamId,
            @PathVariable Long id) {

        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkMembership(userId, teamId, "TEAM");

        return contentTranslationService.getTranslation(id);
    }

    /**
     * チームの特定コンテンツ×言語の翻訳を取得する。
     * 認可: MEMBERでも閲覧可能
     */
    @GetMapping("/api/v1/teams/{teamId}/translations/content")
    public ApiResponse<ContentTranslationResponse> getTeamTranslationForContent(
            @PathVariable Long teamId,
            @RequestParam String contentType,
            @RequestParam Long contentId,
            @RequestParam String language) {

        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkMembership(userId, teamId, "TEAM");

        return contentTranslationService.getTranslationForContent(contentType, contentId, language);
    }

    /**
     * チームの特定コンテンツの翻訳一覧を全言語分取得する。
     * 認可: MEMBERでも閲覧可能
     */
    @GetMapping("/api/v1/teams/{teamId}/translations/content/all")
    public ApiResponse<List<TranslationSummaryResponse>> listTeamTranslationsForContent(
            @PathVariable Long teamId,
            @RequestParam String contentType,
            @RequestParam Long contentId) {

        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkMembership(userId, teamId, "TEAM");

        return contentTranslationService.listTranslationsForContent(contentType, contentId);
    }

    /**
     * チームの翻訳コンテンツ一覧を取得する（フィルタ+ページネーション対応）。
     * 認可: MEMBERでも閲覧可能
     */
    @GetMapping("/api/v1/teams/{teamId}/translations")
    public PagedResponse<TranslationSummaryResponse> listTeamTranslations(
            @PathVariable Long teamId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String language,
            @RequestParam(required = false) String sourceType,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkMembership(userId, teamId, "TEAM");

        return contentTranslationService.listTranslations(
                "TEAM", teamId, status, language, sourceType, page, Math.min(size, 100));
    }

    /**
     * チームの翻訳コンテンツを更新する。
     * 認可: ADMIN以上、またはアサインされた翻訳者（ここではMEMBER以上を許可）
     */
    @PutMapping("/api/v1/teams/{teamId}/translations/{id}")
    public ApiResponse<ContentTranslationResponse> updateTeamTranslation(
            @PathVariable Long teamId,
            @PathVariable Long id,
            @Valid @RequestBody UpdateTranslationRequest req) {

        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkMembership(userId, teamId, "TEAM");

        return contentTranslationService.updateTranslation(id, userId, req);
    }

    /**
     * チームの翻訳コンテンツのステータスを変更する。
     * 認可: ADMIN以上（MEMBERはIN_REVIEWまで。PUBLISHEDへの遷移はADMIN以上）
     */
    @PatchMapping("/api/v1/teams/{teamId}/translations/{id}/status")
    public ApiResponse<ContentTranslationResponse> changeTeamTranslationStatus(
            @PathVariable Long teamId,
            @PathVariable Long id,
            @Valid @RequestBody ChangeStatusRequest req) {

        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkMembership(userId, teamId, "TEAM");

        return contentTranslationService.changeStatus(id, req);
    }

    /**
     * チームの翻訳コンテンツを公開状態（PUBLISHED）に更新する。
     * 認可: ADMIN以上
     */
    @PatchMapping("/api/v1/teams/{teamId}/translations/{id}/publish")
    public ApiResponse<ContentTranslationResponse> publishTeamTranslation(
            @PathVariable Long teamId,
            @PathVariable Long id) {

        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrAbove(userId, teamId, "TEAM");

        return contentTranslationService.publishTranslation(id);
    }

    /**
     * チームの特定コンテンツのPUBLISHED翻訳を全てNEEDS_UPDATEに更新する（内部/管理用）。
     * 認可: ADMIN以上
     */
    @PostMapping("/api/v1/teams/{teamId}/translations/mark-stale")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Integer> markTeamTranslationsAsStale(
            @PathVariable Long teamId,
            @RequestParam String contentType,
            @RequestParam Long contentId) {

        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrAbove(userId, teamId, "TEAM");

        int count = contentTranslationService.markAsStale(contentType, contentId);
        return ApiResponse.of(count);
    }

    /**
     * チームの翻訳ダッシュボード統計を取得する。
     * 認可: ADMIN以上
     */
    @GetMapping("/api/v1/teams/{teamId}/translations/dashboard")
    public ApiResponse<TranslationDashboardResponse> getTeamTranslationDashboard(
            @PathVariable Long teamId) {

        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrAbove(userId, teamId, "TEAM");

        return contentTranslationService.getDashboard("TEAM", teamId);
    }

    /**
     * チームの翻訳コンテンツを論理削除する。
     * 認可: ADMIN以上
     */
    @DeleteMapping("/api/v1/teams/{teamId}/translations/{id}")
    public ResponseEntity<Void> deleteTeamTranslation(
            @PathVariable Long teamId,
            @PathVariable Long id) {

        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrAbove(userId, teamId, "TEAM");

        contentTranslationService.deleteTranslation(id);
        return ResponseEntity.noContent().build();
    }

    // ========================================
    // 組織スコープ
    // ========================================

    @PostMapping("/api/v1/organizations/{orgId}/translations")
    public ResponseEntity<ApiResponse<ContentTranslationResponse>> createOrgTranslation(
            @PathVariable Long orgId,
            @Valid @RequestBody CreateTranslationRequest req) {

        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkMembership(userId, orgId, "ORGANIZATION");

        req.setScopeType("ORGANIZATION");
        req.setScopeId(orgId);

        ApiResponse<ContentTranslationResponse> response =
                contentTranslationService.createTranslation(userId, req);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/api/v1/organizations/{orgId}/translations/{id}")
    public ApiResponse<ContentTranslationResponse> getOrgTranslation(
            @PathVariable Long orgId,
            @PathVariable Long id) {

        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkMembership(userId, orgId, "ORGANIZATION");

        return contentTranslationService.getTranslation(id);
    }

    @GetMapping("/api/v1/organizations/{orgId}/translations/content")
    public ApiResponse<ContentTranslationResponse> getOrgTranslationForContent(
            @PathVariable Long orgId,
            @RequestParam String contentType,
            @RequestParam Long contentId,
            @RequestParam String language) {

        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkMembership(userId, orgId, "ORGANIZATION");

        return contentTranslationService.getTranslationForContent(contentType, contentId, language);
    }

    @GetMapping("/api/v1/organizations/{orgId}/translations/content/all")
    public ApiResponse<List<TranslationSummaryResponse>> listOrgTranslationsForContent(
            @PathVariable Long orgId,
            @RequestParam String contentType,
            @RequestParam Long contentId) {

        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkMembership(userId, orgId, "ORGANIZATION");

        return contentTranslationService.listTranslationsForContent(contentType, contentId);
    }

    @GetMapping("/api/v1/organizations/{orgId}/translations")
    public PagedResponse<TranslationSummaryResponse> listOrgTranslations(
            @PathVariable Long orgId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String language,
            @RequestParam(required = false) String sourceType,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkMembership(userId, orgId, "ORGANIZATION");

        return contentTranslationService.listTranslations(
                "ORGANIZATION", orgId, status, language, sourceType, page, Math.min(size, 100));
    }

    @PutMapping("/api/v1/organizations/{orgId}/translations/{id}")
    public ApiResponse<ContentTranslationResponse> updateOrgTranslation(
            @PathVariable Long orgId,
            @PathVariable Long id,
            @Valid @RequestBody UpdateTranslationRequest req) {

        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkMembership(userId, orgId, "ORGANIZATION");

        return contentTranslationService.updateTranslation(id, userId, req);
    }

    @PatchMapping("/api/v1/organizations/{orgId}/translations/{id}/status")
    public ApiResponse<ContentTranslationResponse> changeOrgTranslationStatus(
            @PathVariable Long orgId,
            @PathVariable Long id,
            @Valid @RequestBody ChangeStatusRequest req) {

        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkMembership(userId, orgId, "ORGANIZATION");

        return contentTranslationService.changeStatus(id, req);
    }

    @PatchMapping("/api/v1/organizations/{orgId}/translations/{id}/publish")
    public ApiResponse<ContentTranslationResponse> publishOrgTranslation(
            @PathVariable Long orgId,
            @PathVariable Long id) {

        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrAbove(userId, orgId, "ORGANIZATION");

        return contentTranslationService.publishTranslation(id);
    }

    @PostMapping("/api/v1/organizations/{orgId}/translations/mark-stale")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Integer> markOrgTranslationsAsStale(
            @PathVariable Long orgId,
            @RequestParam String contentType,
            @RequestParam Long contentId) {

        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrAbove(userId, orgId, "ORGANIZATION");

        int count = contentTranslationService.markAsStale(contentType, contentId);
        return ApiResponse.of(count);
    }

    @GetMapping("/api/v1/organizations/{orgId}/translations/dashboard")
    public ApiResponse<TranslationDashboardResponse> getOrgTranslationDashboard(
            @PathVariable Long orgId) {

        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrAbove(userId, orgId, "ORGANIZATION");

        return contentTranslationService.getDashboard("ORGANIZATION", orgId);
    }

    @DeleteMapping("/api/v1/organizations/{orgId}/translations/{id}")
    public ResponseEntity<Void> deleteOrgTranslation(
            @PathVariable Long orgId,
            @PathVariable Long id) {

        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrAbove(userId, orgId, "ORGANIZATION");

        contentTranslationService.deleteTranslation(id);
        return ResponseEntity.noContent().build();
    }
}
