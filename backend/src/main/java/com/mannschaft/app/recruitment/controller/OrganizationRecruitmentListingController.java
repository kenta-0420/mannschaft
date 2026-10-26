package com.mannschaft.app.recruitment.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.recruitment.RecruitmentScopeType;
import com.mannschaft.app.recruitment.dto.CreateRecruitmentListingRequest;
import com.mannschaft.app.recruitment.dto.RecruitmentListingResponse;
import com.mannschaft.app.recruitment.dto.RecruitmentListingSummaryResponse;
import com.mannschaft.app.recruitment.service.RecruitmentListingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * F03.11 募集型予約: 組織の募集枠管理 Controller (§9.1)。
 */
@RestController
@RequestMapping("/api/v1/organizations/{orgId}/recruitment-listings")
@Tag(name = "F03.11 募集型予約 / 募集枠 (組織)", description = "組織の募集枠 作成・一覧")
@RequiredArgsConstructor
public class OrganizationRecruitmentListingController {

    private final RecruitmentListingService listingService;

    @GetMapping
    @Operation(summary = "組織募集枠一覧")
    public ResponseEntity<PagedResponse<RecruitmentListingSummaryResponse>> list(
            @PathVariable Long orgId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<RecruitmentListingSummaryResponse> result = listingService.listByScope(
                RecruitmentScopeType.ORGANIZATION, orgId, status, SecurityUtils.getCurrentUserId(),
                PageRequest.of(page, size));
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                result.getTotalElements(), result.getNumber(), result.getSize(), result.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(result.getContent(), meta));
    }

    @PostMapping
    @Operation(summary = "組織募集枠作成 (DRAFT で作成)")
    public ResponseEntity<ApiResponse<RecruitmentListingResponse>> create(
            @PathVariable Long orgId,
            @Valid @RequestBody CreateRecruitmentListingRequest request) {
        RecruitmentListingResponse response = listingService.create(
                RecruitmentScopeType.ORGANIZATION, orgId, SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }
}
