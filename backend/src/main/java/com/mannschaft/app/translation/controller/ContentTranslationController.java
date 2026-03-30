package com.mannschaft.app.translation.controller;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.translation.service.ContentTranslationService;
import com.mannschaft.app.translation.service.ContentTranslationService.ContentTranslationResponse;
import com.mannschaft.app.translation.service.ContentTranslationService.CreateTranslationRequest;
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
 * 翻訳の作成・取得・更新・公開・削除、およびステータス一括更新（mark-stale）を提供する。
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
     *
     * @param teamId チームID
     * @param req    翻訳作成リクエスト
     * @return 作成した翻訳コンテンツ（201 Created）
     */
    @PostMapping("/api/v1/teams/{teamId}/translations")
    public ResponseEntity<ApiResponse<ContentTranslationResponse>> createTeamTranslation(
            @PathVariable Long teamId,
            @Valid @RequestBody CreateTranslationRequest req) {

        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkMembership(userId, teamId, "TEAM");

        // スコープ情報をリクエストに補完する
        req.setScopeType("TEAM");
        req.setScopeId(teamId);

        ApiResponse<ContentTranslationResponse> response =
                contentTranslationService.createTranslation(userId, req);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * チームの翻訳コンテンツをIDで取得する。
     * 認可: MEMBERでも閲覧可能
     *
     * @param teamId チームID
     * @param id     翻訳コンテンツID
     * @return 翻訳コンテンツ詳細
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
     *
     * @param teamId      チームID
     * @param contentType コンテンツ種別（BLOG_POST / ANNOUNCEMENT / KNOWLEDGE_BASE）
     * @param contentId   コンテンツID
     * @param language    言語コード（ISO 639-1）
     * @return 翻訳コンテンツ詳細
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
     *
     * @param teamId      チームID
     * @param contentType コンテンツ種別
     * @param contentId   コンテンツID
     * @return 翻訳サマリーリスト
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
     * チームの翻訳コンテンツを更新する。
     * 認可: ADMIN以上、またはアサインされた翻訳者（ここではMEMBER以上を許可）
     *
     * @param teamId チームID
     * @param id     翻訳コンテンツID
     * @param req    翻訳更新リクエスト
     * @return 更新後の翻訳コンテンツ
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
     * チームの翻訳コンテンツを公開状態（PUBLISHED）に更新する。
     * 認可: ADMIN以上
     *
     * @param teamId チームID
     * @param id     翻訳コンテンツID
     * @return 更新後の翻訳コンテンツ
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
     * 原文が更新された場合に呼び出す。
     * 認可: ADMIN以上（管理操作のため）
     *
     * @param teamId      チームID
     * @param contentType コンテンツ種別
     * @param contentId   コンテンツID
     * @return 更新件数を含むレスポンス
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
     * チームの翻訳コンテンツを論理削除する。
     * 認可: ADMIN以上
     *
     * @param teamId チームID
     * @param id     翻訳コンテンツID
     * @return 204 No Content
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

    /**
     * 組織の翻訳コンテンツを新規作成する。
     * 認可: MEMBERでも WRITE_TRANSLATION 権限があれば作成可能。ここでは MEMBER以上を許可。
     *
     * @param orgId 組織ID
     * @param req   翻訳作成リクエスト
     * @return 作成した翻訳コンテンツ（201 Created）
     */
    @PostMapping("/api/v1/organizations/{orgId}/translations")
    public ResponseEntity<ApiResponse<ContentTranslationResponse>> createOrgTranslation(
            @PathVariable Long orgId,
            @Valid @RequestBody CreateTranslationRequest req) {

        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkMembership(userId, orgId, "ORGANIZATION");

        // スコープ情報をリクエストに補完する
        req.setScopeType("ORGANIZATION");
        req.setScopeId(orgId);

        ApiResponse<ContentTranslationResponse> response =
                contentTranslationService.createTranslation(userId, req);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * 組織の翻訳コンテンツをIDで取得する。
     * 認可: MEMBERでも閲覧可能
     *
     * @param orgId 組織ID
     * @param id    翻訳コンテンツID
     * @return 翻訳コンテンツ詳細
     */
    @GetMapping("/api/v1/organizations/{orgId}/translations/{id}")
    public ApiResponse<ContentTranslationResponse> getOrgTranslation(
            @PathVariable Long orgId,
            @PathVariable Long id) {

        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkMembership(userId, orgId, "ORGANIZATION");

        return contentTranslationService.getTranslation(id);
    }

    /**
     * 組織の特定コンテンツ×言語の翻訳を取得する。
     * 認可: MEMBERでも閲覧可能
     *
     * @param orgId       組織ID
     * @param contentType コンテンツ種別
     * @param contentId   コンテンツID
     * @param language    言語コード
     * @return 翻訳コンテンツ詳細
     */
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

    /**
     * 組織の特定コンテンツの翻訳一覧を全言語分取得する。
     * 認可: MEMBERでも閲覧可能
     *
     * @param orgId       組織ID
     * @param contentType コンテンツ種別
     * @param contentId   コンテンツID
     * @return 翻訳サマリーリスト
     */
    @GetMapping("/api/v1/organizations/{orgId}/translations/content/all")
    public ApiResponse<List<TranslationSummaryResponse>> listOrgTranslationsForContent(
            @PathVariable Long orgId,
            @RequestParam String contentType,
            @RequestParam Long contentId) {

        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkMembership(userId, orgId, "ORGANIZATION");

        return contentTranslationService.listTranslationsForContent(contentType, contentId);
    }

    /**
     * 組織の翻訳コンテンツを更新する。
     * 認可: ADMIN以上、またはアサインされた翻訳者（ここではMEMBER以上を許可）
     *
     * @param orgId 組織ID
     * @param id    翻訳コンテンツID
     * @param req   翻訳更新リクエスト
     * @return 更新後の翻訳コンテンツ
     */
    @PutMapping("/api/v1/organizations/{orgId}/translations/{id}")
    public ApiResponse<ContentTranslationResponse> updateOrgTranslation(
            @PathVariable Long orgId,
            @PathVariable Long id,
            @Valid @RequestBody UpdateTranslationRequest req) {

        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkMembership(userId, orgId, "ORGANIZATION");

        return contentTranslationService.updateTranslation(id, userId, req);
    }

    /**
     * 組織の翻訳コンテンツを公開状態（PUBLISHED）に更新する。
     * 認可: ADMIN以上
     *
     * @param orgId 組織ID
     * @param id    翻訳コンテンツID
     * @return 更新後の翻訳コンテンツ
     */
    @PatchMapping("/api/v1/organizations/{orgId}/translations/{id}/publish")
    public ApiResponse<ContentTranslationResponse> publishOrgTranslation(
            @PathVariable Long orgId,
            @PathVariable Long id) {

        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrAbove(userId, orgId, "ORGANIZATION");

        return contentTranslationService.publishTranslation(id);
    }

    /**
     * 組織の特定コンテンツのPUBLISHED翻訳を全てNEEDS_UPDATEに更新する（内部/管理用）。
     * 認可: ADMIN以上（管理操作のため）
     *
     * @param orgId       組織ID
     * @param contentType コンテンツ種別
     * @param contentId   コンテンツID
     * @return 更新件数を含むレスポンス
     */
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

    /**
     * 組織の翻訳コンテンツを論理削除する。
     * 認可: ADMIN以上
     *
     * @param orgId 組織ID
     * @param id    翻訳コンテンツID
     * @return 204 No Content
     */
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
