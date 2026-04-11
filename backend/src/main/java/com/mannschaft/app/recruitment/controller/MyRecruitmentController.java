package com.mannschaft.app.recruitment.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.recruitment.dto.RecruitmentFeedItemResponse;
import com.mannschaft.app.recruitment.dto.RecruitmentParticipantResponse;
import com.mannschaft.app.recruitment.service.RecruitmentListingService;
import com.mannschaft.app.recruitment.service.RecruitmentParticipantService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * F03.11 募集型予約: 個人マイページ Controller (§9.4)。
 * Phase 2 で /me/recruitment-feed (フォロー先の新着) を追加。
 */
@RestController
@RequestMapping("/api/v1/me")
@Tag(name = "F03.11 募集型予約 / 個人マイページ", description = "自分の参加中・参加予定の募集・フォロー先フィード")
@RequiredArgsConstructor
public class MyRecruitmentController {

    private final RecruitmentParticipantService participantService;
    private final RecruitmentListingService listingService;

    /**
     * 自分の参加中・参加予定の募集一覧 (CONFIRMED/WAITLISTED/APPLIED)。
     */
    @GetMapping("/recruitment-listings")
    @Operation(summary = "自分の参加中・参加予定の募集一覧")
    public ResponseEntity<ApiResponse<List<RecruitmentParticipantResponse>>> myActiveParticipations() {
        return ResponseEntity.ok(ApiResponse.of(
                listingService.getMyListings(SecurityUtils.getCurrentUserId())));
    }

    /**
     * フォロー先・サポーター先スコープの最新 OPEN 募集フィード (Phase 2)。
     */
    @GetMapping("/recruitment-feed")
    @Operation(summary = "フォロー先・サポーター先の新着募集フィード", description = "フォロー先チーム/組織の最新OPEN募集を最大20件返す")
    public ResponseEntity<ApiResponse<List<RecruitmentFeedItemResponse>>> myFeed() {
        return ResponseEntity.ok(ApiResponse.of(
                listingService.getMyFeed(SecurityUtils.getCurrentUserId())));
    }
}
