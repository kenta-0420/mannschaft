package com.mannschaft.app.moderation.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.moderation.dto.AppealResponse;
import com.mannschaft.app.moderation.dto.CreateModerationTemplateRequest;
import com.mannschaft.app.moderation.dto.EscalateReReviewRequest;
import com.mannschaft.app.moderation.dto.ModerationDashboardResponse;
import com.mannschaft.app.moderation.dto.ModerationSettingsResponse;
import com.mannschaft.app.moderation.dto.ModerationTemplateResponse;
import com.mannschaft.app.moderation.dto.ReviewAppealRequest;
import com.mannschaft.app.moderation.dto.ReviewReReviewRequest;
import com.mannschaft.app.moderation.dto.ReviewUnflagRequest;
import com.mannschaft.app.moderation.dto.UpdateModerationTemplateRequest;
import com.mannschaft.app.moderation.dto.UpdateSettingRequest;
import com.mannschaft.app.moderation.dto.UserViolationHistoryResponse;
import com.mannschaft.app.moderation.dto.WarningReReviewResponse;
import com.mannschaft.app.moderation.dto.YabaiUnflagResponse;
import com.mannschaft.app.moderation.dto.SettingsHistoryResponse;
import com.mannschaft.app.moderation.ModerationExtMapper;
import com.mannschaft.app.moderation.service.ContentReportService;
import com.mannschaft.app.moderation.service.ModerationAppealService;
import com.mannschaft.app.moderation.service.ModerationSettingsService;
import com.mannschaft.app.moderation.service.ModerationTemplateService;
import com.mannschaft.app.moderation.service.UserViolationService;
import com.mannschaft.app.moderation.service.WarningReReviewService;
import com.mannschaft.app.moderation.service.YabaiUnflagService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * SYSTEM_ADMIN向けモデレーション拡張コントローラー。
 * ダッシュボード・違反履歴・異議申立て・解除申請・再レビュー・テンプレート・設定APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/system-admin")
@Tag(name = "SYSTEM_ADMINモデレーション", description = "F10.2 SYSTEM_ADMIN向けモデレーション拡張")
@RequiredArgsConstructor
public class SystemAdminModerationController {

    private final ContentReportService contentReportService;
    private final UserViolationService violationService;
    private final ModerationAppealService appealService;
    private final YabaiUnflagService unflagService;
    private final WarningReReviewService reReviewService;
    private final ModerationTemplateService templateService;
    private final ModerationSettingsService settingsService;
    private final ModerationExtMapper moderationExtMapper;

    // TODO: JwtAuthenticationFilter実装時にSecurityContextHolderから取得に変更
    private Long getCurrentUserId() {
        return 1L;
    }

    // ========== ダッシュボード ==========

