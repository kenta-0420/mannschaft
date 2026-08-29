package com.mannschaft.app.recruitment.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.security.SelfScopedEndpoint;
import com.mannschaft.app.recruitment.dto.CreateRecruitmentListingRequest;
import com.mannschaft.app.recruitment.dto.CancelRecruitmentListingRequest;
import com.mannschaft.app.recruitment.dto.RecruitmentListingResponse;
import com.mannschaft.app.recruitment.dto.RecruitmentListingSummaryResponse;
import com.mannschaft.app.recruitment.dto.UpdateRecruitmentListingRequest;
import com.mannschaft.app.recruitment.service.PersonalMarketListingService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me/market/listings")
@RequiredArgsConstructor
public class PersonalMarketListingController {

    private final PersonalMarketListingService personalMarketListingService;

    @SelfScopedEndpoint("認証済みユーザーIDをscopeIdとcreatedByへ固定する")
    @PostMapping
    @Operation(summary = "個人市の札を下書きで作成")
    public ResponseEntity<ApiResponse<RecruitmentListingResponse>> create(
            @Valid @RequestBody CreateRecruitmentListingRequest request) {
        RecruitmentListingResponse response =
                personalMarketListingService.create(SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    @SelfScopedEndpoint("認証済みユーザーIDを履歴の検索スコープへ固定する")
    @GetMapping
    @Operation(summary = "個人市で立てた札の履歴を取得")
    public ResponseEntity<PagedResponse<RecruitmentListingSummaryResponse>> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<RecruitmentListingSummaryResponse> result = personalMarketListingService.list(
                SecurityUtils.getCurrentUserId(), status, PageRequest.of(page, size));
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                result.getTotalElements(), result.getNumber(), result.getSize(), result.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(result.getContent(), meta));
    }

    @SelfScopedEndpoint("個人札の所有者を認証済みユーザーに固定する")
    @PatchMapping("/{id}")
    @Operation(summary = "個人札のDRAFT編集")
    public ResponseEntity<ApiResponse<RecruitmentListingResponse>> update(
            @PathVariable Long id, @Valid @RequestBody UpdateRecruitmentListingRequest request) {
        return ResponseEntity.ok(ApiResponse.of(personalMarketListingService.update(
                SecurityUtils.getCurrentUserId(), id, request)));
    }

    @SelfScopedEndpoint("個人札の所有者を認証済みユーザーに固定する")
    @PostMapping("/{id}/cancel")
    @Operation(summary = "個人札のDRAFT取消")
    public ResponseEntity<ApiResponse<RecruitmentListingResponse>> cancel(
            @PathVariable Long id, @RequestBody(required = false) CancelRecruitmentListingRequest request) {
        return ResponseEntity.ok(ApiResponse.of(personalMarketListingService.cancel(
                SecurityUtils.getCurrentUserId(), id, request)));
    }
}
