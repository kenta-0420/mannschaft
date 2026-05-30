package com.mannschaft.app.inbox.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.inbox.InboxPriority;
import com.mannschaft.app.inbox.InboxSourceType;
import com.mannschaft.app.inbox.dto.InboxItemDto;
import com.mannschaft.app.inbox.dto.InboxPageResponse;
import com.mannschaft.app.inbox.dto.InboxSummaryResponse;
import com.mannschaft.app.inbox.dto.SnoozeInboxRequest;
import com.mannschaft.app.inbox.dto.TriageTargetRequest;
import com.mannschaft.app.inbox.service.InboxAggregationService;
import com.mannschaft.app.inbox.service.InboxTriageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * F04.11 統合通知インボックス — コントローラー（MVP）。
 *
 * <p>設計書: {@code docs/features/F04.11_notification_inbox/02_api_design.md} §2。
 * 全 EP 認証必須・{@code SecurityUtils.getCurrentUserId()} で本人データのみ操作する（IDOR 防止）。
 * ラッパは {@code ApiResponse}。triage 操作の対象指定は {@code (sourceType, sourceId)} の複合論理キー。</p>
 *
 * <p>ラベル系 EP は MVP 対象外（Phase 2）のため本コントローラーには含めない。</p>
 *
 * <p><b>骨組み（一陣）</b>: サービス本体は三陣で実装する。現段階ではコンパイルが通る空骨格
 * （サービスが {@code UnsupportedOperationException} を投げるため実行時は未完）。</p>
 */
@RestController
@RequestMapping("/api/v1/inbox")
@Tag(name = "通知インボックス", description = "統合通知インボックス（あとで見る仕分け）API")
@RequiredArgsConstructor
public class InboxController {

    private final InboxAggregationService aggregationService;
    private final InboxTriageService triageService;

    // ─────────────────────────────────────────────
    // 一覧
    // ─────────────────────────────────────────────

    @GetMapping
    @Operation(summary = "インボックス一覧取得",
            description = "5 ソースを集約し、状態/緊急度/種類/ラベルで絞り込んだ一覧を返す。")
    public ResponseEntity<ApiResponse<InboxPageResponse>> getInbox(
            @RequestParam(name = "state", defaultValue = "INBOX") String state,
            @RequestParam(name = "priority", required = false) List<InboxPriority> priority,
            @RequestParam(name = "sourceType", required = false) List<InboxSourceType> sourceType,
            @RequestParam(name = "labelId", required = false) UUID labelId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        Long userId = SecurityUtils.getCurrentUserId();
        InboxPageResponse response = aggregationService.getInbox(
                userId, state, priority, sourceType, labelId, page, size);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    @GetMapping("/summary")
    @Operation(summary = "インボックス件数サマリ取得",
            description = "状態別・緊急度別・種類別の件数を返す（タブ/バッジ用）。")
    public ResponseEntity<ApiResponse<InboxSummaryResponse>> getSummary() {
        Long userId = SecurityUtils.getCurrentUserId();
        InboxSummaryResponse response = aggregationService.getSummary(userId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    // ─────────────────────────────────────────────
    // triage（スヌーズ / アーカイブ）
    // ─────────────────────────────────────────────

    @PostMapping("/snooze")
    @Operation(summary = "スヌーズ",
            description = "通知を指定時刻まで受信箱から隠す（upsert）。時刻到来で自動復帰する。")
    public ResponseEntity<ApiResponse<InboxItemDto>> snooze(
            @Valid @RequestBody SnoozeInboxRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        InboxItemDto item = triageService.snooze(
                userId, request.getSourceType(), request.getSourceId(), request.getSnoozedUntil());
        return ResponseEntity.ok(ApiResponse.of(item));
    }

    @PostMapping("/unsnooze")
    @Operation(summary = "スヌーズ解除", description = "スヌーズを解除して受信箱へ戻す。")
    public ResponseEntity<ApiResponse<InboxItemDto>> unsnooze(
            @Valid @RequestBody TriageTargetRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        InboxItemDto item = triageService.unsnooze(
                userId, request.getSourceType(), request.getSourceId());
        return ResponseEntity.ok(ApiResponse.of(item));
    }

    @PostMapping("/archive")
    @Operation(summary = "アーカイブ", description = "通知を保管庫へ退避する（時間復帰なし）。")
    public ResponseEntity<ApiResponse<InboxItemDto>> archive(
            @Valid @RequestBody TriageTargetRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        InboxItemDto item = triageService.archive(
                userId, request.getSourceType(), request.getSourceId());
        return ResponseEntity.ok(ApiResponse.of(item));
    }

    @PostMapping("/unarchive")
    @Operation(summary = "アーカイブ解除", description = "保管庫から受信箱へ戻す。")
    public ResponseEntity<ApiResponse<InboxItemDto>> unarchive(
            @Valid @RequestBody TriageTargetRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        InboxItemDto item = triageService.unarchive(
                userId, request.getSourceType(), request.getSourceId());
        return ResponseEntity.ok(ApiResponse.of(item));
    }
}
