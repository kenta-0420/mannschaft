package com.mannschaft.app.residencestatus.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.residencestatus.dto.AnnualReviewDto;
import com.mannschaft.app.residencestatus.dto.CreateAnnualReviewRequest;
import com.mannschaft.app.residencestatus.service.AnnualReviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.mannschaft.app.common.security.AuthorizedInService;

import java.util.List;
import java.util.UUID;

/**
 * 年次更新キャンペーンコントローラー（F09.16 S3-A）。
 *
 * <p>認可は Service 層に委譲する。Controller はパスパラメータの組織 ID と
 * 現在ユーザー ID の引き渡しのみを行う。</p>
 */
@RestController
@RequestMapping("/api/v1/organizations/{orgId}/residence-status/annual-reviews")
@Tag(name = "年次更新キャンペーン（F09.16）", description = "F09.16 居住実態管理 - 年次更新キャンペーン API")
@RequiredArgsConstructor
public class AnnualReviewController {

    private final AnnualReviewService annualReviewService;

    /**
     * キャンペーンを起動する（ADMIN/DEPUTY_ADMIN のみ）。
     */
    @PostMapping
    @Operation(summary = "年次更新キャンペーン起動（ADMIN/DEPUTY_ADMIN のみ）")
    public ResponseEntity<ApiResponse<AnnualReviewDto>> createReview(
            @PathVariable Long orgId,
            @Valid @RequestBody CreateAnnualReviewRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        AnnualReviewDto dto = annualReviewService.createReview(orgId, userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(dto));
    }

    /**
     * キャンペーン一覧を取得する（ADMIN/DEPUTY_ADMIN のみ）。
     */
    @GetMapping
    @Operation(summary = "年次更新キャンペーン一覧（ADMIN/DEPUTY_ADMIN のみ）")
    public ResponseEntity<ApiResponse<List<AnnualReviewDto>>> listReviews(@PathVariable Long orgId) {
        Long userId = SecurityUtils.getCurrentUserId();
        List<AnnualReviewDto> list = annualReviewService.listReviews(orgId, userId);
        return ResponseEntity.ok(ApiResponse.of(list));
    }

    /**
     * キャンペーン詳細を取得する（ADMIN/DEPUTY_ADMIN のみ）。
     */
    @GetMapping("/{id}")
    @Operation(summary = "年次更新キャンペーン詳細（ADMIN/DEPUTY_ADMIN のみ）")
    public ResponseEntity<ApiResponse<AnnualReviewDto>> getReview(
            @PathVariable Long orgId,
            @PathVariable UUID id) {
        Long userId = SecurityUtils.getCurrentUserId();
        AnnualReviewDto dto = annualReviewService.getReview(orgId, id, userId);
        return ResponseEntity.ok(ApiResponse.of(dto));
    }

    /**
     * キャンペーンを手動クローズする（ADMIN/DEPUTY_ADMIN のみ）。
     */
    @PostMapping("/{id}/close")
    @Operation(summary = "年次更新キャンペーン手動クローズ（ADMIN/DEPUTY_ADMIN のみ）")
    public ResponseEntity<ApiResponse<AnnualReviewDto>> closeReview(
            @PathVariable Long orgId,
            @PathVariable UUID id) {
        Long userId = SecurityUtils.getCurrentUserId();
        AnnualReviewDto dto = annualReviewService.closeReview(orgId, id, userId);
        return ResponseEntity.ok(ApiResponse.of(dto));
    }

    /**
     * 居住者向け: 進行中のキャンペーン一覧を取得する。
     */
    // 認可根治戦役 Wave6 ロットC: AnnualReviewService#listMyReviews が
    // ResidentRegistryRepository#findActiveByUserIdAndOrganizationId で呼び出し元 userId が
    // 当該 orgId の現居住者であることを検証してから一覧を返す（非居住者は 404 相当で拒否）。
    @AuthorizedInService
    @GetMapping("/my")
    @Operation(summary = "居住者向け: 進行中の年次更新キャンペーン一覧")
    public ResponseEntity<ApiResponse<List<AnnualReviewDto>>> listMyReviews(@PathVariable Long orgId) {
        Long userId = SecurityUtils.getCurrentUserId();
        List<AnnualReviewDto> list = annualReviewService.listMyReviews(orgId, userId);
        return ResponseEntity.ok(ApiResponse.of(list));
    }
}
