package com.mannschaft.app.recruitment.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.recruitment.dto.CancelRecruitmentListingRequest;
import com.mannschaft.app.recruitment.dto.CancellationFeeEstimateResponse;
import com.mannschaft.app.recruitment.dto.RecruitmentListingResponse;
import com.mannschaft.app.recruitment.dto.RecruitmentListingSearchRequest;
import com.mannschaft.app.recruitment.dto.RecruitmentListingSummaryResponse;
import com.mannschaft.app.recruitment.dto.UpdateRecruitmentListingRequest;
import com.mannschaft.app.recruitment.entity.RecruitmentListingEntity;
import com.mannschaft.app.recruitment.service.RecruitmentCancellationPolicyService;
import com.mannschaft.app.recruitment.service.RecruitmentListingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

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

    /**
     * 全体検索エンドポイント — 認証不要 (PUBLIC アクセス可)。
     *
     * status = OPEN の募集を対象に、カテゴリ・日時・参加形式・キーワード・場所で絞り込む。
     * visibility が SCOPE_ONLY / SUPPORTERS_ONLY の募集も検索結果に含める。
     * 詳細閲覧時に権限チェックを行うため、一覧では visibility による除外は行わない。
     * keyword / location は空文字列の場合 null 扱いとして LIKE 検索を省略する。
     */
    @GetMapping("/search")
    @Operation(summary = "募集枠 全体検索 (§Phase4)")
    public ResponseEntity<PagedResponse<RecruitmentListingSummaryResponse>> searchListings(
            @Valid @ModelAttribute RecruitmentListingSearchRequest req) {
        // XSS 対策: keyword・location をトリムし、空文字列は null に正規化
        String keyword = req.getKeyword();
        if (keyword != null) {
            keyword = keyword.trim();
            if (keyword.isEmpty()) keyword = null;
            else if (keyword.length() > 100) keyword = keyword.substring(0, 100);
        }
        String location = req.getLocation();
        if (location != null) {
            location = location.trim();
            if (location.isEmpty()) location = null;
            else if (location.length() > 100) location = location.substring(0, 100);
        }
        Page<RecruitmentListingSummaryResponse> page = listingService.searchPublicListings(
                req.getCategoryId(), req.getSubcategoryId(),
                req.getStartFrom(), req.getStartTo(),
                req.getParticipationType(),
                keyword, location,
                PageRequest.of(req.getPage(), req.getSize()));
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                page.getTotalElements(), page.getNumber(), page.getSize(), page.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(page.getContent(), meta));
    }

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
}
