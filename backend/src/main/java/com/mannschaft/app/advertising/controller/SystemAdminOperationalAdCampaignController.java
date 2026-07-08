package com.mannschaft.app.advertising.controller;

import com.mannschaft.app.advertising.dto.OperationalCampaignResponse;
import com.mannschaft.app.advertising.dto.RejectOperationalCampaignRequest;
import com.mannschaft.app.advertising.entity.AdCampaignEntity.CampaignStatus;
import com.mannschaft.app.advertising.service.OperationalAdCampaignService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.common.SecurityUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 運用型キャンペーン審査（SYSTEM_ADMIN 向け。F09.19 §6.1）。
 *
 * <p>既存 {@code /api/v1/system-admin/ad-campaigns/**}（F09.17 メッセージ型審査）との URL 衝突回避のため
 * {@code -operational} サフィックスで分離する。</p>
 *
 * <p><b>SYSTEM_ADMIN ガード</b>: {@code AccessControlService.checkSystemAdmin}（user_roles を参照する
 * DB ベース判定）で行う。{@code @PreAuthorize("hasRole('SYSTEM_ADMIN')")} は authority ベースのため、
 * SecurityContext に authority を積まない直接呼び出しの契約テスト（試練 IT）を破ってしまう。DB ベース判定は
 * HTTP 経路・直接呼び出しのいずれでも一貫して機能し、越境は 403（COMMON_002）で拒否する。</p>
 */
@RestController
@RequestMapping("/api/v1/system-admin/ad-campaigns-operational")
@RequiredArgsConstructor
public class SystemAdminOperationalAdCampaignController {

    private final OperationalAdCampaignService operationalAdCampaignService;
    private final AccessControlService accessControlService;

    private Long requireSystemAdmin() {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkSystemAdmin(userId);
        return userId;
    }

    /** 審査キュー（status フィルタ既定 PENDING_REVIEW）。 */
    @GetMapping
    public PagedResponse<OperationalCampaignResponse> list(
            @RequestParam(required = false, defaultValue = "PENDING_REVIEW") CampaignStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        requireSystemAdmin();
        return operationalAdCampaignService.listForReview(status, page, size);
    }

    /** PENDING_REVIEW → ACTIVE。 */
    @PatchMapping("/{id}/approve")
    public ApiResponse<OperationalCampaignResponse> approve(@PathVariable Long id) {
        Long adminUserId = requireSystemAdmin();
        return ApiResponse.of(operationalAdCampaignService.approve(id, adminUserId));
    }

    /** PENDING_REVIEW → DRAFT（差戻し。理由必須）。 */
    @PatchMapping("/{id}/reject")
    public ApiResponse<OperationalCampaignResponse> reject(
            @PathVariable Long id,
            @Valid @RequestBody RejectOperationalCampaignRequest request) {
        Long adminUserId = requireSystemAdmin();
        return ApiResponse.of(operationalAdCampaignService.reject(id, adminUserId, request.reason()));
    }
}
