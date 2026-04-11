package com.mannschaft.app.recruitment.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.recruitment.dto.CancelRecruitmentListingRequest;
import com.mannschaft.app.recruitment.dto.CancellationFeeEstimateResponse;
import com.mannschaft.app.recruitment.dto.RecruitmentDistributionTargetResponse;
import com.mannschaft.app.recruitment.dto.RecruitmentListingResponse;
import com.mannschaft.app.recruitment.dto.RecruitmentParticipantResponse;
import com.mannschaft.app.recruitment.dto.SetDistributionTargetsRequest;
import com.mannschaft.app.recruitment.dto.UpdateRecruitmentListingRequest;
import com.mannschaft.app.recruitment.entity.RecruitmentListingEntity;
import com.mannschaft.app.recruitment.service.RecruitmentCancellationPolicyService;
import com.mannschaft.app.recruitment.service.RecruitmentListingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * F03.11 募集型予約: 募集枠 個別操作 Controller (§9.1, §9.9)。
 * ID 指定で詳細取得・編集・公開・キャンセル・論理削除・キャンセル料試算を行う。
 */
@RestController
@RequestMapping("/api/v1/recruitment-listings")
@Tag(name = "F03.11 募集型予約 / 募集枠", description = "募集枠の個別操作")
@RequiredArgsConstructor
public class RecruitmentListingController {

    private final RecruitmentListingService listingService;
    private final RecruitmentCancellationPolicyService cancellationPolicyService;

    @GetMapping("/{id}")
    @Operation(summary = "募集枠詳細取得")
    public ResponseEntity<ApiResponse<RecruitmentListingResponse>> get(@PathVariable Long id) {
        RecruitmentListingResponse response = listingService.getListing(id, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    @PatchMapping("/{id}")
    @Operation(summary = "募集枠編集 (§5.7)")
    public ResponseEntity<ApiResponse<RecruitmentListingResponse>> update(
            @PathVariable Long id,
            @Valid @RequestBody UpdateRecruitmentListingRequest request) {
        return ResponseEntity.ok(ApiResponse.of(
                listingService.update(id, SecurityUtils.getCurrentUserId(), request)));
    }

    @PostMapping("/{id}/publish")
    @Operation(summary = "募集枠公開 (DRAFT → OPEN)")
    public ResponseEntity<ApiResponse<RecruitmentListingResponse>> publish(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.of(
                listingService.publish(id, SecurityUtils.getCurrentUserId())));
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "募集枠 主催者キャンセル")
    public ResponseEntity<ApiResponse<RecruitmentListingResponse>> cancel(
            @PathVariable Long id,
            @RequestBody(required = false) CancelRecruitmentListingRequest request) {
        return ResponseEntity.ok(ApiResponse.of(
                listingService.cancelByAdmin(id, SecurityUtils.getCurrentUserId(), request)));
    }

    @PostMapping("/{id}/archive")
    @Operation(summary = "募集枠 論理削除")
    public ResponseEntity<Void> archive(@PathVariable Long id) {
        listingService.archive(id, SecurityUtils.getCurrentUserId());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/cancellation-fee-estimate")
    @Operation(summary = "キャンセル料試算 (§9.9)")
    public ResponseEntity<ApiResponse<CancellationFeeEstimateResponse>> estimateCancellationFee(
            @PathVariable Long id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime at) {
        // 認可は getListing 内のチェックを再利用 (本人/管理者のみ閲覧可)
        listingService.getListing(id, SecurityUtils.getCurrentUserId());
        RecruitmentListingEntity listing = listingService.findOrThrow(id);
        CancellationFeeEstimateResponse estimate = cancellationPolicyService.estimateFee(listing, at);
        return ResponseEntity.ok(ApiResponse.of(estimate));
    }

    // ===========================================
    // Phase 2: 配信対象設定 (§9.3)
    // ===========================================

    @GetMapping("/{id}/distribution-targets")
    @Operation(summary = "配信対象取得 (Phase 2)")
    public ResponseEntity<ApiResponse<List<RecruitmentDistributionTargetResponse>>> getDistributionTargets(
            @PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.of(
                listingService.getDistributionTargets(id, SecurityUtils.getCurrentUserId())));
    }

    @PutMapping("/{id}/distribution-targets")
    @Operation(summary = "配信対象設定 (Phase 2)", description = "全削除 → 再設定。publish 前に必ず呼ぶこと")
    public ResponseEntity<ApiResponse<List<RecruitmentDistributionTargetResponse>>> setDistributionTargets(
            @PathVariable Long id,
            @Valid @RequestBody SetDistributionTargetsRequest request) {
        return ResponseEntity.ok(ApiResponse.of(
                listingService.setDistributionTargets(id, SecurityUtils.getCurrentUserId(), request.getTargetTypes())));
    }

    // ===========================================
    // Phase 2: 申込確定 (§9.2)
    // ===========================================

    @PostMapping("/{listingId}/participants/{participantId}/confirm")
    @Operation(summary = "申込確定 (APPLIED → CONFIRMED, Phase 2)", description = "確定 + RECRUITMENT_CONFIRMED 通知 + リマインダー作成")
    public ResponseEntity<ApiResponse<RecruitmentParticipantResponse>> confirmApplication(
            @PathVariable Long listingId,
            @PathVariable Long participantId) {
        return ResponseEntity.ok(ApiResponse.of(
                listingService.confirmApplication(participantId, SecurityUtils.getCurrentUserId())));
    }
}
