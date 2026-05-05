package com.mannschaft.app.admin.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.storage.migration.StorageMigrationStatus;
import com.mannschaft.app.common.storage.migration.StoragePathMigrationBatchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.Executor;

/**
 * F13 Phase 5-b ストレージパス移行管理者向け API コントローラー。
 *
 * <p>SystemAdmin ロールのみがアクセスできる（SecurityConfig で {@code /api/v1/system-admin/**}
 * は認証済みユーザーのみ許可、別途フィルターで SYSTEM_ADMIN ロールを確認する）。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/system-admin/storage-migration")
@Tag(name = "システム管理 - ストレージ移行", description = "F13 Phase 5-b ストレージパス移行バッチ API")
public class StorageMigrationAdminController {

    private final StoragePathMigrationBatchService migrationBatchService;
    private final Executor jobPoolExecutor;

    public StorageMigrationAdminController(
            StoragePathMigrationBatchService migrationBatchService,
            @Qualifier("job-pool") Executor jobPoolExecutor) {
        this.migrationBatchService = migrationBatchService;
        this.jobPoolExecutor = jobPoolExecutor;
    }

    /**
     * ストレージパス移行の進捗状況を取得する。
     *
     * @return 機能ごとの総件数・移行済み件数・未移行件数およびエラー件数
     */
    @GetMapping("/status")
    @Operation(summary = "ストレージパス移行進捗確認")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<StorageMigrationStatus>> getStatus() {
        StorageMigrationStatus status = migrationBatchService.getStatus();
        return ResponseEntity.ok(ApiResponse.of(status));
    }

    /**
     * ストレージパス移行バッチを非同期で実行する。
     *
     * <p>バッチは非同期で実行されるためリクエストはすぐに返る。
     * 進捗は {@code GET /status} で確認する。</p>
     *
     * @return 実行開始メッセージ
     */
    @PostMapping("/run")
    @Operation(summary = "ストレージパス移行バッチ手動実行（非同期）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "202", description = "バッチ開始受け付け")
    public ResponseEntity<ApiResponse<Map<String, String>>> runMigration() {
        log.info("ストレージパス移行バッチ手動実行リクエスト受け付け");
        jobPoolExecutor.execute(() -> {
            try {
                Map<String, Long> results = migrationBatchService.migrateAll();
                log.info("ストレージパス移行バッチ完了: {}", results);
            } catch (Exception e) {
                log.error("ストレージパス移行バッチ実行エラー", e);
            }
        });
        return ResponseEntity.accepted()
                .body(ApiResponse.of(Map.of("message", "ストレージパス移行バッチを開始しました。進捗は /status で確認してください。")));
    }
}
