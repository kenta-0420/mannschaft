package com.mannschaft.app.inbox.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.security.SelfScopedEndpoint;
import com.mannschaft.app.inbox.InboxPriority;
import com.mannschaft.app.inbox.InboxSourceType;
import com.mannschaft.app.inbox.dto.BulkInboxRequest;
import com.mannschaft.app.inbox.dto.BulkResultResponse;
import com.mannschaft.app.inbox.dto.CreateLabelRequest;
import com.mannschaft.app.inbox.dto.InboxItemDto;
import com.mannschaft.app.inbox.dto.InboxPageResponse;
import com.mannschaft.app.inbox.dto.InboxSummaryResponse;
import com.mannschaft.app.inbox.dto.LabelDto;
import com.mannschaft.app.inbox.dto.SnoozeInboxRequest;
import com.mannschaft.app.inbox.dto.SuggestApplyRequest;
import com.mannschaft.app.inbox.dto.TriageTargetRequest;
import com.mannschaft.app.inbox.dto.UpdateLabelRequest;
import com.mannschaft.app.inbox.service.InboxAggregationService;
import com.mannschaft.app.inbox.service.InboxBulkService;
import com.mannschaft.app.inbox.service.InboxLabelService;
import com.mannschaft.app.inbox.service.InboxTriageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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
 * <p>Phase 2 でラベル CRUD・付与/解除・一括操作（bulk）を追加した。</p>
 *
 * <p><b>MVP 範囲</b>: 集約対象は NOTIFICATION と TODO_DUE の 2 ソース（実装済み）。
 * 残り 3 ソース（ANNOUNCEMENT / MENTION / CONFIRMABLE）は後続フェーズでアダプタを追加する
 * （{@code InboxSourceAdapter} 実装を足すのみ。集約・API・本コントローラーは不変）。</p>
 */
@RestController
@RequestMapping("/api/v1/inbox")
@Tag(name = "通知インボックス", description = "統合通知インボックス（あとで見る仕分け）API")
@RequiredArgsConstructor
public class InboxController {

    private final InboxAggregationService aggregationService;
    private final InboxTriageService triageService;
    private final InboxLabelService labelService;
    private final InboxBulkService bulkService;

    // ─────────────────────────────────────────────
    // 一覧
    // ─────────────────────────────────────────────

    @SelfScopedEndpoint("集約対象は SecurityUtils.getCurrentUserId() で確定した認証主体固定"
            + "（InboxAggregationService#getInbox）")
    @GetMapping
    @Operation(summary = "インボックス一覧取得",
            description = "通知ソースを集約し、状態/緊急度/種類/ラベルで絞り込んだ一覧を返す"
                    + "（MVP は NOTIFICATION・TODO_DUE の 2 ソース。残ソースは後続フェーズで追加）。")
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

    @SelfScopedEndpoint("集計対象は SecurityUtils.getCurrentUserId() で確定した認証主体固定"
            + "（InboxAggregationService#getSummary）")
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

    @SelfScopedEndpoint("解除対象の複合キーは (userId, sourceType, sourceId) で、userId は "
            + "SecurityUtils.getCurrentUserId() 固定のため他ユーザーの行には到達しない"
            + "（InboxTriageService#unsnooze の requireExisting）")
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

    @SelfScopedEndpoint("解除対象の複合キーは (userId, sourceType, sourceId) で、userId は "
            + "SecurityUtils.getCurrentUserId() 固定のため他ユーザーの行には到達しない"
            + "（InboxTriageService#unarchive の requireExisting）")
    @PostMapping("/unarchive")
    @Operation(summary = "アーカイブ解除", description = "保管庫から受信箱へ戻す。")
    public ResponseEntity<ApiResponse<InboxItemDto>> unarchive(
            @Valid @RequestBody TriageTargetRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        InboxItemDto item = triageService.unarchive(
                userId, request.getSourceType(), request.getSourceId());
        return ResponseEntity.ok(ApiResponse.of(item));
    }

    // ─────────────────────────────────────────────
    // ラベル CRUD（Phase 2）
    // ─────────────────────────────────────────────

