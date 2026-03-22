package com.mannschaft.app.moderation.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.moderation.dto.CreateInternalNoteRequest;
import com.mannschaft.app.moderation.dto.InternalNoteResponse;
import com.mannschaft.app.moderation.dto.ModerationTemplateResponse;
import com.mannschaft.app.moderation.dto.ReviewReReviewRequest;
import com.mannschaft.app.moderation.dto.UserViolationHistoryResponse;
import com.mannschaft.app.moderation.dto.WarningReReviewResponse;
import com.mannschaft.app.moderation.service.ModerationTemplateService;
import com.mannschaft.app.moderation.service.ReportInternalNoteService;
import com.mannschaft.app.moderation.service.UserViolationService;
import com.mannschaft.app.moderation.service.WarningReReviewService;
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

import java.util.List;

/**
 * ADMIN向け違反管理コントローラー。ユーザー違反履歴・内部メモ・再レビュー・テンプレートAPIを提供する。
 */
@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "ADMIN違反管理", description = "F10.2 ADMIN向けモデレーション拡張")
@RequiredArgsConstructor
public class AdminViolationController {

    private final UserViolationService violationService;
    private final ReportInternalNoteService noteService;
    private final WarningReReviewService reReviewService;
    private final ModerationTemplateService templateService;

    // TODO: JwtAuthenticationFilter実装時にSecurityContextHolderから取得に変更
    private Long getCurrentUserId() {
        return 1L;
    }

    /**
     * ユーザー違反履歴を取得する。
     */
    @GetMapping("/users/{id}/violations")
    @Operation(summary = "ユーザー違反履歴取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<UserViolationHistoryResponse>> getUserViolations(@PathVariable Long id) {
        UserViolationHistoryResponse response = violationService.getViolationHistory(id);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * コンテンツを一時非表示にする。
     */
    @PatchMapping("/reports/{id}/hide-content")
    @Operation(summary = "コンテンツ一時非表示")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "非表示成功")
    public ResponseEntity<ApiResponse<Void>> hideContent(@PathVariable Long id) {
        // TODO: ContentReportService経由でコンテンツの非表示フラグを更新
        return ResponseEntity.ok(ApiResponse.of(null));
    }

    /**
     * コンテンツの非表示を解除する。
     */
    @PatchMapping("/reports/{id}/unhide-content")
    @Operation(summary = "非表示解除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "解除成功")
    public ResponseEntity<ApiResponse<Void>> unhideContent(@PathVariable Long id) {
        // TODO: ContentReportService経由でコンテンツの非表示フラグを解除
        return ResponseEntity.ok(ApiResponse.of(null));
    }

    /**
     * 内部メモを追加する。
     */
    @PostMapping("/reports/{id}/notes")
    @Operation(summary = "内部メモ追加")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "追加成功")
    public ResponseEntity<ApiResponse<InternalNoteResponse>> addNote(
            @PathVariable Long id,
            @Valid @RequestBody CreateInternalNoteRequest request) {
        InternalNoteResponse response = noteService.addNote(id, getCurrentUserId(), request.getNote());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * 内部メモ一覧を取得する。
     */
    @GetMapping("/reports/{id}/notes")
    @Operation(summary = "内部メモ一覧取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<InternalNoteResponse>>> getNotes(@PathVariable Long id) {
        List<InternalNoteResponse> response = noteService.getNotes(id);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * ADMIN再レビュー判定。
     */
    @PatchMapping("/warning-re-reviews/{id}/review")
    @Operation(summary = "ADMIN再レビュー判定")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "判定成功")
    public ResponseEntity<ApiResponse<WarningReReviewResponse>> reviewReReview(
            @PathVariable Long id,
            @Valid @RequestBody ReviewReReviewRequest request) {
        WarningReReviewResponse response = reReviewService.adminReview(
                id, request.getStatus(), request.getReviewNote(), getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 対応テンプレート一覧を取得する。
     */
    @GetMapping("/moderation/templates")
    @Operation(summary = "対応テンプレート一覧取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<ModerationTemplateResponse>>> getTemplates() {
        List<ModerationTemplateResponse> response = templateService.getAllTemplates();
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
