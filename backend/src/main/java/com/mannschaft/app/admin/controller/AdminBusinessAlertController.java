package com.mannschaft.app.admin.controller;

import com.mannschaft.app.admin.dto.AdminBusinessAlertSummaryResponse;
import com.mannschaft.app.admin.service.AdminBusinessAlertService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 業務アラートコントローラー（F10.7）。
 *
 * <p>認証済み ADMIN/DEPUTY_ADMIN ユーザーが管理するチームの予約・問い合わせ未読件数のサマリーを返す。</p>
 *
 * <p>設計書: docs/features/F10.7_admin_business_alert.md §4</p>
 */
@RestController
@RequestMapping("/api/v1/admin/business-alerts")
@Tag(name = "管理 - 業務アラート", description = "F10.7 業務アラートAPI（チーム/組織管理者向け）")
@RequiredArgsConstructor
public class AdminBusinessAlertController {

    private final AdminBusinessAlertService adminBusinessAlertService;

    /**
     * 業務アラートサマリーを取得する。
     *
     * <p>認証済みユーザーが管理するチームごとの新規予約・承認待ち・問い合わせ未読件数を返す。
     * Valkey に 60 秒間キャッシュする。</p>
     *
     * @return 業務アラートサマリー
     */
    @GetMapping("/summary")
    @Operation(
            summary = "業務アラートサマリー取得",
            description = "認証済み ADMIN/DEPUTY_ADMIN ユーザーが管理するチームの予約・問い合わせ件数サマリーを返す。" +
                    "少なくとも 1 チームで ADMIN/DEPUTY_ADMIN ロールを持たない場合は 403。")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "未認証"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "ADMIN/DEPUTY_ADMIN ロールなし"),
    })
    @PreAuthorize("@adminRoleChecker.hasAnyAdminRoleInAnyTeam(authentication)")
    public ResponseEntity<ApiResponse<AdminBusinessAlertSummaryResponse>> getSummary() {
        Long userId = SecurityUtils.getCurrentUserId();
        AdminBusinessAlertSummaryResponse response = adminBusinessAlertService.getSummary(userId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
