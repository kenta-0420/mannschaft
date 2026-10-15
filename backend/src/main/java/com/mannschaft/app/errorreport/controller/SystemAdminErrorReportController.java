package com.mannschaft.app.errorreport.controller;

import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.security.AuthorizedByPathConfig;
import com.mannschaft.app.common.storage.MediaUrlResolver;
import com.mannschaft.app.errorreport.ErrorReportMapper;
import com.mannschaft.app.errorreport.ErrorReportProperties;
import com.mannschaft.app.errorreport.dto.AssignableUserResponse;
import com.mannschaft.app.errorreport.dto.ErrorReportAiAnalysisResponse;
import com.mannschaft.app.errorreport.dto.ErrorReportAssigneeRequest;
import com.mannschaft.app.errorreport.dto.ErrorReportBulkUpdateRequest;
import com.mannschaft.app.errorreport.dto.ErrorReportCommentRequest;
import com.mannschaft.app.errorreport.dto.ErrorReportConfigResponse;
import com.mannschaft.app.errorreport.dto.ErrorReportResponse;
import com.mannschaft.app.errorreport.dto.ErrorReportStatsResponse;
import com.mannschaft.app.errorreport.dto.ErrorReportTimelineResponse;
import com.mannschaft.app.errorreport.dto.ErrorReportUpdateRequest;
import com.mannschaft.app.errorreport.dto.ErrorReportWorkflowStageRequest;
import com.mannschaft.app.errorreport.dto.GitHubIssueCreateResponse;
import com.mannschaft.app.errorreport.dto.KanbanResponse;
import com.mannschaft.app.errorreport.entity.ErrorReportAiAnalysisEntity;
import com.mannschaft.app.errorreport.entity.ErrorReportEntity;
import com.mannschaft.app.errorreport.repository.ErrorReportAiAnalysisRepository;
import com.mannschaft.app.errorreport.service.ErrorReportAiAnalysisService;
import com.mannschaft.app.errorreport.service.ErrorReportKanbanService;
import com.mannschaft.app.errorreport.service.ErrorReportQueryService;
import com.mannschaft.app.errorreport.service.ErrorReportService;
import com.mannschaft.app.errorreport.service.ErrorReportTimelineService;
import com.mannschaft.app.errorreport.service.GitHubIssueService;
import com.mannschaft.app.role.repository.UserRoleRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * システム管理者向けエラーレポート管理コントローラー。
 *
 * <p><b>認可根拠（{@link AuthorizedByPathConfig} クラス付与・凍結ストア該当 5 EP）</b>:
 * 本 Controller の全 Mapping エンドポイントは、{@code SecurityConfig} のパス単位認可により
 * SYSTEM_ADMIN ロール保持者のみへ宣言的に予約されている。</p>
 *
 * <p><b>根拠</b>:
 * SecurityConfig の requestMatchers("/api/v1/system-admin/**").hasRole("SYSTEM_ADMIN")
 * </p>
 *
 * <p>Controller / Service 側に認可コードは存在しないが、フィルタチェーンで強制されるため
 * 無認可ではない。認可根治戦役 Wave5 監査済。パス定義を変更・削除する際は本注釈の根拠が
 * 失効するため、必ず併せて見直すこと。</p>
 */
@AuthorizedByPathConfig("/api/v1/system-admin/**")
@RestController
@RequestMapping("/api/v1/system-admin/error-reports")
@Tag(name = "システム管理 - エラーレポート", description = "F12.5 エラーレポート管理API（システム管理者向け）")
@RequiredArgsConstructor
public class SystemAdminErrorReportController {

    private final ErrorReportService errorReportService;
    private final ErrorReportQueryService errorReportQueryService;
    private final ErrorReportKanbanService errorReportKanbanService;
    private final ErrorReportTimelineService errorReportTimelineService;
    private final ErrorReportMapper errorReportMapper;
    private final AccessControlService accessControlService;
    private final ErrorReportAiAnalysisService aiAnalysisService;
    private final ErrorReportAiAnalysisRepository aiAnalysisRepository;
    private final GitHubIssueService gitHubIssueService;
    private final ErrorReportProperties errorReportProperties;
    /** F10.6 Phase 10-δ — 担当者候補一覧取得用。 */
    private final UserRoleRepository userRoleRepository;
    private final UserRepository userRepository;
    private final MediaUrlResolver mediaUrlResolver;

