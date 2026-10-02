package com.mannschaft.app.reflection.controller;

import com.mannschaft.app.cms.dto.BlogPostResponse;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.reflection.dto.CreateRecallAttemptRequest;
import com.mannschaft.app.reflection.dto.ExportToBlogRequest;
import com.mannschaft.app.reflection.dto.RecallAttemptResponse;
import com.mannschaft.app.reflection.dto.ReflectionEntryResponse;
import com.mannschaft.app.reflection.dto.UpsertReflectionEntryRequest;
import com.mannschaft.app.reflection.service.RecallService;
import com.mannschaft.app.reflection.service.ReflectionEntryService;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * F06.5 振り返りエントリ・想起テストコントローラー（§7 #6〜#11, #13）。
 *
 * <p>認可は全エンドポイント「認証必須＋本人所有のみ」。マスク適用はサービス／Mapper 側（§3.2）。</p>
 */
@RestController
@RequestMapping("/api/v1/me/reflections")
@Tag(name = "振り返りエントリ", description = "F06.5 アクティブリコール学習機能 — 振り返り・想起テスト")
@RequiredArgsConstructor
public class ReflectionEntryController {

    private final ReflectionEntryService reflectionEntryService;
    private final RecallService recallService;

    /** テーマ配下エントリ一覧（§7 #6・マスク適用）。 */
    @GetMapping("/themes/{themeId}/entries")
    @Operation(summary = "テーマ配下エントリ一覧取得")
    public ResponseEntity<ApiResponse<List<ReflectionEntryResponse>>> listEntries(
            @PathVariable UUID themeId) {
        List<ReflectionEntryResponse> result =
                reflectionEntryService.listEntries(SecurityUtils.getCurrentUserId(), themeId);
        return ResponseEntity.ok(ApiResponse.of(result));
    }

    /** エントリ upsert（§7 #7・(theme,target_date)一意・楽観排他 409）。 */
    @PutMapping("/entries")
    @Operation(summary = "エントリ upsert（作成/更新）")
    public ResponseEntity<ApiResponse<ReflectionEntryResponse>> upsertEntry(
            @Valid @RequestBody UpsertReflectionEntryRequest request) {
        ReflectionEntryResponse result =
                reflectionEntryService.upsertEntry(SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.ok(ApiResponse.of(result));
    }

    /** エントリ詳細（§7 #8・マスク適用）。 */
    @GetMapping("/entries/{entryId}")
    @Operation(summary = "エントリ詳細取得")
    public ResponseEntity<ApiResponse<ReflectionEntryResponse>> getEntry(
            @PathVariable UUID entryId) {
        ReflectionEntryResponse result =
                reflectionEntryService.getEntry(SecurityUtils.getCurrentUserId(), entryId);
        return ResponseEntity.ok(ApiResponse.of(result));
    }

    /** エントリ論理削除（§7 #9）。 */
    @DeleteMapping("/entries/{entryId}")
    @Operation(summary = "エントリ論理削除")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public ResponseEntity<Void> deleteEntry(@PathVariable UUID entryId) {
        reflectionEntryService.deleteEntry(SecurityUtils.getCurrentUserId(), entryId);
        return ResponseEntity.noContent().build();
    }

    /** 想起テスト保存＝開示（§7 #10・revealed_at 記録・original 返却）。 */
    @PostMapping("/entries/{entryId}/recall")
    @Operation(summary = "想起テスト保存＝開示")
    public ResponseEntity<ApiResponse<ReflectionEntryResponse>> recordRecall(
            @PathVariable UUID entryId,
            @Valid @RequestBody CreateRecallAttemptRequest request) {
        ReflectionEntryResponse result =
                recallService.recordRecall(SecurityUtils.getCurrentUserId(), entryId, request);
        return ResponseEntity.ok(ApiResponse.of(result));
    }

    /** 想起履歴一覧（§7 #11）。 */
    @GetMapping("/entries/{entryId}/recalls")
    @Operation(summary = "想起履歴一覧取得")
    public ResponseEntity<ApiResponse<List<RecallAttemptResponse>>> listRecalls(
            @PathVariable UUID entryId) {
        List<RecallAttemptResponse> result =
                recallService.listRecalls(SecurityUtils.getCurrentUserId(), entryId);
        return ResponseEntity.ok(ApiResponse.of(result));
    }

    /** ブログ輸出（§7 #13・元エントリ残存）。 */
    @PostMapping("/entries/{entryId}/export-to-blog")
    @Operation(summary = "ブログ輸出（元エントリ残存）")
    public ResponseEntity<ApiResponse<BlogPostResponse>> exportToBlog(
            @PathVariable UUID entryId,
            @Valid @RequestBody ExportToBlogRequest request) {
        BlogPostResponse result =
                reflectionEntryService.exportToBlog(SecurityUtils.getCurrentUserId(), entryId, request);
        return ResponseEntity.ok(ApiResponse.of(result));
    }
}
