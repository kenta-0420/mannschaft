package com.mannschaft.app.recruitment.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.recruitment.dto.RecruitmentParticipantResponse;
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
 * Phase 1+5a では「自分の参加中・参加予定」のみ。
 * /me/recruitment-feed (フォロー先の新着) は Phase 2 で実装。
 */
@RestController
@RequestMapping("/api/v1/me")
@Tag(name = "F03.11 募集型予約 / 個人マイページ", description = "自分の参加中・参加予定の募集")
@RequiredArgsConstructor
public class MyRecruitmentController {

    private final RecruitmentParticipantService participantService;

    @GetMapping("/recruitment-listings")
    @Operation(summary = "自分の参加中・参加予定の募集一覧")
    public ResponseEntity<ApiResponse<List<RecruitmentParticipantResponse>>> myActiveParticipations() {
        return ResponseEntity.ok(ApiResponse.of(
                participantService.listMyActiveParticipations(SecurityUtils.getCurrentUserId())));
    }
}
