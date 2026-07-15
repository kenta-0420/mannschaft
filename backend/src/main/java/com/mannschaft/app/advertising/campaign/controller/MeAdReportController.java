package com.mannschaft.app.advertising.campaign.controller;

import com.mannschaft.app.advertising.campaign.dto.AdReportCreatedResponse;
import com.mannschaft.app.advertising.campaign.dto.CreateAdReportRequest;
import com.mannschaft.app.advertising.campaign.service.AdReportService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * F09.19.9 受信者向け通報 API（{@code POST /api/v1/me/ad-reports}・認証必須）。
 *
 * <p>正本 §12。メッセージ型（campaignId・UUID）/ 運用型（operationalCampaignId・Long）を XOR で受け付ける。
 * これまで FE（{@code useAdDeliveriesApi.createReport} / {@code AdReportModal} /
 * {@code SpotlightSlot}）が呼んで 404 になっていた幻 API の実体を提供する。</p>
 */
@RestController
@RequestMapping("/api/v1/me/ad-reports")
@RequiredArgsConstructor
@Tag(name = "受信者 - 広告通報", description = "F09.19.9 メッセージ型/運用型両対応の広告通報")
public class MeAdReportController {

    private final AdReportService adReportService;

    /** 通報を作成する。201 {@code { data: { id, status, createdAt } }}。 */
    @PostMapping
    @Operation(summary = "広告を通報する",
            description = "campaignId（メッセージ型）/ operationalCampaignId（運用型）を XOR で指定する。"
                    + "両方指定・両方 null は 400 / AD_032、不存在対象は 404。")
    public ResponseEntity<ApiResponse<AdReportCreatedResponse>> create(
            @Valid @RequestBody CreateAdReportRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        AdReportCreatedResponse created = adReportService.createReport(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(created));
    }
}
