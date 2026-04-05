package com.mannschaft.app.line.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.line.ScopeType;
import com.mannschaft.app.line.dto.CreateSnsFeedConfigRequest;
import com.mannschaft.app.line.dto.SnsFeedConfigResponse;
import com.mannschaft.app.line.dto.SnsFeedPreviewResponse;
import com.mannschaft.app.line.dto.UpdateSnsFeedConfigRequest;
import com.mannschaft.app.line.service.SnsFeedConfigService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import com.mannschaft.app.common.SecurityUtils;

/**
 * SNSフィード設定コントローラー（チーム・組織共用）。
 */
@RestController
@RequiredArgsConstructor
public class SnsFeedConfigController {

    private final SnsFeedConfigService snsFeedConfigService;

    // ─── チーム ───

    /**
     * チームのフィード設定一覧を取得する。
     */
    @GetMapping("/api/v1/teams/{teamId}/sns/feeds")
    public ApiResponse<List<SnsFeedConfigResponse>> listForTeam(@PathVariable Long teamId) {
        return ApiResponse.of(snsFeedConfigService.findAll(ScopeType.TEAM, teamId));
    }

    /**
     * チームのフィード設定を作成する。
     */
    @PostMapping("/api/v1/teams/{teamId}/sns/feeds")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<SnsFeedConfigResponse> createForTeam(
            @PathVariable Long teamId,
            @Valid @RequestBody CreateSnsFeedConfigRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(
                snsFeedConfigService.create(ScopeType.TEAM, teamId, userId, request));
    }

    /**
     * チームのフィード設定を更新する。
     */
    @PutMapping("/api/v1/teams/{teamId}/sns/feeds/{id}")
    public ApiResponse<SnsFeedConfigResponse> updateForTeam(
            @PathVariable Long teamId, @PathVariable Long id,
            @Valid @RequestBody UpdateSnsFeedConfigRequest request) {
        return ApiResponse.of(
                snsFeedConfigService.update(id, ScopeType.TEAM, teamId, request));
    }

    /**
     * チームのフィード設定を削除する。
     */
    @DeleteMapping("/api/v1/teams/{teamId}/sns/feeds/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteForTeam(@PathVariable Long teamId, @PathVariable Long id) {
        snsFeedConfigService.delete(id, ScopeType.TEAM, teamId);
    }

    /**
     * チームのフィードプレビューを取得する。
     */
    @GetMapping("/api/v1/teams/{teamId}/sns/feeds/{id}/preview")
    public ApiResponse<SnsFeedPreviewResponse> previewForTeam(
            @PathVariable Long teamId, @PathVariable Long id) {
        return ApiResponse.of(snsFeedConfigService.preview(id, ScopeType.TEAM, teamId));
    }

    // ─── 組織 ───

    /**
     * 組織のフィード設定一覧を取得する。
     */
    @GetMapping("/api/v1/organizations/{orgId}/sns/feeds")
    public ApiResponse<List<SnsFeedConfigResponse>> listForOrg(@PathVariable Long orgId) {
        return ApiResponse.of(snsFeedConfigService.findAll(ScopeType.ORGANIZATION, orgId));
    }

    /**
     * 組織のフィード設定を作成する。
     */
    @PostMapping("/api/v1/organizations/{orgId}/sns/feeds")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<SnsFeedConfigResponse> createForOrg(
            @PathVariable Long orgId,
            @Valid @RequestBody CreateSnsFeedConfigRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(
                snsFeedConfigService.create(ScopeType.ORGANIZATION, orgId, userId, request));
    }

    /**
     * 組織のフィード設定を更新する。
     */
    @PutMapping("/api/v1/organizations/{orgId}/sns/feeds/{id}")
    public ApiResponse<SnsFeedConfigResponse> updateForOrg(
            @PathVariable Long orgId, @PathVariable Long id,
            @Valid @RequestBody UpdateSnsFeedConfigRequest request) {
        return ApiResponse.of(
                snsFeedConfigService.update(id, ScopeType.ORGANIZATION, orgId, request));
    }

    /**
     * 組織のフィード設定を削除する。
     */
    @DeleteMapping("/api/v1/organizations/{orgId}/sns/feeds/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteForOrg(@PathVariable Long orgId, @PathVariable Long id) {
        snsFeedConfigService.delete(id, ScopeType.ORGANIZATION, orgId);
    }

    /**
     * 組織のフィードプレビューを取得する。
     */
    @GetMapping("/api/v1/organizations/{orgId}/sns/feeds/{id}/preview")
    public ApiResponse<SnsFeedPreviewResponse> previewForOrg(
            @PathVariable Long orgId, @PathVariable Long id) {
        return ApiResponse.of(snsFeedConfigService.preview(id, ScopeType.ORGANIZATION, orgId));
    }
}
