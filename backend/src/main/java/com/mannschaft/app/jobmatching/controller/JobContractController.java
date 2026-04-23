package com.mannschaft.app.jobmatching.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.jobmatching.controller.dto.CancelContractRequest;
import com.mannschaft.app.jobmatching.controller.dto.JobContractResponse;
import com.mannschaft.app.jobmatching.controller.dto.RejectCompletionRequest;
import com.mannschaft.app.jobmatching.controller.dto.ReportCompletionRequest;
import com.mannschaft.app.jobmatching.entity.JobContractEntity;
import com.mannschaft.app.jobmatching.mapper.JobMapper;
import com.mannschaft.app.jobmatching.service.JobContractService;
import com.mannschaft.app.jobmatching.service.command.ReportCompletionCommand;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 求人契約コントローラー。
 *
 * <p>F13.1 Phase 13.1.1 MVP における求人契約ライフサイクルの REST API を提供する。
 * 採用確定（応募 → 契約生成）自体は {@link JobApplicationController} の accept エンドポイントが担う。
 * 本コントローラは成立後の完了報告・承認・差し戻し・キャンセル・閲覧を扱う。</p>
 *
 * <p>エンドポイント一覧:</p>
 * <ul>
 *   <li>GET  /api/v1/me/contracts                        — 自分の契約一覧（Worker/Requester 兼用）</li>
 *   <li>GET  /api/v1/contracts/{id}                      — 契約詳細</li>
 *   <li>POST /api/v1/contracts/{id}/report-completion    — 完了報告（Worker）</li>
 *   <li>POST /api/v1/contracts/{id}/approve-completion   — 完了承認（Requester）</li>
 *   <li>POST /api/v1/contracts/{id}/reject-completion    — 完了差し戻し（Requester）</li>
 *   <li>POST /api/v1/contracts/{id}/cancel               — 契約キャンセル（Requester/Worker 本人）</li>
 * </ul>
 *
 * <p>認可・状態遷移・差し戻し上限は全て Service 層に委譲する（Controller は薄く保つ方針）。</p>
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "求人契約")
@RequiredArgsConstructor
@Validated
public class JobContractController {

    private final JobContractService contractService;
    private final JobMapper jobMapper;

    // ========================================
    // 一覧・詳細
    // ========================================

    /**
     * 自分が Worker または Requester として関与する契約一覧をページング取得する。
     *
     * <p>MVP では role/status フィルタは Service 側に未実装のため Controller でも受け付けない
     * （追加が必要になった時点で Service 層にフィルタ引数を拡張する方針）。</p>
     */
    @GetMapping("/me/contracts")
    @Operation(summary = "自分の契約一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<PagedResponse<JobContractResponse>> listMyContracts(Pageable pageable) {
        Page<JobContractEntity> page = contractService.listMyContracts(
                SecurityUtils.getCurrentUserId(), pageable);
        var data = jobMapper.toContractResponseList(page.getContent());
        var meta = new PagedResponse.PageMeta(
                page.getTotalElements(), page.getNumber(), page.getSize(), page.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(data, meta));
    }

    /**
     * 契約詳細を取得する。Requester または Worker 本人のみ閲覧可。
     */
    @GetMapping("/contracts/{id}")
    @Operation(summary = "契約詳細取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<JobContractResponse>> getContract(@PathVariable Long id) {
        JobContractEntity entity = contractService.findById(id, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(jobMapper.toContractResponse(entity)));
    }

    // ========================================
    // 完了フロー
    // ========================================

    /**
     * Worker が業務完了を報告する（MATCHED → COMPLETION_REPORTED）。
     */
    @PostMapping("/contracts/{id}/report-completion")
    @Operation(summary = "業務完了報告（Worker）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "報告成功")
    public ResponseEntity<ApiResponse<JobContractResponse>> reportCompletion(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) ReportCompletionRequest req) {
        String message = req != null ? req.message() : null;
        ReportCompletionCommand cmd = new ReportCompletionCommand(message);
        JobContractEntity entity = contractService.reportCompletion(id, cmd, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(jobMapper.toContractResponse(entity)));
    }

    /**
     * Requester が完了承認する（COMPLETION_REPORTED → COMPLETED）。
     */
    @PostMapping("/contracts/{id}/approve-completion")
    @Operation(summary = "完了承認（Requester）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "承認成功")
    public ResponseEntity<ApiResponse<JobContractResponse>> approveCompletion(@PathVariable Long id) {
        JobContractEntity entity = contractService.approveCompletion(id, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(jobMapper.toContractResponse(entity)));
    }

    /**
     * Requester が完了を差し戻す（COMPLETION_REPORTED → MATCHED、rejection_count + 1）。
     * 差し戻し上限（3 回）超過時は Service が {@code JOB_REJECTION_LIMIT_EXCEEDED} を送出する。
     */
    @PostMapping("/contracts/{id}/reject-completion")
    @Operation(summary = "完了差し戻し（Requester）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "差し戻し成功")
    public ResponseEntity<ApiResponse<JobContractResponse>> rejectCompletion(
            @PathVariable Long id,
            @Valid @RequestBody RejectCompletionRequest req) {
        JobContractEntity entity = contractService.rejectCompletion(id, req.reason(), SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(jobMapper.toContractResponse(entity)));
    }

    // ========================================
    // キャンセル
    // ========================================

    /**
     * 契約をキャンセルする。Requester または Worker 本人のみ許可。
     */
    @PostMapping("/contracts/{id}/cancel")
    @Operation(summary = "契約キャンセル")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "キャンセル成功")
    public ResponseEntity<ApiResponse<JobContractResponse>> cancelContract(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) CancelContractRequest req) {
        String reason = req != null ? req.reason() : null;
        JobContractEntity entity = contractService.cancelContract(id, SecurityUtils.getCurrentUserId(), reason);
        return ResponseEntity.ok(ApiResponse.of(jobMapper.toContractResponse(entity)));
    }
}
