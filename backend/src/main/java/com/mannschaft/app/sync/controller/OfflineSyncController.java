package com.mannschaft.app.sync.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.sync.dto.ConflictDetailResponse;
import com.mannschaft.app.sync.dto.ConflictResponse;
import com.mannschaft.app.sync.dto.ResolveConflictRequest;
import com.mannschaft.app.sync.dto.SyncRequest;
import com.mannschaft.app.sync.dto.SyncResponse;
import com.mannschaft.app.sync.service.ConflictResolverService;
import com.mannschaft.app.sync.service.OfflineSyncService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
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
 * F11.1 オフライン同期コントローラー。
 * オフラインキューの一括同期とコンフリクト管理の5エンドポイントを提供する。
 */
@RestController
@RequestMapping("/api/v1/sync")
@Tag(name = "オフライン同期", description = "F11.1 オフラインキューの同期とコンフリクト管理")
@RequiredArgsConstructor
public class OfflineSyncController {

    private final OfflineSyncService syncService;
    private final ConflictResolverService conflictResolverService;

    private static final int DEFAULT_PAGE_SIZE = 20;

    /**
     * オフラインキュー一括同期。
     * フロントエンドがオフライン中にキューイングしたリクエストを最大50件一括送信する。
     */
    @PostMapping
    @Operation(summary = "オフラインキュー一括同期")
    public ResponseEntity<ApiResponse<SyncResponse>> sync(
            @Valid @RequestBody SyncRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        SyncResponse response = syncService.sync(userId, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 自分の未解決コンフリクト一覧を取得する。
     */
    @GetMapping("/conflicts/me")
    @Operation(summary = "自分の未解決コンフリクト一覧")
    public ResponseEntity<PagedResponse<ConflictResponse>> getMyConflicts(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long userId = SecurityUtils.getCurrentUserId();
        Pageable pageable = PageRequest.of(page, Math.min(size, DEFAULT_PAGE_SIZE));
        Page<ConflictResponse> conflicts = conflictResolverService.getMyConflicts(userId, pageable);

        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                conflicts.getTotalElements(),
                conflicts.getNumber(),
                conflicts.getSize(),
                conflicts.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(conflicts.getContent(), meta));
    }

    /**
     * コンフリクト詳細を取得する。
     */
    @GetMapping("/conflicts/{id}")
    @Operation(summary = "コンフリクト詳細")
    public ResponseEntity<ApiResponse<ConflictDetailResponse>> getConflictDetail(
            @PathVariable Long id) {
        Long userId = SecurityUtils.getCurrentUserId();
        ConflictDetailResponse detail = conflictResolverService.getConflictDetail(id, userId);
        return ResponseEntity.ok(ApiResponse.of(detail));
    }

    /**
     * コンフリクトを解決する。
     * resolution: CLIENT_WIN / SERVER_WIN / MANUAL_MERGE
     */
    @PatchMapping("/conflicts/{id}/resolve")
    @Operation(summary = "コンフリクト解決")
    public ResponseEntity<ApiResponse<ConflictDetailResponse>> resolveConflict(
            @PathVariable Long id,
            @Valid @RequestBody ResolveConflictRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        ConflictDetailResponse detail = conflictResolverService.resolveConflict(id, userId, request);
        return ResponseEntity.ok(ApiResponse.of(detail));
    }

    /**
     * コンフリクトを破棄（DISCARDED）する。
     */
    @DeleteMapping("/conflicts/{id}")
    @Operation(summary = "コンフリクト破棄")
    public ResponseEntity<Void> discardConflict(@PathVariable Long id) {
        Long userId = SecurityUtils.getCurrentUserId();
        conflictResolverService.discardConflict(id, userId);
        return ResponseEntity.noContent().build();
    }
}
