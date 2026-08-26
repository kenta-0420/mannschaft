package com.mannschaft.app.payment.controller;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.payment.MembershipSubscriptionStatus;
import com.mannschaft.app.payment.dto.MembershipSubscriptionListItemResponse;
import com.mannschaft.app.payment.service.MembershipSubscriptionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * F08.9 P5 第四波: チーム管理者向け継続課金一覧コントローラー（設計書 02 §4.1）。
 *
 * <p>{@code GET /api/v1/teams/{teamId}/membership-subscriptions} — チーム ADMIN が継続課金一覧を取得する。
 * 認可は {@link AccessControlService#checkAdminOrAbove} で行う（正準・設計書 03 §3 マトリクス厳守）。
 * status フィルタ（任意 query param）で絞り込み可。ページングは Service 層で将来対応（現在は全件）。</p>
 *
 * <h3>@WebMvcTest 非互換の回避</h3>
 * 契約テストは {@code MockMvcBuilders.standaloneSetup} + {@code MockitoExtension} で構成し Spring Security を回避する
 * （#1266 前科・P1 Wave5 と同流儀）。
 *
 * <p>設計書: docs/features/F08.9_membership_billing_paywall/02_api_design.md §4.1</p>
 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}")
@Tag(name = "継続課金（チーム管理）", description = "F08.9 P5 チーム向け継続課金一覧（管理者専用）")
@RequiredArgsConstructor
public class TeamMembershipSubscriptionController {

    private final MembershipSubscriptionService membershipSubscriptionService;
    private final AccessControlService accessControlService;

    /**
     * チームの継続課金一覧を取得する（チーム ADMIN 認可・設計書 02 §4.1）。
     *
     * <p>認可: チーム ADMIN（{@link AccessControlService#checkAdminOrAbove}・"TEAM" scope・03 §3 正準）。
     * 無権原は {@link AccessControlService} が {@code BusinessException} を投げる（{@code GlobalExceptionHandler} が 403 変換）。</p>
     *
     * @param teamId チーム ID
     * @param status 状態フィルタ（任意・{@code ACTIVE}/{@code PENDING}/{@code CANCELLED} 等）
     * @return 200 OK + 継続課金一覧（作成日時降順・名前解決済み）
     */
    @GetMapping("/membership-subscriptions")
    @Operation(summary = "チームの継続課金一覧（F08.9 P5 第四波）")
    public ResponseEntity<ApiResponse<List<MembershipSubscriptionListItemResponse>>> listTeamSubscriptions(
            @PathVariable Long teamId,
            @RequestParam(required = false) String status) {

        Long actorUserId = SecurityUtils.getCurrentUserId();
        // チーム ADMIN 認可（AccessControlService 正準・03 §3 マトリクス）。
        accessControlService.checkAdminOrAbove(actorUserId, teamId, "TEAM");

        MembershipSubscriptionStatus statusFilter = null;
        if (status != null && !status.isBlank()) {
            try {
                statusFilter = MembershipSubscriptionStatus.valueOf(status.toUpperCase());
            } catch (IllegalArgumentException e) {
                // 不明な status 文字列は無視して全件返す（寛容な解釈）。
            }
        }

        List<MembershipSubscriptionListItemResponse> list =
                membershipSubscriptionService.findForTeamWithNames(teamId, statusFilter);
        return ResponseEntity.ok(ApiResponse.of(list));
    }
}
