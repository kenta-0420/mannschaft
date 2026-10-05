package com.mannschaft.app.social.announcement.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.featuregate.AlwaysReachable;
import com.mannschaft.app.common.featuregate.AlwaysReachableCategory;
import com.mannschaft.app.common.security.AuthorizedInService;
import com.mannschaft.app.social.announcement.AnnouncementPreviewService;
import com.mannschaft.app.social.announcement.AnnouncementScopeType;
import com.mannschaft.app.social.announcement.dto.AnnouncementPreviewResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 本文プレビュー。AnnouncementPreviewService が所有 scope・最新配信対象・可視性・課金を検証し、
 * 元 Service が最新 F00/所属/実在を再検証する（F02.6 §4.1）。GET は既読・閲覧数を変更しない。
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
@AuthorizedInService
@Tag(name = "お知らせ本文プレビュー")
public class AnnouncementPreviewController {

    private final AnnouncementPreviewService previewService;

    @GetMapping("/teams/{scopeId}/announcements/{feedId}/preview")
    @AlwaysReachable(category = AlwaysReachableCategory.CORE,
            reason = "既存のお知らせ配信内容を横断閲覧する中核入口。元コンテンツの最新可視性・課金認可はサービスで検証する")
    @Operation(summary = "チームお知らせ本文プレビュー")
    public ResponseEntity<ApiResponse<AnnouncementPreviewResponse>> teamPreview(
            @PathVariable Long scopeId, @PathVariable Long feedId) {
        return preview(AnnouncementScopeType.TEAM, scopeId, feedId);
    }

    @GetMapping("/organizations/{scopeId}/announcements/{feedId}/preview")
    @AlwaysReachable(category = AlwaysReachableCategory.CORE,
            reason = "既存のお知らせ配信内容を横断閲覧する中核入口。元コンテンツの最新可視性・課金認可はサービスで検証する")
    @Operation(summary = "組織お知らせ本文プレビュー")
    public ResponseEntity<ApiResponse<AnnouncementPreviewResponse>> organizationPreview(
            @PathVariable Long scopeId, @PathVariable Long feedId) {
        return preview(AnnouncementScopeType.ORGANIZATION, scopeId, feedId);
    }

    private ResponseEntity<ApiResponse<AnnouncementPreviewResponse>> preview(
            AnnouncementScopeType scopeType, Long scopeId, Long feedId) {
        if (scopeId == null || scopeId <= 0 || feedId == null || feedId <= 0) {
            throw new BusinessException(CommonErrorCode.COMMON_001);
        }
        AnnouncementPreviewResponse body = previewService.preview(
                scopeType, scopeId, feedId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "private, no-cache, no-store, must-revalidate, max-age=0")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header(HttpHeaders.EXPIRES, "0")
                .body(ApiResponse.of(body));
    }
}