    @Value("${mannschaft.claude.api-key:}")
    private String claudeApiKey;

    /**
     * エラーレポート一覧を取得する（ページネーション・フィルタ付き）。
     */
    @GetMapping
    @Operation(summary = "エラーレポート一覧取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<PagedResponse<ErrorReportResponse>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(defaultValue = "false") boolean overdueOnly,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "lastOccurredAt,desc") String sort) {
        int cappedSize = Math.min(size, 100);
        Pageable pageable = buildPageable(page, cappedSize, sort);
        Page<ErrorReportEntity> result = errorReportQueryService.search(status, severity, from, to, overdueOnly, pageable);
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                result.getTotalElements(), result.getNumber(), result.getSize(), result.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(
                errorReportMapper.toResponseList(result.getContent()), meta));
    }

    /**
     * エラーレポート詳細を取得する。
     */
    @GetMapping("/{id}")
    @Operation(summary = "エラーレポート詳細取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<ErrorReportResponse>> get(@PathVariable Long id) {
        ErrorReportEntity entity = errorReportQueryService.findById(id);
        ErrorReportResponse response = errorReportMapper.toResponse(entity);
        // F12.5 Phase 2-C — 最新 SUCCESS の AI 分析サマリを埋める
        aiAnalysisRepository.findFirstByErrorReportIdAndStatusOrderByCreatedAtDesc(id, "SUCCESS")
                .ifPresent(latest -> response.setLatestAiAnalysis(toSummary(latest)));
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * エラーレポートのステータス・重要度・管理者メモを更新する。
     */
    @PatchMapping("/{id}")
    @Operation(summary = "エラーレポート更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<ErrorReportResponse>> update(
            @PathVariable Long id,
            @Valid @RequestBody ErrorReportUpdateRequest request) {
        Long adminId = SecurityUtils.getCurrentUserId();
        ErrorReportEntity entity = errorReportService.updateStatus(id, request, adminId);
        return ResponseEntity.ok(ApiResponse.of(errorReportMapper.toResponse(entity)));
    }

    /**
     * エラーレポートを一括でステータス更新する。
     */
    @PatchMapping("/bulk")
    @Operation(summary = "エラーレポート一括更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<Map<String, Integer>> bulkUpdate(
            @Valid @RequestBody ErrorReportBulkUpdateRequest request) {
        int count = errorReportService.bulkUpdate(request);
        return ResponseEntity.ok(Map.of("updated_count", count));
    }

    /**
     * エラーレポート統計情報を取得する。
     */
    @GetMapping("/stats")
    @Operation(summary = "エラーレポート統計取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<ErrorReportStatsResponse>> stats() {
        return ResponseEntity.ok(ApiResponse.of(errorReportQueryService.getStats()));
    }

    // ========================================
    // F12.5 Phase 2-E — Kanban ビュー
    // ========================================

    /**
     * F12.5 Phase 2-E — Kanban ビュー（6 カラム）を取得する。
     * 各カラム最大 50 件、{@code last_occurred_at DESC}。IGNORED は対象外。
     */
    @GetMapping("/kanban")
    @Operation(summary = "Kanban ビュー取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<KanbanResponse>> kanban() {
        accessControlService.checkSystemAdmin(SecurityUtils.getCurrentUserId());
        KanbanResponse response = errorReportKanbanService.fetchKanban();
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    // ========================================
    // F12.5 Phase 2 — ワークフロー / 担当者 / コメント / タイムライン
    // ========================================

    /**
     * F12.5 Phase 2 — エラーレポートのワークフロー段階を更新する。
     */
    @PatchMapping("/{id}/workflow-stage")
    @Operation(summary = "ワークフロー段階更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<ErrorReportResponse>> updateWorkflowStage(
            @PathVariable Long id,
            @Valid @RequestBody ErrorReportWorkflowStageRequest req) {
        Long actorId = SecurityUtils.getCurrentUserId();
        ErrorReportEntity entity = errorReportTimelineService.updateWorkflowStage(id, req.getWorkflowStage(), actorId);
        return ResponseEntity.ok(ApiResponse.of(errorReportMapper.toResponse(entity)));
    }

    /**
     * F12.5 Phase 2 — 担当者を割り当て/解除する。
     */
    @PatchMapping("/{id}/assignee")
    @Operation(summary = "担当者割り当て/解除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<ErrorReportResponse>> assign(
            @PathVariable Long id,
            @Valid @RequestBody ErrorReportAssigneeRequest req) {
        Long actorId = SecurityUtils.getCurrentUserId();
        ErrorReportEntity entity = errorReportTimelineService.assign(id, req.getAssigneeId(), actorId);
        return ResponseEntity.ok(ApiResponse.of(errorReportMapper.toResponse(entity)));
    }

    /**
     * F12.5 Phase 2 — 管理者コメントを追加する。
     */
    @PostMapping("/{id}/comments")
    @Operation(summary = "コメント追加")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "追加成功")
    public ResponseEntity<ApiResponse<Void>> addComment(
            @PathVariable Long id,
            @Valid @RequestBody ErrorReportCommentRequest req) {
        Long actorId = SecurityUtils.getCurrentUserId();
        errorReportTimelineService.addComment(id, req.getContent(), actorId);
        return ResponseEntity.ok(ApiResponse.of(null));
    }

    /**
     * F12.5 Phase 2 — タイムライン（occurrences + activities）を取得する。
     */
    @GetMapping("/{id}/timeline")
    @Operation(summary = "タイムライン取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<ErrorReportTimelineResponse>> timeline(
            @PathVariable Long id,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int limit) {
        accessControlService.checkSystemAdmin(SecurityUtils.getCurrentUserId());
        int cappedLimit = Math.min(Math.max(limit, 1), 100);
        ErrorReportTimelineResponse response = errorReportTimelineService.fetchTimeline(id, cursor, cappedLimit);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    // ========================================
    // F12.5 Phase 2-C — AI 分析
    // ========================================

    /**
     * F12.5 Phase 2-C — AI 再分析を即時実行する。
     */
    @PostMapping("/{id}/ai-analyses")
    @Operation(summary = "AI 再分析実行")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "分析成功")
    public ResponseEntity<ApiResponse<ErrorReportAiAnalysisResponse>> reanalyze(
            @PathVariable Long id) {
        Long actorId = SecurityUtils.getCurrentUserId();
        accessControlService.checkSystemAdmin(actorId);
        ErrorReportAiAnalysisEntity entity = aiAnalysisService.analyzeSync(id, actorId);
        return ResponseEntity.ok(ApiResponse.of(toResponse(entity)));
    }

    /**
     * F12.5 Phase 2-C — AI 分析履歴を取得する。
     */
    @GetMapping("/{id}/ai-analyses")
    @Operation(summary = "AI 分析履歴取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<PagedResponse<ErrorReportAiAnalysisResponse>> aiAnalyses(
            @PathVariable Long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        accessControlService.checkSystemAdmin(SecurityUtils.getCurrentUserId());
        int cappedSize = Math.min(Math.max(size, 1), 50);
        Page<ErrorReportAiAnalysisEntity> result = aiAnalysisRepository
                .findByErrorReportIdOrderByCreatedAtDesc(id, PageRequest.of(page, cappedSize));
        java.util.List<ErrorReportAiAnalysisResponse> data = result.getContent().stream()
                .map(this::toResponse)
                .toList();
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                result.getTotalElements(), result.getNumber(), result.getSize(), result.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(data, meta));
    }

    // ========================================
    // F12.5 Phase 2-D — GitHub Issue 連携
    // ========================================

    /**
     * F12.5 Phase 2-D — GitHub Issue を作成し、エラーレポートに URL を保存する。
     */
    @PostMapping("/{id}/github-issue")
    @Operation(summary = "GitHub Issue 作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "作成成功")
    public ResponseEntity<ApiResponse<GitHubIssueCreateResponse>> createGithubIssue(@PathVariable Long id) {
        Long actorId = SecurityUtils.getCurrentUserId();
        accessControlService.checkSystemAdmin(actorId);
        String url = gitHubIssueService.createIssue(id, actorId);
        return ResponseEntity.ok(ApiResponse.of(
                GitHubIssueCreateResponse.builder().url(url).build()));
    }

    /**
     * F12.5 Phase 2-D — エラーレポート機能の運用設定（GitHub/AI 有効状態）を返す。
     * フロントエンドのボタン状態判定に使用される。
     */
    @GetMapping("/config")
    @Operation(summary = "エラーレポート機能設定取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<ErrorReportConfigResponse>> config() {
        accessControlService.checkSystemAdmin(SecurityUtils.getCurrentUserId());
        boolean aiEnabled = errorReportProperties.getAi().isEnabled()
                && claudeApiKey != null && !claudeApiKey.isBlank();
        ErrorReportConfigResponse response = ErrorReportConfigResponse.builder()
                .githubEnabled(gitHubIssueService.isAvailable())
                .aiEnabled(aiEnabled)
                .aiModel(errorReportProperties.getAi().getModel())
                .aiMonthlyBudgetJpy(errorReportProperties.getAi().getMonthlyBudgetJpy())
                .build();
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * F10.6 Phase 10-δ — 担当者候補となる SYSTEM_ADMIN ユーザー一覧を返す。
     * ErrorReportAssigneeSelector のドロップダウン表示用。
     */
    @GetMapping("/assignable-users")
    @Operation(summary = "担当者候補一覧取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<AssignableUserResponse>>> assignableUsers() {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        accessControlService.checkSystemAdmin(currentUserId);

        List<Long> adminIds = userRoleRepository.findSystemAdminUserIds();
        List<AssignableUserResponse> users = userRepository.findAllById(adminIds).stream()
                .map(u -> AssignableUserResponse.builder()
                        .id(u.getId())
                        .displayName(u.getDisplayName())
                        .profileImageUrl(mediaUrlResolver.resolve(u.getAvatarUrl()))
                        .build())
                .sorted(Comparator.comparing(AssignableUserResponse::getDisplayName,
                        Comparator.nullsLast(String::compareTo)))
                .toList();
        return ResponseEntity.ok(ApiResponse.of(users));
    }

    /**
     * Entity → Response 変換。
     */
    private ErrorReportAiAnalysisResponse toResponse(ErrorReportAiAnalysisEntity entity) {
        java.util.List<String> files = entity.getSuggestedFiles() != null && !entity.getSuggestedFiles().isBlank()
                ? java.util.Arrays.stream(entity.getSuggestedFiles().split(","))
                    .map(String::trim).filter(s -> !s.isEmpty()).toList()
                : java.util.List.of();
        return ErrorReportAiAnalysisResponse.builder()
                .id(entity.getId())
                .errorReportId(entity.getErrorReportId())
                .modelName(entity.getModelName())
                .promptTokens(entity.getPromptTokens() != null ? entity.getPromptTokens() : 0)
                .completionTokens(entity.getCompletionTokens() != null ? entity.getCompletionTokens() : 0)
                .estimatedCause(entity.getEstimatedCause())
                .fixProposal(entity.getFixProposal())
                .impactAssessment(entity.getImpactAssessment())
                .suggestedFiles(files)
                .status(entity.getStatus())
                .errorMessage(entity.getErrorMessage())
                .createdBy(entity.getCreatedBy())
                .createdAt(entity.getCreatedAt())
                .build();
    }

    /**
     * Entity → ErrorReportResponse の latestAiAnalysis サマリ変換。
     */
    private ErrorReportResponse.ErrorReportAiAnalysisSummary toSummary(ErrorReportAiAnalysisEntity entity) {
        return ErrorReportResponse.ErrorReportAiAnalysisSummary.builder()
                .id(entity.getId())
                .estimatedCause(entity.getEstimatedCause())
                .fixProposal(entity.getFixProposal())
                .impactAssessment(entity.getImpactAssessment())
                .suggestedFiles(entity.getSuggestedFiles())
                .createdAt(entity.getCreatedAt())
                .build();
    }

    /**
     * sort パラメータ文字列から Pageable を構築する。
     * 形式: "field,direction" (例: "lastOccurredAt,desc")
     */
    private Pageable buildPageable(int page, int size, String sort) {
        String[] parts = sort.split(",");
        if (parts.length == 2) {
            Sort.Direction direction = Sort.Direction.fromOptionalString(parts[1].trim())
                    .orElse(Sort.Direction.DESC);
            return PageRequest.of(page, size, Sort.by(direction, parts[0].trim()));
        }
        return PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "lastOccurredAt"));
    }
}
