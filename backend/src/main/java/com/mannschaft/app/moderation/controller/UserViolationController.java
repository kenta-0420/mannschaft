package com.mannschaft.app.moderation.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.moderation.dto.CreateReReviewRequest;
import com.mannschaft.app.moderation.dto.SelfCorrectRequest;
import com.mannschaft.app.moderation.dto.UserViolationHistoryResponse;
import com.mannschaft.app.moderation.dto.ViolationResponse;
import com.mannschaft.app.moderation.dto.WarningReReviewResponse;
import com.mannschaft.app.moderation.service.UserViolationService;
import com.mannschaft.app.moderation.service.WarningReReviewService;
import com.mannschaft.app.common.security.AuthorizedInService;
import com.mannschaft.app.common.security.SelfScopedEndpoint;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.mannschaft.app.common.SecurityUtils;

/**
 * ユーザー違反コントローラー。自分の違反履歴・自主修正・再レビュー依頼APIを提供する。
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "ユーザー違反", description = "F10.2 ユーザー向け違反管理")
@RequiredArgsConstructor
public class UserViolationController {

    private final UserViolationService violationService;
    private final WarningReReviewService reReviewService;


    /**
     * 自分の違反履歴を取得する。
     *
     * <p><b>認可方式（{@link SelfScopedEndpoint} メソッド付与）</b>:
     * {@code violationService.getViolationHistory} は {@code SecurityUtils.getCurrentUserId()} の
     * みを検索条件に渡すため、他人の違反履歴へ到達する経路が構造的に無い
     * （UserViolationController#getMyViolations）。</p>
     *
     * <p>認可根治戦役 Wave6 監査済。</p>
     */
    @SelfScopedEndpoint(
            "violationService.getViolationHistory(userId) は SecurityUtils.getCurrentUserId() の"
                    + "みを検索条件に渡す（UserViolationController#getMyViolations）")
    @GetMapping("/users/me/violations")
    @Operation(summary = "自分の違反履歴取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<UserViolationHistoryResponse>> getMyViolations() {
        UserViolationHistoryResponse response = violationService.getViolationHistory(SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * WARNING自主修正完了通知。
     *
     * <p><b>認可方式（{@link AuthorizedInService} メソッド付与）</b>:
     * {@code UserViolationService#selfCorrect} が actionId から取得した違反の
     * {@code userId} と操作者を比較し、不一致なら {@code VIOLATION_NOT_FOUND} を投げる。
     * 認可根治戦役 Wave6 監査済。</p>
     */
    @AuthorizedInService
    @PatchMapping("/warnings/{actionId}/self-correct")
    @Operation(summary = "WARNING自主修正完了通知")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "修正完了")
    public ResponseEntity<ApiResponse<ViolationResponse>> selfCorrect(
            @PathVariable Long actionId,
            @Valid @RequestBody(required = false) SelfCorrectRequest request) {
        String note = request != null ? request.getCorrectionNote() : null;
        ViolationResponse response = violationService.selfCorrect(actionId, SecurityUtils.getCurrentUserId(), note);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * WARNING再レビュー依頼。
     *
     * <p><b>認可方式（{@link AuthorizedInService} メソッド付与）</b>:
     * {@code WarningReReviewService#createReReview} が actionId から取得した違反の
     * {@code userId}/{@code reportId} と操作者・request を比較し、不一致なら
     * {@code VIOLATION_NOT_FOUND} を投げる。認可根治戦役 Wave6 監査済。</p>
     */
    @AuthorizedInService
    @PostMapping("/warnings/{actionId}/re-review")
    @Operation(summary = "WARNING再レビュー依頼")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "依頼作成成功")
    public ResponseEntity<ApiResponse<WarningReReviewResponse>> requestReReview(
            @PathVariable Long actionId,
            @Valid @RequestBody CreateReReviewRequest request) {
        WarningReReviewResponse response = reReviewService.createReReview(
                SecurityUtils.getCurrentUserId(), actionId, request.getReportId(), request.getReason());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }
}
