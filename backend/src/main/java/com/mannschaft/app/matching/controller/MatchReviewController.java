package com.mannschaft.app.matching.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.matching.dto.CreateReviewRequest;
import com.mannschaft.app.matching.dto.ReviewCreateResponse;
import com.mannschaft.app.matching.dto.TeamReviewSummaryResponse;
import com.mannschaft.app.matching.service.MatchReviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * レビューコントローラー。レビューの投稿・取得APIを提供する。
 */
@RestController
@Tag(name = "マッチングレビュー", description = "F08.1 マッチングレビュー投稿・取得")
@RequiredArgsConstructor
public class MatchReviewController {

    private final MatchReviewService reviewService;

    // TODO: JwtAuthenticationFilter実装時にSecurityContextHolderから取得に変更
    private Long getCurrentUserId() {
        return 1L;
    }

    /**
     * レビュー投稿。
     */
    @PostMapping("/api/v1/matching/reviews")
    @Operation(summary = "レビュー投稿")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<ReviewCreateResponse>> createReview(
            @Valid @RequestBody CreateReviewRequest request) {
        Long currentTeamId = getCurrentUserId();
        ReviewCreateResponse response = reviewService.createReview(currentTeamId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * チームのレビュー一覧。
     */
    @GetMapping("/api/v1/teams/{teamId}/matching/reviews")
    @Operation(summary = "チームのレビュー一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<TeamReviewSummaryResponse>> getTeamReviews(
            @PathVariable Long teamId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        TeamReviewSummaryResponse response = reviewService.getTeamReviews(teamId, PageRequest.of(page, Math.min(size, 50)));
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
