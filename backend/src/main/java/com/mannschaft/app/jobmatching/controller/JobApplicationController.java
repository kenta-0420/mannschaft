package com.mannschaft.app.jobmatching.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.jobmatching.controller.dto.ApplyRequest;
import com.mannschaft.app.jobmatching.controller.dto.JobApplicationResponse;
import com.mannschaft.app.jobmatching.controller.dto.JobContractResponse;
import com.mannschaft.app.jobmatching.controller.dto.RejectApplicationRequest;
import com.mannschaft.app.jobmatching.entity.JobApplicationEntity;
import com.mannschaft.app.jobmatching.entity.JobContractEntity;
import com.mannschaft.app.jobmatching.mapper.JobMapper;
import com.mannschaft.app.jobmatching.service.JobApplicationService;
import com.mannschaft.app.jobmatching.service.JobContractService;
import com.mannschaft.app.jobmatching.service.command.ApplyCommand;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 求人応募コントローラー。
 *
 * <p>F13.1 Phase 13.1.1 MVP における求人応募ライフサイクルの REST API を提供する。
 * 応募申込・取り下げ・不採用・一覧取得は {@link JobApplicationService} に委譲し、
 * 採用確定（応募 → 契約成立）は責務分離の観点から {@link JobContractService#acceptApplication(Long, Long)}
 * に委譲する。</p>
 *
 * <p>エンドポイント一覧（path は設計書およびタスク指示書に準拠）:</p>
 * <ul>
 *   <li>GET  /api/v1/jobs/{jobId}/applications      — 求人への応募一覧（Requester 視点）</li>
 *   <li>POST /api/v1/jobs/{jobId}/apply             — 応募する（Worker 視点）</li>
 *   <li>GET  /api/v1/me/applications                — 自分の応募履歴（Worker 視点）</li>
 *   <li>GET  /api/v1/applications/{id}              — 応募詳細</li>
 *   <li>POST /api/v1/applications/{id}/accept       — 応募受理（契約生成）</li>
 *   <li>POST /api/v1/applications/{id}/reject       — 応募却下</li>
 *   <li>POST /api/v1/applications/{id}/withdraw     — 応募取り下げ</li>
 * </ul>
 *
 * <p>認可・状態遷移・定員ロックは全て Service 層に委譲する（Controller は薄く保つ方針）。</p>
 */
@RestController
@Tag(name = "求人応募")
@RequiredArgsConstructor
@Validated
public class JobApplicationController {

    private final JobApplicationService applicationService;
    private final JobContractService contractService;
    private final JobMapper jobMapper;

    // ========================================
    // 求人配下の応募操作
    // ========================================

    /**
     * 求人の応募一覧を取得する。採否権限者（Requester/ADMIN 等）のみ閲覧可。
     *
     * <p>Service 層は現状 {@link java.util.List} 返却のため、ページネーションはメモリ上で単純切り分ける
     * 暫定実装（応募数が膨大になる規模ではないため MVP では許容）。</p>
     */
    @GetMapping("/api/v1/jobs/{jobId}/applications")
    @Operation(summary = "求人応募一覧（Requester視点）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<PagedResponse<JobApplicationResponse>> listByPosting(
            @PathVariable Long jobId,
            Pageable pageable) {
        List<JobApplicationEntity> all = applicationService.listByPosting(jobId, SecurityUtils.getCurrentUserId());

        int total = all.size();
        int pageNumber = pageable.getPageNumber();
        int pageSize = pageable.getPageSize();
        int from = Math.min(pageNumber * pageSize, total);
        int to = Math.min(from + pageSize, total);
        List<JobApplicationEntity> slice = all.subList(from, to);
        int totalPages = pageSize == 0 ? 0 : (int) Math.ceil((double) total / pageSize);

        var data = jobMapper.toApplicationResponseList(slice);
        var meta = new PagedResponse.PageMeta(total, pageNumber, pageSize, totalPages);
        return ResponseEntity.ok(PagedResponse.of(data, meta));
    }

    /**
     * 求人に応募する。
     *
     * <p>定員上限の厳密判定は採用確定時（{@code accept}）のみで行われるため、
     * ここでは早期の重複チェック・権限チェックのみ行う。応募成立で {@code JOB_APPLIED} 通知が Requester に飛ぶ。</p>
     */
    @PostMapping("/api/v1/jobs/{jobId}/apply")
    @Operation(summary = "応募する（Worker視点）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "応募成功")
    public ResponseEntity<ApiResponse<JobApplicationResponse>> apply(
            @PathVariable Long jobId,
            @Valid @RequestBody ApplyRequest req) {
        ApplyCommand cmd = new ApplyCommand(req.selfPr());
        JobApplicationEntity entity = applicationService.apply(jobId, cmd, SecurityUtils.getCurrentUserId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.of(jobMapper.toApplicationResponse(entity)));
    }

    // ========================================
    // 自分の応募履歴
    // ========================================

    /**
     * 自分の応募履歴をページング取得する。
     */
    @GetMapping("/api/v1/me/applications")
    @Operation(summary = "自分の応募履歴")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<PagedResponse<JobApplicationResponse>> listMyApplications(Pageable pageable) {
        Page<JobApplicationEntity> page = applicationService.listMyApplications(
                SecurityUtils.getCurrentUserId(), pageable);
        var data = jobMapper.toApplicationResponseList(page.getContent());
        var meta = new PagedResponse.PageMeta(
                page.getTotalElements(), page.getNumber(), page.getSize(), page.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(data, meta));
    }

    // ========================================
    // 応募個別操作
    // ========================================

    /**
     * 応募詳細を取得する。
     *
     * <p>MVP では Service 側で閲覧権限チェックを行わないため、認証済みであれば参照可能。
     * 後続 Phase で応募者本人または Requester/ADMIN のみに絞るリファクタを Service 層に加える予定。</p>
     */
    @GetMapping("/api/v1/applications/{id}")
    @Operation(summary = "応募詳細取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<JobApplicationResponse>> getApplication(@PathVariable Long id) {
        JobApplicationEntity entity = applicationService.findById(id);
        return ResponseEntity.ok(ApiResponse.of(jobMapper.toApplicationResponse(entity)));
    }

    /**
     * 応募を採用確定する（APPLIED → ACCEPTED、契約レコード生成）。
     *
     * <p>認可・状態遷移チェック・GET_LOCK による同時採用排他は
     * {@link JobContractService#acceptApplication(Long, Long)} が実施する。
     * レスポンスは生成された契約（{@link JobContractResponse}）。応募を返すと
     * 「採用確定の結果として契約成立」という意味が伝わりにくいためこの設計とした。</p>
     */
    @PostMapping("/api/v1/applications/{id}/accept")
    @Operation(summary = "応募を採用確定（契約生成）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "採用成功・契約成立")
    public ResponseEntity<ApiResponse<JobContractResponse>> acceptApplication(@PathVariable Long id) {
        JobContractEntity contract = contractService.acceptApplication(id, SecurityUtils.getCurrentUserId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.of(jobMapper.toContractResponse(contract)));
    }

    /**
     * 応募を不採用にする（APPLIED → REJECTED）。採否権限者のみ許可。
     */
    @PostMapping("/api/v1/applications/{id}/reject")
    @Operation(summary = "応募を不採用")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "不採用成功")
    public ResponseEntity<ApiResponse<JobApplicationResponse>> rejectApplication(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) RejectApplicationRequest req) {
        String reason = req != null ? req.reason() : null;
        JobApplicationEntity entity = applicationService.reject(id, reason, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(jobMapper.toApplicationResponse(entity)));
    }

    /**
     * 応募を取り下げる（APPLIED → WITHDRAWN）。応募者本人のみ許可。
     */
    @PostMapping("/api/v1/applications/{id}/withdraw")
    @Operation(summary = "応募を取り下げ")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取り下げ成功")
    public ResponseEntity<ApiResponse<JobApplicationResponse>> withdrawApplication(@PathVariable Long id) {
        JobApplicationEntity entity = applicationService.withdraw(id, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(jobMapper.toApplicationResponse(entity)));
    }
}
