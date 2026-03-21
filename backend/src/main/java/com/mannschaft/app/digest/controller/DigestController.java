package com.mannschaft.app.digest.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.digest.dto.DigestDetailResponse;
import com.mannschaft.app.digest.dto.DigestEditRequest;
import com.mannschaft.app.digest.dto.DigestGenerateRequest;
import com.mannschaft.app.digest.dto.DigestGenerateResponse;
import com.mannschaft.app.digest.dto.DigestListResponse;
import com.mannschaft.app.digest.dto.DigestPublishRequest;
import com.mannschaft.app.digest.dto.DigestPublishResponse;
import com.mannschaft.app.digest.dto.DigestRegenerateRequest;
import com.mannschaft.app.digest.service.DigestGenerationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
 * タイムラインダイジェストコントローラー。
 * ダイジェストの生成・一覧・詳細・公開・破棄・再生成・編集エンドポイントを提供する。
 */
@RestController
@RequestMapping("/api/v1/timeline-digest")
@Tag(name = "タイムラインダイジェスト")
@RequiredArgsConstructor
public class DigestController {

    private final DigestGenerationService digestGenerationService;

    /**
     * ダイジェスト手動生成。
     * AI スタイルの場合は 202 Accepted（非同期）、TEMPLATE の場合は 201 Created（同期）。
     */
    @PostMapping("/generate")
    @Operation(summary = "ダイジェスト手動生成")
    public ResponseEntity<ApiResponse<DigestGenerateResponse>> generate(
            @Valid @RequestBody DigestGenerateRequest request) {
        // TODO: SecurityContext からユーザー ID を取得
        Long userId = 0L;
        DigestGenerateResponse response = digestGenerationService.generate(request, userId);

        HttpStatus status = "GENERATING".equals(response.getStatus())
                ? HttpStatus.ACCEPTED
                : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(ApiResponse.of(response));
    }

    /**
     * ダイジェストをブログ下書きとして公開。
     */
    @PostMapping("/{id}/publish")
    @Operation(summary = "ダイジェストをブログ下書きとして公開")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "公開成功")
    public ResponseEntity<ApiResponse<DigestPublishResponse>> publish(
            @PathVariable Long id,
            @Valid @RequestBody DigestPublishRequest request) {
        DigestPublishResponse response = digestGenerationService.publish(id, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * ダイジェスト履歴一覧取得。
     */
    @GetMapping
    @Operation(summary = "ダイジェスト履歴一覧取得")
    public ResponseEntity<DigestListResponse> list(
            @RequestParam String scopeType,
            @RequestParam Long scopeId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long cursor,
            @RequestParam(required = false) Integer limit) {
        DigestListResponse response = digestGenerationService.list(scopeType, scopeId, status, cursor, limit);
        return ResponseEntity.ok(response);
    }

    /**
     * ダイジェスト詳細取得。
     */
    @GetMapping("/{id}")
    @Operation(summary = "ダイジェスト詳細取得")
    public ResponseEntity<ApiResponse<DigestDetailResponse>> getDetail(@PathVariable Long id) {
        DigestDetailResponse response = digestGenerationService.getDetail(id);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * ダイジェスト破棄（GENERATED のみ）。
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "ダイジェスト破棄")
    public ResponseEntity<ApiResponse<DigestDetailResponse>> discard(@PathVariable Long id) {
        DigestDetailResponse response = digestGenerationService.discard(id);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * ダイジェスト再生成。
     */
    @PostMapping("/{id}/regenerate")
    @Operation(summary = "ダイジェスト再生成")
    public ResponseEntity<ApiResponse<DigestGenerateResponse>> regenerate(
            @PathVariable Long id,
            @Valid @RequestBody DigestRegenerateRequest request) {
        // TODO: SecurityContext からユーザー ID を取得
        Long userId = 0L;
        DigestGenerateResponse response = digestGenerationService.regenerate(id, request, userId);

        HttpStatus status = "GENERATING".equals(response.getStatus())
                ? HttpStatus.ACCEPTED
                : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(ApiResponse.of(response));
    }

    /**
     * ダイジェストのインライン編集（GENERATED のみ）。
     */
    @PatchMapping("/{id}")
    @Operation(summary = "ダイジェストのインライン編集")
    public ResponseEntity<ApiResponse<DigestDetailResponse>> edit(
            @PathVariable Long id,
            @Valid @RequestBody DigestEditRequest request) {
        DigestDetailResponse response = digestGenerationService.edit(id, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
