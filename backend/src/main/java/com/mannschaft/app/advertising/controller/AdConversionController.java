package com.mannschaft.app.advertising.controller;

import com.mannschaft.app.advertising.dto.AdConversionResponse;
import com.mannschaft.app.advertising.dto.AdConversionSummaryResponse;
import com.mannschaft.app.advertising.service.AdConversionService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 広告コンバージョンコントローラー（広告主ダッシュボード用）。
 */
@RestController
@RequestMapping("/api/v1/advertiser/campaigns/{campaignId}/conversions")
@RequiredArgsConstructor
public class AdConversionController {

    private final AdConversionService adConversionService;
    private final AccessControlService accessControlService;

    /**
     * 組織スコープの権限検証。
     */
    private void verifyOrganizationAccess(Long organizationId) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrAbove(userId, organizationId, "ORGANIZATION");
    }

    /**
     * キャンペーン別コンバージョン一覧を取得する。
     */
    @GetMapping
    public ApiResponse<List<AdConversionResponse>> getConversions(
            @PathVariable Long campaignId,
            @RequestParam Long organizationId,
            @RequestParam LocalDate from,
            @RequestParam LocalDate to) {
        verifyOrganizationAccess(organizationId);
        return ApiResponse.of(adConversionService.getConversions(campaignId, organizationId, from, to));
    }

    /**
     * キャンペーン別コンバージョンサマリーを取得する。
     */
    @GetMapping("/summary")
    public ApiResponse<AdConversionSummaryResponse> getConversionSummary(
            @PathVariable Long campaignId,
            @RequestParam Long organizationId,
            @RequestParam LocalDate from,
            @RequestParam LocalDate to) {
        verifyOrganizationAccess(organizationId);
        return ApiResponse.of(adConversionService.getConversionSummary(campaignId, organizationId, from, to));
    }
}