    @SelfScopedEndpoint("一覧のスコープは SecurityUtils.getCurrentUserId() で確定した認証主体固定"
            + "（InboxLabelService#getLabels）")
    @GetMapping("/labels")
    @Operation(summary = "ラベル一覧取得",
            description = "現役（論理削除されていない）ラベルを sortOrder 昇順で返す。")
    public ResponseEntity<ApiResponse<List<LabelDto>>> getLabels() {
        Long userId = SecurityUtils.getCurrentUserId();
        List<LabelDto> labels = labelService.getLabels(userId);
        return ResponseEntity.ok(ApiResponse.of(labels));
    }

    @SelfScopedEndpoint("作成先は SecurityUtils.getCurrentUserId() で確定した認証主体固定"
            + "（InboxLabelService#createLabel）")
    @PostMapping("/labels")
    @Operation(summary = "ラベル作成",
            description = "ラベルを作成する。上限 20 件・同名重複・色/アイコン形式を検証する。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<LabelDto>> createLabel(
            @Valid @RequestBody CreateLabelRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        LabelDto label = labelService.createLabel(
                userId, request.getName(), request.getColor(), request.getIcon());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(label));
    }

    @PutMapping("/labels/{labelId}")
    @Operation(summary = "ラベル更新",
            description = "名前/色/アイコン/順序を更新する。他人/不存在/論理削除済みは 404。")
    public ResponseEntity<ApiResponse<LabelDto>> updateLabel(
            @PathVariable UUID labelId,
            @Valid @RequestBody UpdateLabelRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        LabelDto label = labelService.updateLabel(
                userId, labelId, request.getName(), request.getColor(),
                request.getIcon(), request.getSortOrder());
        return ResponseEntity.ok(ApiResponse.of(label));
    }

    @DeleteMapping("/labels/{labelId}")
    @Operation(summary = "ラベル論理削除", description = "ラベルを論理削除する。他人/不存在は 404。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteLabel(@PathVariable UUID labelId) {
        Long userId = SecurityUtils.getCurrentUserId();
        labelService.deleteLabel(userId, labelId);
        return ResponseEntity.noContent().build();
    }

    // ─────────────────────────────────────────────
    // ラベル付与 / 解除（Phase 2）
    // ─────────────────────────────────────────────

    @PostMapping("/labels/{labelId}/assign")
    @Operation(summary = "ラベル付与",
            description = "通知にラベルを付与する。ラベル所有・対象通知の可視性・1 通知 10 ラベル上限を検証する（重複は冪等）。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "付与成功")
    public ResponseEntity<Void> assignLabel(
            @PathVariable UUID labelId,
            @Valid @RequestBody TriageTargetRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        labelService.assignLabel(userId, labelId, request.getSourceType(), request.getSourceId());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/labels/{labelId}/assign")
    @Operation(summary = "ラベル付与解除",
            description = "通知からラベル付与を解除する（リンクが無ければ冪等に無視）。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "解除成功")
    public ResponseEntity<Void> unassignLabel(
            @PathVariable UUID labelId,
            @Valid @RequestBody TriageTargetRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        labelService.unassignLabel(userId, labelId, request.getSourceType(), request.getSourceId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/labels/suggest-apply")
    @Operation(summary = "提案ラベルの1タップ付与（自動ラベリング・案C）",
            description = "提案チップのタップで、同名ラベルを find-or-create して当該通知へ付与する。"
                    + "重複作成・重複付与はせず冪等（200）。可視性・上限は付与経路で検証する。")
    public ResponseEntity<ApiResponse<LabelDto>> suggestApply(
            @Valid @RequestBody SuggestApplyRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        LabelDto label = labelService.suggestApply(
                userId, request.getName(), request.getColor(),
                request.getSourceType(), request.getSourceId());
        return ResponseEntity.ok(ApiResponse.of(label));
    }

    // ─────────────────────────────────────────────
    // 一括操作（Phase 2）
    // ─────────────────────────────────────────────

    @PostMapping("/bulk")
    @Operation(summary = "一括操作",
            description = "複数通知への archive/unarchive/snooze/label_add を一括適用する。"
                    + "部分失敗を許容し、成功/スキップ件数を返す（全体 200）。")
    public ResponseEntity<ApiResponse<BulkResultResponse>> bulk(
            @Valid @RequestBody BulkInboxRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        BulkResultResponse result = bulkService.bulk(userId, request);
        return ResponseEntity.ok(ApiResponse.of(result));
    }
}
