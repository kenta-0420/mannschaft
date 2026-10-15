package com.mannschaft.app.publicview.controller;

import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.publicview.dto.NameDisclosureChangeLogResponse;
import com.mannschaft.app.publicview.dto.SupporterNameDisclosurePatchRequest;
import com.mannschaft.app.publicview.dto.SupporterNameDisclosureResponse;
import com.mannschaft.app.publicview.service.SupporterNameDisclosureService;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * F19.1 Phase 2: Admin 向け supporter_name_disclosure 切替 Controller。
 *
 * <p>チーム / 組織の投稿者識別モード（DISPLAY_NAME / REAL_NAME）を切り替える
 * PATCH エンドポイントと、変更履歴取得 GET エンドポイントを提供する。</p>
 *
 * <p><strong>認可</strong>: {@code hasRole('ADMIN')} または
 * {@code hasRole('SYSTEM_ADMIN')} のいずれかを保持していること。<br>
 * ADMIN はチーム / 組織の管理者を想定し、SYSTEM_ADMIN はシステム全体の管理者。</p>
 *
 * <p>{@code confirmed=false} のリクエストは Service 層で
 * {@link com.mannschaft.app.publicview.error.PublicViewErrorCode#NAME_DISCLOSURE_CONFIRM_REQUIRED}
 * (400) を返す（設計書 §6.2）。</p>
 *
 * <p>設計書: docs/features/F19.1_public_pages_identity_disclosure.md §6 / §7.7</p>
 */
@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "Admin 投稿者識別モード切替 API (F19.1 Phase 2)")
@RequiredArgsConstructor
public class AdminSupporterNameDisclosureController {

    private final SupporterNameDisclosureService service;

    /**
     * チームの投稿者識別モードを切り替える。
     *
     * <p>{@code confirmed=false} の場合は 400 を返す。</p>
     */
    @PatchMapping("/teams/{teamId}/supporter-name-disclosure")
    @PreAuthorize("@accessGuard.isScopeAdmin(authentication, #teamId, 'TEAM')")
    @Operation(
            summary = "チーム投稿者識別モード切替",
            description = "ADMIN または SYSTEM_ADMIN が teams.supporter_name_disclosure を変更する。"
                    + " confirmed=true が必須（REAL_NAME への切替時は警告ダイアログで確認後に送信すること）。")
    public ResponseEntity<SupporterNameDisclosureResponse> patchTeamDisclosure(
            @PathVariable Long teamId,
            @RequestBody @Valid SupporterNameDisclosurePatchRequest request) {
        Long operatorUserId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(service.patchTeamDisclosure(teamId, operatorUserId, request));
    }

    /**
     * 組織の投稿者識別モードを切り替える。
     */
    @PatchMapping("/organizations/{organizationId}/supporter-name-disclosure")
    @PreAuthorize("@accessGuard.isScopeAdmin(authentication, #organizationId, 'ORGANIZATION')")
    @Operation(
            summary = "組織投稿者識別モード切替",
            description = "ADMIN または SYSTEM_ADMIN が organizations.supporter_name_disclosure を変更する。"
                    + " confirmed=true が必須。")
    public ResponseEntity<SupporterNameDisclosureResponse> patchOrganizationDisclosure(
            @PathVariable Long organizationId,
            @RequestBody @Valid SupporterNameDisclosurePatchRequest request) {
        Long operatorUserId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(service.patchOrganizationDisclosure(organizationId, operatorUserId, request));
    }

    /**
     * チームの投稿者識別モード変更履歴を取得する。
     *
     * <p>設計書 §7.7「過去 1 年の切替履歴」に使用する。変更日時の降順で返す。</p>
     */
    @GetMapping("/teams/{teamId}/supporter-name-disclosure/history")
    @PreAuthorize("@accessGuard.isScopeAdmin(authentication, #teamId, 'TEAM')")
    @Operation(
            summary = "チーム投稿者識別モード変更履歴取得",
            description = "変更日時の降順でチームの supporter_name_disclosure 変更履歴を返す。")
    public ResponseEntity<List<NameDisclosureChangeLogResponse>> getTeamDisclosureHistory(
            @PathVariable Long teamId) {
        Long operatorUserId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(service.getTeamChangeHistory(teamId, operatorUserId));
    }

    /**
     * 組織の投稿者識別モード変更履歴を取得する。
     */
    @GetMapping("/organizations/{organizationId}/supporter-name-disclosure/history")
    @PreAuthorize("@accessGuard.isScopeAdmin(authentication, #organizationId, 'ORGANIZATION')")
    @Operation(
            summary = "組織投稿者識別モード変更履歴取得",
            description = "変更日時の降順で組織の supporter_name_disclosure 変更履歴を返す。")
    public ResponseEntity<List<NameDisclosureChangeLogResponse>> getOrganizationDisclosureHistory(
            @PathVariable Long organizationId) {
        Long operatorUserId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(service.getOrganizationChangeHistory(organizationId, operatorUserId));
    }
}
