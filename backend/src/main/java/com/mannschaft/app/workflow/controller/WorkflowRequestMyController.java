package com.mannschaft.app.workflow.controller;

import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.workflow.dto.WorkflowRequestResponse;
import com.mannschaft.app.workflow.service.WorkflowRequestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.mannschaft.app.common.security.SelfScopedEndpoint;

/**
 * 自分の申請一覧コントローラー。
 *
 * <p>F05.6 Phase 11 第二陣（2-γ）で追加。スコープを跨いで自分の申請を一覧する API を提供する。
 * フロントエンドの「マイ申請ページ」が依存する。</p>
 */
@RestController
@RequestMapping("/api/v1/workflow-requests")
@Tag(name = "ワークフロー申請（自分宛）", description = "F05.6 自分の申請横断一覧")
@RequiredArgsConstructor
public class WorkflowRequestMyController {

    private final WorkflowRequestService requestService;

    /**
     * 自分の申請を組織横断で一覧取得する。
     *
     * @param status ステータスフィルタ（任意。{@code DRAFT} / {@code PENDING} / {@code IN_PROGRESS} /
     *               {@code APPROVED} / {@code REJECTED} / {@code WITHDRAWN} / {@code CANCELLED}）
     * @param page   ページ番号（0 始まり）
     * @param size   1 ページあたりの件数
     * @return 申請レスポンスのページ
     */
    @SelfScopedEndpoint("WorkflowRequestService#listMyRequests は"
        + "findByRequestedByOrderByCreatedAtDesc(SecurityUtils.getCurrentUserId())"
        + " のみを組織横断で検索する")
    @GetMapping("/me")
    @Operation(summary = "自分の申請一覧（組織横断）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<PagedResponse<WorkflowRequestResponse>> listMyRequests(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        Page<WorkflowRequestResponse> result = requestService.listMyRequests(
                currentUserId, status, PageRequest.of(page, size));
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                result.getTotalElements(), result.getNumber(), result.getSize(), result.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(result.getContent(), meta));
    }
}
