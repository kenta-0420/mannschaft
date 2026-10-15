package com.mannschaft.app.residencestatus.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.residencestatus.dto.AnnualReviewResponseDto;
import com.mannschaft.app.residencestatus.dto.SubmitAnnualResponseRequest;
import com.mannschaft.app.residencestatus.service.AnnualReviewResponseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.mannschaft.app.common.security.AuthorizedInService;

import java.util.List;
import java.util.UUID;

/**
 * 年次更新回答コントローラー（F09.16 S3-A）。
 *
 * <p>認可は Service 層に委譲する。Controller はパスパラメータの組織 ID と
 * 現在ユーザー ID の引き渡しのみを行う。</p>
 */
@RestController
@RequestMapping("/api/v1/organizations/{orgId}/residence-status/annual-reviews/{reviewId}/responses")
@Tag(name = "年次更新回答（F09.16）", description = "F09.16 居住実態管理 - 年次更新回答 API")
@RequiredArgsConstructor
public class AnnualReviewResponseController {

    private final AnnualReviewResponseService responseService;

    /**
     * キャンペーンの全回答一覧を取得する（ADMIN/DEPUTY_ADMIN のみ）。
     */
    @GetMapping
    @Operation(summary = "年次更新キャンペーン全回答一覧（ADMIN/DEPUTY_ADMIN のみ）")
    public ResponseEntity<ApiResponse<List<AnnualReviewResponseDto>>> listResponses(
            @PathVariable Long orgId,
            @PathVariable UUID reviewId) {
        Long userId = SecurityUtils.getCurrentUserId();
        List<AnnualReviewResponseDto> list = responseService.listResponses(orgId, reviewId, userId);
        return ResponseEntity.ok(ApiResponse.of(list));
    }

    /**
     * 自分の回答を送信する（UPSERT）。
     *
     * <p>同一キャンペーン × 居住者の回答が既存の場合は更新、新規の場合は作成する。</p>
     */
    // 認可根治戦役 Wave6 ロットC: AnnualReviewResponseService#submitResponse が
    // ResidentRegistryRepository#findById で req.residentRegistryId の所有者を引き、
    // 呼び出し元 userId と一致することを検証してから UPSERT する（他居住者の residentRegistryId
    // を指定した回答書き換えは 404 相当で拒否）。
    @AuthorizedInService
    @PutMapping("/me")
    @Operation(summary = "年次更新回答送信（UPSERT）")
    public ResponseEntity<ApiResponse<AnnualReviewResponseDto>> submitMyResponse(
            @PathVariable Long orgId,
            @PathVariable UUID reviewId,
            @Valid @RequestBody SubmitAnnualResponseRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        AnnualReviewResponseDto dto = responseService.submitResponse(orgId, reviewId, userId, request);
        return ResponseEntity.ok(ApiResponse.of(dto));
    }
}
