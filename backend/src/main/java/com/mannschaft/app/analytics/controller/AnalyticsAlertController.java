package com.mannschaft.app.analytics.controller;

import com.mannschaft.app.analytics.DatePreset;
import com.mannschaft.app.analytics.dto.*;
import com.mannschaft.app.analytics.service.AnalyticsAggregationService;
import com.mannschaft.app.analytics.service.AnalyticsAlertService;
import com.mannschaft.app.analytics.service.AnalyticsBackfillService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.security.AuthorizedByPathConfig;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 経営分析ダッシュボード: アラート・バックフィル・スナップショット・レポート。
 *
 * <p><b>認可根拠（{@link AuthorizedByPathConfig} クラス付与・凍結ストア該当 8 EP）</b>:
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
@RequestMapping("/api/v1/system-admin/analytics")
@RequiredArgsConstructor
public class AnalyticsAlertController {

    private final AnalyticsAlertService alertService;
    private final AnalyticsBackfillService backfillService;
    private final AnalyticsAggregationService aggregationService;

    /** アラートルール一覧取得 */
    @GetMapping("/alerts")
    public ApiResponse<List<AlertRuleResponse>> getAlertRules() {
        return ApiResponse.of(alertService.getAllRules());
    }

    /** アラートルール作成 */
    @PostMapping("/alerts")
    public ResponseEntity<ApiResponse<AlertRuleResponse>> createAlertRule(
            @Valid @RequestBody CreateAlertRuleRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        AlertRuleResponse response = alertService.createRule(request, userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /** アラートルール更新 */
    @PutMapping("/alerts/{id}")
    public ApiResponse<AlertRuleResponse> updateAlertRule(
            @PathVariable Long id,
            @Valid @RequestBody UpdateAlertRuleRequest request) {
        return ApiResponse.of(alertService.updateRule(id, request));
    }

    /** アラートルール削除（論理削除） */
    @DeleteMapping("/alerts/{id}")
    public ResponseEntity<Void> deleteAlertRule(@PathVariable Long id) {
        alertService.deleteRule(id);
        return ResponseEntity.noContent().build();
    }

    /** アラート発火履歴 */
    @GetMapping("/alerts/history")
    public PagedResponse<AlertHistoryResponse> getAlertHistory(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) DatePreset preset,
            @RequestParam(required = false) Long rule_id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (from == null) from = LocalDate.now().minusMonths(1);
        if (to == null) to = LocalDate.now();
        int clampedSize = Math.min(size, 100);
        Page<AlertHistoryResponse> result = alertService.getHistory(from, to, rule_id, PageRequest.of(page, clampedSize));
        return PagedResponse.of(
            result.getContent(),
            new PagedResponse.PageMeta(result.getTotalElements(), page, clampedSize, result.getTotalPages())
        );
    }

    /** バックフィル（過去データ再集計） */
    @PostMapping("/backfill")
    public ResponseEntity<ApiResponse<BackfillJobResponse>> triggerBackfill(
            @Valid @RequestBody BackfillRequest request) {
        BackfillJobResponse response = backfillService.startBackfill(request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.of(response));
    }

    /** 月次KPIスナップショット一覧 */
    @GetMapping("/snapshots")
    public ApiResponse<List<KpiSnapshotResponse>> getSnapshots(
            @RequestParam(required = false) String from_month,
            @RequestParam(required = false) String to_month) {
        return ApiResponse.of(aggregationService.getSnapshots(from_month, to_month));
    }

    /** 月次レポート手動送信 */
    @PostMapping("/snapshots/{month}/send-report")
    public ApiResponse<Object> sendReport(
            @PathVariable String month,
            @Valid @RequestBody SendReportRequest request) {
        aggregationService.sendMonthlyReport(month, request.getRecipients());
        return ApiResponse.of(Map.of(
            "month", month,
            "sent_to", request.getRecipients(),
            "sent_at", LocalDateTime.now()
        ));
    }
}
