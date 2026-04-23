package com.mannschaft.app.jobmatching.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.jobmatching.controller.dto.CreateJobPostingRequest;
import com.mannschaft.app.jobmatching.controller.dto.FeePreviewResponse;
import com.mannschaft.app.jobmatching.controller.dto.JobPostingResponse;
import com.mannschaft.app.jobmatching.controller.dto.JobPostingSummaryResponse;
import com.mannschaft.app.jobmatching.controller.dto.UpdateJobPostingRequest;
import com.mannschaft.app.jobmatching.entity.JobPostingEntity;
import com.mannschaft.app.jobmatching.enums.JobPostingStatus;
import com.mannschaft.app.jobmatching.fee.FeeBreakdown;
import com.mannschaft.app.jobmatching.fee.JobFeeCalculator;
import com.mannschaft.app.jobmatching.mapper.JobMapper;
import com.mannschaft.app.jobmatching.service.JobPostingService;
import com.mannschaft.app.jobmatching.service.command.CreateJobPostingCommand;
import com.mannschaft.app.jobmatching.service.command.UpdateJobPostingCommand;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 求人投稿コントローラー。
 *
 * <p>F13.1 Phase 13.1.1 MVP における求人の投稿・検索・公開・編集・募集終了・キャンセル・削除
 * および手数料プレビュー API を提供する。認可は {@link com.mannschaft.app.jobmatching.policy.JobPolicy}、
 * 状態遷移は {@code JobPostingStateMachine} に委譲する。</p>
 */
@RestController
@RequestMapping("/api/v1/jobs")
@Tag(name = "求人投稿")
@RequiredArgsConstructor
@Validated
public class JobPostingController {

    private final JobPostingService jobPostingService;
    private final JobFeeCalculator jobFeeCalculator;
    private final JobMapper jobMapper;

    // ========================================
    // 検索・取得
    // ========================================

    /**
     * 求人一覧を取得する。teamId 必須、status は任意指定。
     */
    @GetMapping
    @Operation(summary = "求人検索（チーム配下）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<PagedResponse<JobPostingSummaryResponse>> searchJobs(
            @RequestParam Long teamId,
            @RequestParam(required = false) JobPostingStatus status,
            Pageable pageable) {
        Page<JobPostingEntity> page = jobPostingService.listByTeam(teamId, status, pageable);
        var data = jobMapper.toPostingSummaryResponseList(page.getContent());
        var meta = new PagedResponse.PageMeta(
                page.getTotalElements(), page.getNumber(), page.getSize(), page.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(data, meta));
    }

    /**
     * 求人詳細を取得する。
     */
    @GetMapping("/{id}")
    @Operation(summary = "求人詳細取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<JobPostingResponse>> getJob(@PathVariable Long id) {
        JobPostingEntity entity = jobPostingService.findById(id);
        return ResponseEntity.ok(ApiResponse.of(jobMapper.toPostingResponse(entity)));
    }

    /**
     * 手数料プレビュー（UI の入力補助用）。
     *
     * <p>業務報酬（基本額）から Requester 支払総額・Worker 受取額の内訳を返す。
     * 認証のみで呼び出せる軽量 API（求人作成画面のリアルタイム表示を想定）。</p>
     */
    @GetMapping("/fee-preview")
    @Operation(summary = "手数料プレビュー")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<FeePreviewResponse>> previewFee(
            @RequestParam @Min(0) @Max(1_000_000) int baseRewardJpy) {
        FeeBreakdown breakdown = jobFeeCalculator.calculate(baseRewardJpy);
        return ResponseEntity.ok(ApiResponse.of(FeePreviewResponse.from(breakdown)));
    }

    // ========================================
    // 作成・更新
    // ========================================

    /**
     * 求人を新規投稿する（DRAFT 作成）。
     */
    @PostMapping
    @Operation(summary = "求人投稿作成（DRAFT）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<JobPostingResponse>> createJob(
            @Valid @RequestBody CreateJobPostingRequest req) {
        CreateJobPostingCommand cmd = new CreateJobPostingCommand(
                req.teamId(),
                req.title(),
                req.description(),
                req.category(),
                req.workLocationType(),
                req.workAddress(),
                req.workStartAt(),
                req.workEndAt(),
                req.rewardType(),
                req.baseRewardJpy(),
                req.capacity(),
                req.applicationDeadlineAt(),
                req.visibilityScope(),
                req.publishAt()
        );
        JobPostingEntity entity = jobPostingService.create(cmd, SecurityUtils.getCurrentUserId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.of(jobMapper.toPostingResponse(entity)));
    }

    /**
     * 求人を部分更新する。null のフィールドは現状維持（PATCH セマンティクス）。
     */
    @PatchMapping("/{id}")
    @Operation(summary = "求人投稿更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<JobPostingResponse>> updateJob(
            @PathVariable Long id,
            @Valid @RequestBody UpdateJobPostingRequest req) {
        UpdateJobPostingCommand cmd = new UpdateJobPostingCommand(
                req.title(),
                req.description(),
                req.category(),
                req.workLocationType(),
                req.workAddress(),
                req.workStartAt(),
                req.workEndAt(),
                req.rewardType(),
                req.baseRewardJpy(),
                req.capacity(),
                req.applicationDeadlineAt(),
                req.visibilityScope(),
                req.publishAt()
        );
        JobPostingEntity entity = jobPostingService.update(id, cmd, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(jobMapper.toPostingResponse(entity)));
    }

    // ========================================
    // 状態遷移
    // ========================================

    /**
     * 求人を公開する（DRAFT → OPEN）。
     */
    @PostMapping("/{id}/publish")
    @Operation(summary = "求人公開")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "公開成功")
    public ResponseEntity<ApiResponse<JobPostingResponse>> publishJob(@PathVariable Long id) {
        JobPostingEntity entity = jobPostingService.publish(id, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(jobMapper.toPostingResponse(entity)));
    }

    /**
     * 求人の募集を終了する（OPEN → CLOSED）。
     */
    @PostMapping("/{id}/close")
    @Operation(summary = "求人募集終了")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "終了成功")
    public ResponseEntity<ApiResponse<JobPostingResponse>> closeJob(@PathVariable Long id) {
        JobPostingEntity entity = jobPostingService.close(id, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(jobMapper.toPostingResponse(entity)));
    }

    /**
     * 求人をキャンセルする（DRAFT または OPEN → CANCELLED）。
     */
    @PostMapping("/{id}/cancel")
    @Operation(summary = "求人キャンセル")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "キャンセル成功")
    public ResponseEntity<ApiResponse<JobPostingResponse>> cancelJob(@PathVariable Long id) {
        JobPostingEntity entity = jobPostingService.cancel(id, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(jobMapper.toPostingResponse(entity)));
    }

    /**
     * 求人を論理削除する（応募者ゼロ件の場合のみ許容）。
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "求人論理削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteJob(@PathVariable Long id) {
        jobPostingService.delete(id, SecurityUtils.getCurrentUserId());
        return ResponseEntity.noContent().build();
    }
}
