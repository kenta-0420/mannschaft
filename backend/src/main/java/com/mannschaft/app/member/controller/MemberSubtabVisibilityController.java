package com.mannschaft.app.member.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.dashboard.ScopeType;
import com.mannschaft.app.member.dto.MemberSubtabVisibilityResponse;
import com.mannschaft.app.member.dto.UpdateMemberSubtabVisibilityRequest;
import com.mannschaft.app.member.service.MemberSubtabVisibilityService;
import com.mannschaft.app.organization.service.OrganizationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * CMP-260919-1140 Phase 1: メンバー統合画面（一覧／紹介）サブタブ ロール別可視性 コントローラー。
 *
 * <p>組織単位でサブタブ（一覧／紹介）の最低必要ロールを取得・一括更新する。
 * GET は非メンバーでも 403 とせずデフォルト値で 200、PUT は ADMIN 無条件 / DEPUTY_ADMIN は
 * {@code MEMBER_SUBTAB_VISIBILITY_MANAGE} パーミッション保有時のみ可（Service 層で検証）。</p>
 *
 * <p>Phase 1 は組織スコープのみを対象とする（チームスコープは対象外。設計書 §2）。</p>
 *
 * @see MemberSubtabVisibilityService
 */
@RestController
@RequestMapping("/api/v1/organizations/{slug}/member-subtab-visibility")
@Tag(name = "メンバーサブタブ可視性", description = "CMP-260919-1140 Phase 1: メンバー統合画面（一覧／紹介）のロール別可視性管理")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class MemberSubtabVisibilityController {

    private final MemberSubtabVisibilityService visibilityService;
    private final OrganizationService organizationService;

    @GetMapping
    @Operation(summary = "メンバーサブタブ可視性設定一覧",
            description = "指定組織の一覧／紹介サブタブの最低必要ロール一覧を取得する。非メンバーでもデフォルト値で 200。")
    public ResponseEntity<ApiResponse<MemberSubtabVisibilityResponse>> getSettings(@PathVariable String slug) {
        Long orgId = organizationService.resolveOrgId(slug);
        Long userId = SecurityUtils.getCurrentUserId();
        MemberSubtabVisibilityResponse response =
                visibilityService.getSettings(userId, ScopeType.ORGANIZATION, orgId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    @PutMapping
    @Operation(summary = "メンバーサブタブ可視性設定更新",
            description = "指定組織の一覧／紹介サブタブの最低必要ロールを一括更新する。"
                    + "ADMIN は無条件、DEPUTY_ADMIN は MEMBER_SUBTAB_VISIBILITY_MANAGE 権限保有時のみ可。"
                    + "一覧タブに PUBLIC を指定すると 422。")
    public ResponseEntity<ApiResponse<MemberSubtabVisibilityResponse>> updateSettings(
            @PathVariable String slug,
            @Valid @RequestBody UpdateMemberSubtabVisibilityRequest request) {
        Long orgId = organizationService.resolveOrgId(slug);
        Long userId = SecurityUtils.getCurrentUserId();
        MemberSubtabVisibilityResponse response =
                visibilityService.updateSettings(userId, ScopeType.ORGANIZATION, orgId, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