    /**
     * モデレーション全体統計を取得する。
     */
    @GetMapping("/moderation/dashboard")
    @Operation(summary = "モデレーションダッシュボード")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<ModerationDashboardResponse>> getDashboard() {
        long pendingReports = contentReportService.countPendingReports();
        long pendingAppeals = appealService.countPendingAppeals();
        long pendingReReviews = reReviewService.countPendingReReviews();
        long escalatedReReviews = reReviewService.countEscalatedReReviews();
        long pendingUnflagRequests = unflagService.countPendingRequests();
        long activeViolations = violationService.countActiveViolations();
        // TODO: yabaiUsersCountは別途集計クエリが必要
        long yabaiUsers = 0;

        ModerationDashboardResponse response = new ModerationDashboardResponse(
                pendingReports, pendingAppeals, pendingReReviews,
                escalatedReReviews, pendingUnflagRequests, activeViolations, yabaiUsers);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    // ========== ユーザー違反 ==========

    /**
     * 全スコープ違反履歴を取得する。
     */
    @GetMapping("/users/{id}/violations")
    @Operation(summary = "全スコープ違反履歴取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<UserViolationHistoryResponse>> getUserViolations(@PathVariable Long id) {
        UserViolationHistoryResponse response = violationService.getViolationHistory(id);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    // ========== 異議申立て ==========

    /**
     * 異議申立て一覧を取得する。
     */
    @GetMapping("/appeals")
    @Operation(summary = "異議申立て一覧取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<PagedResponse<AppealResponse>> getAppeals(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<AppealResponse> appeals = appealService.getAppeals(PageRequest.of(page, size));
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                appeals.getTotalElements(), page, size, appeals.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(appeals.getContent(), meta));
    }

    /**
     * 異議申立てをレビューする。
     */
    @PatchMapping("/appeals/{id}/review")
    @Operation(summary = "異議申立てレビュー")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "レビュー成功")
    public ResponseEntity<ApiResponse<AppealResponse>> reviewAppeal(
            @PathVariable Long id,
            @Valid @RequestBody ReviewAppealRequest request) {
        AppealResponse response = appealService.reviewAppeal(
                id, request.getStatus(), request.getReviewNote(), getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    // ========== ヤバいやつ ==========

    /**
     * 解除申請一覧を取得する。
     */
    @GetMapping("/yabai/unflag-requests")
    @Operation(summary = "解除申請一覧取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<PagedResponse<YabaiUnflagResponse>> getUnflagRequests(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<YabaiUnflagResponse> requests = unflagService.getUnflagRequests(PageRequest.of(page, size));
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                requests.getTotalElements(), page, size, requests.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(requests.getContent(), meta));
    }

    /**
     * 解除申請をレビューする。
     */
    @PatchMapping("/yabai/unflag-requests/{id}/review")
    @Operation(summary = "解除申請レビュー")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "レビュー成功")
    public ResponseEntity<ApiResponse<YabaiUnflagResponse>> reviewUnflagRequest(
            @PathVariable Long id,
            @Valid @RequestBody ReviewUnflagRequest request) {
        YabaiUnflagResponse response = unflagService.reviewUnflagRequest(
                id, request.getStatus(), request.getReviewNote(), getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * ヤバいやつを手動解除する。
     */
    @PatchMapping("/users/{id}/yabai/unflag")
    @Operation(summary = "ヤバいやつ手動解除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "解除成功")
    public ResponseEntity<ApiResponse<Void>> manualUnflag(@PathVariable Long id) {
        // TODO: ユーザーの有効違反を一括無効化してヤバいやつ認定を解除する
        return ResponseEntity.ok(ApiResponse.of(null));
    }

    // ========== WARNING再レビュー ==========

    /**
     * 昇格済み再レビュー一覧を取得する。
     */
    @GetMapping("/warning-re-reviews")
    @Operation(summary = "昇格済み再レビュー一覧取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<PagedResponse<WarningReReviewResponse>> getEscalatedReReviews(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<WarningReReviewResponse> reviews = reReviewService.getEscalatedReReviews(PageRequest.of(page, size));
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                reviews.getTotalElements(), page, size, reviews.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(reviews.getContent(), meta));
    }

    /**
     * SYSTEM_ADMIN最終判定。
     */
    @PatchMapping("/warning-re-reviews/{id}/review")
    @Operation(summary = "SYSTEM_ADMIN最終判定")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "判定成功")
    public ResponseEntity<ApiResponse<WarningReReviewResponse>> systemAdminReview(
            @PathVariable Long id,
            @Valid @RequestBody ReviewReReviewRequest request) {
        WarningReReviewResponse response = reReviewService.systemAdminReview(
                id, request.getStatus(), request.getReviewNote(), getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 再レビューをSYSTEM_ADMINに昇格する。
     */
    @PatchMapping("/warnings/re-reviews/{id}/escalate")
    @Operation(summary = "再レビュー昇格")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "昇格成功")
    public ResponseEntity<ApiResponse<WarningReReviewResponse>> escalateReReview(
            @PathVariable Long id,
            @Valid @RequestBody EscalateReReviewRequest request) {
        WarningReReviewResponse response = reReviewService.escalate(id, request.getEscalationReason());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    // ========== テンプレート ==========

    /**
     * テンプレートを作成する。
     */
    @PostMapping("/moderation/templates")
    @Operation(summary = "対応テンプレート作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<ModerationTemplateResponse>> createTemplate(
            @Valid @RequestBody CreateModerationTemplateRequest request) {
        ModerationTemplateResponse response = templateService.createTemplate(
                request.getName(), request.getActionType(), request.getReason(),
                request.getTemplateText(), request.getLanguage(), request.getIsDefault(),
                getCurrentUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * テンプレートを更新する。
     */
    @PutMapping("/moderation/templates/{id}")
    @Operation(summary = "対応テンプレート更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<ModerationTemplateResponse>> updateTemplate(
            @PathVariable Long id,
            @Valid @RequestBody UpdateModerationTemplateRequest request) {
        ModerationTemplateResponse response = templateService.updateTemplate(
                id, request.getName(), request.getActionType(), request.getReason(),
                request.getTemplateText(), request.getLanguage(), request.getIsDefault());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * テンプレートを論理削除する。
     */
    @DeleteMapping("/moderation/templates/{id}")
    @Operation(summary = "対応テンプレート削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteTemplate(@PathVariable Long id) {
        templateService.deleteTemplate(id);
        return ResponseEntity.noContent().build();
    }

    // ========== 設定 ==========

    /**
     * 設定一覧を取得する。
     */
    @GetMapping("/moderation/settings")
    @Operation(summary = "モデレーション設定一覧取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<ModerationSettingsResponse>>> getSettings() {
        List<ModerationSettingsResponse> response = settingsService.getAllSettings();
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 設定を更新する。
     */
    @PutMapping("/moderation/settings/{key}")
    @Operation(summary = "モデレーション設定更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<ModerationSettingsResponse>> updateSetting(
            @PathVariable String key,
            @Valid @RequestBody UpdateSettingRequest request) {
        ModerationSettingsResponse response = settingsService.updateSetting(
                key, request.getSettingValue(), getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 設定変更履歴を取得する。
     */
    @GetMapping("/moderation/settings/history")
    @Operation(summary = "設定変更履歴取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<PagedResponse<SettingsHistoryResponse>> getSettingsHistory(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<SettingsHistoryResponse> history = settingsService.getSettingsHistory(PageRequest.of(page, size))
                .map(moderationExtMapper::toSettingsHistoryResponse);
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                history.getTotalElements(), history.getNumber(), history.getSize(), history.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(history.getContent(), meta));
    }
}
