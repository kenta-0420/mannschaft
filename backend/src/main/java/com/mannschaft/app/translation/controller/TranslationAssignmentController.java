package com.mannschaft.app.translation.controller;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.translation.service.TranslationAssignmentService;
import com.mannschaft.app.translation.service.TranslationAssignmentService.AssignTranslatorRequest;
import com.mannschaft.app.translation.service.TranslationAssignmentService.TranslationAssignmentResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 翻訳者アサイン管理コントローラー。
 * 翻訳者（ユーザー）のアサイン作成・一覧取得・削除を提供する。
 * チーム・組織スコープに対応する。
 */
@RestController
@RequiredArgsConstructor
public class TranslationAssignmentController {

    private final TranslationAssignmentService translationAssignmentService;
    private final AccessControlService accessControlService;

    // ========================================
    // チームスコープ
    // ========================================

    /**
     * チームの翻訳者をアサインする。
     * 既に同一スコープ×ユーザー×言語のアサインが存在する場合は既存レコードを返す（冪等）。
     * 認可: ADMIN以上
     *
     * @param teamId チームID
     * @param req    アサインリクエスト
     * @return 作成または既存のアサインレスポンス（201 Created）
     */
    @PostMapping("/api/v1/teams/{teamId}/translations/assignments")
    public ResponseEntity<ApiResponse<TranslationAssignmentResponse>> assignTeamTranslator(
            @PathVariable Long teamId,
            @Valid @RequestBody AssignTranslatorRequest req) {

        Long userId = SecurityUtils.getCurrentUserId();
        // 翻訳者のアサインはADMIN以上の操作
        accessControlService.checkAdminOrAbove(userId, teamId, "TEAM");

        ApiResponse<TranslationAssignmentResponse> response =
                translationAssignmentService.assignTranslator(userId, req);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * チームのアサイン一覧を取得する。
     * translationId からスコープ情報を参照して一覧を返す。
     * 認可: MEMBERでも閲覧可能
     *
     * @param teamId       チームID
     * @param translationId 翻訳コンテンツID（スコープ情報の取得に使用）
     * @return アサインレスポンスリスト
     */
    @GetMapping("/api/v1/teams/{teamId}/translations/assignments")
    public ApiResponse<List<TranslationAssignmentResponse>> listTeamAssignments(
            @PathVariable Long teamId,
            @RequestParam Long translationId) {

        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkMembership(userId, teamId, "TEAM");

        return translationAssignmentService.listAssignments(translationId);
    }

    /**
     * チームの翻訳者アサインを物理削除する。
     * 認可: ADMIN以上
     *
     * @param teamId チームID
     * @param id     アサインID
     * @return 204 No Content
     */
    @DeleteMapping("/api/v1/teams/{teamId}/translations/assignments/{id}")
    public ResponseEntity<Void> removeTeamAssignment(
            @PathVariable Long teamId,
            @PathVariable Long id) {

        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrAbove(userId, teamId, "TEAM");

        translationAssignmentService.removeAssignment(id);
        return ResponseEntity.noContent().build();
    }

    // ========================================
    // 組織スコープ
    // ========================================

    /**
     * 組織の翻訳者をアサインする。
     * 既に同一スコープ×ユーザー×言語のアサインが存在する場合は既存レコードを返す（冪等）。
     * 認可: ADMIN以上
     *
     * @param orgId 組織ID
     * @param req   アサインリクエスト
     * @return 作成または既存のアサインレスポンス（201 Created）
     */
    @PostMapping("/api/v1/organizations/{orgId}/translations/assignments")
    public ResponseEntity<ApiResponse<TranslationAssignmentResponse>> assignOrgTranslator(
            @PathVariable Long orgId,
            @Valid @RequestBody AssignTranslatorRequest req) {

        Long userId = SecurityUtils.getCurrentUserId();
        // 翻訳者のアサインはADMIN以上の操作
        accessControlService.checkAdminOrAbove(userId, orgId, "ORGANIZATION");

        ApiResponse<TranslationAssignmentResponse> response =
                translationAssignmentService.assignTranslator(userId, req);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * 組織のアサイン一覧を取得する。
     * translationId からスコープ情報を参照して一覧を返す。
     * 認可: MEMBERでも閲覧可能
     *
     * @param orgId         組織ID
     * @param translationId 翻訳コンテンツID（スコープ情報の取得に使用）
     * @return アサインレスポンスリスト
     */
    @GetMapping("/api/v1/organizations/{orgId}/translations/assignments")
    public ApiResponse<List<TranslationAssignmentResponse>> listOrgAssignments(
            @PathVariable Long orgId,
            @RequestParam Long translationId) {

        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkMembership(userId, orgId, "ORGANIZATION");

        return translationAssignmentService.listAssignments(translationId);
    }

    /**
     * 組織の翻訳者アサインを物理削除する。
     * 認可: ADMIN以上
     *
     * @param orgId 組織ID
     * @param id    アサインID
     * @return 204 No Content
     */
    @DeleteMapping("/api/v1/organizations/{orgId}/translations/assignments/{id}")
    public ResponseEntity<Void> removeOrgAssignment(
            @PathVariable Long orgId,
            @PathVariable Long id) {

        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrAbove(userId, orgId, "ORGANIZATION");

        translationAssignmentService.removeAssignment(id);
        return ResponseEntity.noContent().build();
    }
}
