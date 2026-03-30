package com.mannschaft.app.translation.controller;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.translation.service.TranslationConfigService;
import com.mannschaft.app.translation.service.TranslationConfigService.TranslationConfigResponse;
import com.mannschaft.app.translation.service.TranslationConfigService.UpsertTranslationConfigRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 翻訳設定コントローラー。
 * チーム・組織スコープの翻訳設定（対応言語・原文言語・自動検出フラグ）の取得・更新を提供する。
 */
@RestController
@RequiredArgsConstructor
public class TranslationConfigController {

    private final TranslationConfigService translationConfigService;
    private final AccessControlService accessControlService;

    // ========================================
    // チームスコープ
    // ========================================

    /**
     * チームの翻訳設定を作成・更新する（upsert）。
     * 認可: ADMIN以上
     *
     * @param teamId チームID
     * @param req    翻訳設定リクエスト
     * @return 保存後の翻訳設定
     */
    @PutMapping("/api/v1/teams/{teamId}/translations/config")
    public ResponseEntity<ApiResponse<TranslationConfigResponse>> upsertTeamConfig(
            @PathVariable Long teamId,
            @Valid @RequestBody UpsertTranslationConfigRequest req) {

        Long userId = SecurityUtils.getCurrentUserId();
        // ADMIN以上のみ翻訳設定を変更可能
        accessControlService.checkAdminOrAbove(userId, teamId, "TEAM");

        ApiResponse<TranslationConfigResponse> response =
                translationConfigService.upsertConfig("TEAM", teamId, req);
        return ResponseEntity.ok(response);
    }

    /**
     * チームの翻訳設定を取得する。
     * 未設定の場合はデフォルト設定を返す。
     * 認可: MEMBERでも閲覧可能
     *
     * @param teamId チームID
     * @return 翻訳設定
     */
    @GetMapping("/api/v1/teams/{teamId}/translations/config")
    public ApiResponse<TranslationConfigResponse> getTeamConfig(
            @PathVariable Long teamId) {

        Long userId = SecurityUtils.getCurrentUserId();
        // メンバー以上なら設定を閲覧可能
        accessControlService.checkMembership(userId, teamId, "TEAM");

        return translationConfigService.getConfig("TEAM", teamId);
    }

    // ========================================
    // 組織スコープ
    // ========================================

    /**
     * 組織の翻訳設定を作成・更新する（upsert）。
     * 認可: ADMIN以上
     *
     * @param orgId 組織ID
     * @param req   翻訳設定リクエスト
     * @return 保存後の翻訳設定
     */
    @PutMapping("/api/v1/organizations/{orgId}/translations/config")
    public ResponseEntity<ApiResponse<TranslationConfigResponse>> upsertOrgConfig(
            @PathVariable Long orgId,
            @Valid @RequestBody UpsertTranslationConfigRequest req) {

        Long userId = SecurityUtils.getCurrentUserId();
        // ADMIN以上のみ翻訳設定を変更可能
        accessControlService.checkAdminOrAbove(userId, orgId, "ORGANIZATION");

        ApiResponse<TranslationConfigResponse> response =
                translationConfigService.upsertConfig("ORGANIZATION", orgId, req);
        return ResponseEntity.ok(response);
    }

    /**
     * 組織の翻訳設定を取得する。
     * 未設定の場合はデフォルト設定を返す。
     * 認可: MEMBERでも閲覧可能
     *
     * @param orgId 組織ID
     * @return 翻訳設定
     */
    @GetMapping("/api/v1/organizations/{orgId}/translations/config")
    public ApiResponse<TranslationConfigResponse> getOrgConfig(
            @PathVariable Long orgId) {

        Long userId = SecurityUtils.getCurrentUserId();
        // メンバー以上なら設定を閲覧可能
        accessControlService.checkMembership(userId, orgId, "ORGANIZATION");

        return translationConfigService.getConfig("ORGANIZATION", orgId);
    }
}
