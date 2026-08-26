package com.mannschaft.app.admin.controller;

import com.mannschaft.app.admin.dto.FeedbackRespondRequest;
import com.mannschaft.app.admin.dto.FeedbackResponse;
import com.mannschaft.app.admin.dto.FeedbackStatusRequest;
import com.mannschaft.app.admin.AdminFeedbackErrorCode;
import com.mannschaft.app.admin.service.FeedbackService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.PagedResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.mannschaft.app.common.SecurityUtils;

/**
 * 管理者向けフィードバック管理コントローラー。
 */
@RestController
@RequestMapping("/api/v1/admin/feedbacks")
@Tag(name = "管理 - フィードバック", description = "F10.1 フィードバック管理API（管理者向け）")
@RequiredArgsConstructor
public class AdminFeedbackController {

    private final FeedbackService feedbackService;
    private final AccessControlService accessControlService;

    /**
     * フィードバック一覧を取得する。
     */
    @GetMapping
    @Operation(summary = "フィードバック一覧取得（管理者向け）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<PagedResponse<FeedbackResponse>> getFeedbacks(
            @RequestParam String scopeType,
            @RequestParam Long scopeId,
            @RequestParam(required = false) String status,
            Pageable pageable) {
        // 認可根治 Wave5: 宣言された scope に対して ADMIN/DEPUTY_ADMIN を要求する（非メンバーは 403）。
        // 追込: 認可の前に scopeType を検証し、不正値による ScopeType.valueOf の
        // IllegalArgumentException（未処理 500）を 400 へ正規化する。
        AdminScopeTypeValidator.requireSupportedScopeType(scopeType);
        accessControlService.checkAdminOrAbove(SecurityUtils.getCurrentUserId(), scopeId, scopeType);
        Page<FeedbackResponse> page = feedbackService.getFeedbacks(scopeType, scopeId, status, pageable);
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                page.getTotalElements(), page.getNumber(), page.getSize(), page.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(page.getContent(), meta));
    }

    /**
     * フィードバックに回答する。
     */
    @PatchMapping("/{id}/respond")
    @Operation(summary = "フィードバック回答")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "回答成功")
    public ResponseEntity<ApiResponse<FeedbackResponse>> respondToFeedback(
            @PathVariable Long id,
            @Valid @RequestBody FeedbackRespondRequest request) {
        authorizeByEntityScope(id);
        FeedbackResponse response = feedbackService.respondToFeedback(id, request, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * フィードバックのステータスを変更する。
     */
    @PatchMapping("/{id}/status")
    @Operation(summary = "フィードバックステータス変更")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "変更成功")
    public ResponseEntity<ApiResponse<FeedbackResponse>> updateFeedbackStatus(
            @PathVariable Long id,
            @Valid @RequestBody FeedbackStatusRequest request) {
        authorizeByEntityScope(id);
        FeedbackResponse response = feedbackService.updateFeedbackStatus(id, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * entity 由来の scope でフィードバック操作を認可する（BOLA 対策）。
     *
     * <p>認可根治 Wave5: {@code respond} / {@code status} は ID のみを引数に取るため、
     * 引数の scope を信用すると別スコープの ADMIN が他スコープのフィードバックを
     * 回答・ステータス変更できてしまう。そこで entity から scope を読み直して認可する。</p>
     *
     * <p>権限が無い場合・GENERAL スコープ（scopeId が null＝プラットフォーム全体宛て。
     * {@code SystemAdminFeedbackController} の管轄）の場合は、403 ではなく
     * <b>404（{@code ADMIN_FB_003}）で存在を秘匿</b>する。403 を返すと「その ID の
     * フィードバックは存在する」ことが判明し、ID 総当たりによる存在推測を許すため。</p>
     *
     * @param id フィードバックID
     */
    private void authorizeByEntityScope(Long id) {
        FeedbackService.FeedbackScopeRef scope = feedbackService.getFeedbackScope(id);
        // GENERAL 等 per-scope でない種別・scopeId 欠落は本 Controller の管轄外。
        // isAdminOrAbove へ渡すと ScopeType.valueOf が例外になるため、先に 404 で秘匿する。
        // 追込: ホワイトリストのベタ書きを共通ヘルパーへ集約（ScopeType enum と常に一致させる）。
        boolean perScope = scope.scopeId() != null
                && AdminScopeTypeValidator.isSupportedScopeType(scope.scopeType());
        if (!perScope
                || !accessControlService.isAdminOrAbove(
                        SecurityUtils.getCurrentUserId(), scope.scopeId(), scope.scopeType())) {
            throw new BusinessException(AdminFeedbackErrorCode.FEEDBACK_NOT_FOUND);
        }
    }
}
