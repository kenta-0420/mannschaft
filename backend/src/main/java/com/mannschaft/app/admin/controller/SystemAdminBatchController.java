package com.mannschaft.app.admin.controller;

import com.mannschaft.app.admin.AdminMapper;
import com.mannschaft.app.admin.batch.BatchEndpointDescriptor;
import com.mannschaft.app.admin.batch.BatchEndpointRegistry;
import com.mannschaft.app.admin.batch.ShedLockProbe;
import com.mannschaft.app.admin.dto.BatchEndpointSummary;
import com.mannschaft.app.admin.dto.BatchJobLogResponse;
import com.mannschaft.app.admin.dto.BatchStatusResponse;
import com.mannschaft.app.admin.dto.BatchTriggerResponse;
import com.mannschaft.app.admin.entity.BatchJobLogEntity;
import com.mannschaft.app.admin.service.BatchJobLogService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.security.AuthorizedByPathConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;

/**
 * F10.X 第二陣（汎用バッチキック API） — システム管理者向けバッチ起動コントローラー。
 *
 * <p>{@code /api/v1/system-admin/**} 配下は SecurityConfig で SYSTEM_ADMIN ロールに制限されているため、
 * 本 Controller 側でロールチェックの再記述は不要（既存 SystemAdmin Controller の慣習に従う）。</p>
 *
 * <p>提供エンドポイント:</p>
 * <ul>
 *   <li>{@code GET    /} — 登録済みバッチ一覧（{@link BatchEndpointRegistry#listAll()} を DTO 化）</li>
 *   <li>{@code GET    /{name}/status} — 直近 1 件の {@link BatchJobLogEntity} を返す</li>
 *   <li>{@code POST   /{name}/trigger?sync={bool}} — バッチを起動（既定 sync=false=非同期）</li>
 * </ul>
 *
 * <p>二重起動防御: ShedLock を付与しているバッチについては、起動前に {@link ShedLockProbe} で
 * 現在ロック中かを SELECT し、ロック中なら HTTP 409 で即返却する。
 * これは UX 改善のための早期判定であり、最終的な排他は ShedLock 本体が保証する。</p>
 *
 * <p>非同期起動は {@code @Qualifier("job-pool")} の {@link Executor} に投入する。
 * MDC は {@code AsyncConfig.MdcTaskDecorator} により呼び出し元コンテキストが伝播する。</p>
 *
 * <p><b>認可根拠（{@link AuthorizedByPathConfig} クラス付与・凍結ストア該当 3 EP）</b>:
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
@Slf4j
@RestController
@RequestMapping("/api/v1/system-admin/batch")
@Tag(name = "システム管理 - バッチ起動", description = "F10.X 第二陣 汎用バッチキック API")
public class SystemAdminBatchController {

    private final BatchEndpointRegistry registry;
    private final BatchJobLogService batchJobLogService;
    private final AdminMapper adminMapper;
    private final ShedLockProbe shedLockProbe;
    private final Executor jobPoolExecutor;

    public SystemAdminBatchController(
            BatchEndpointRegistry registry,
            BatchJobLogService batchJobLogService,
            AdminMapper adminMapper,
            ShedLockProbe shedLockProbe,
            @Qualifier("job-pool") Executor jobPoolExecutor) {
        this.registry = registry;
        this.batchJobLogService = batchJobLogService;
        this.adminMapper = adminMapper;
        this.shedLockProbe = shedLockProbe;
        this.jobPoolExecutor = jobPoolExecutor;
    }

    /**
     * 登録済みバッチエンドポイント一覧を取得する。
     */
    @GetMapping
    @Operation(summary = "登録済みバッチ一覧取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<BatchEndpointSummary>>> listBatches() {
        List<BatchEndpointSummary> summaries = registry.listAll().stream()
                .map(this::toSummary)
                .toList();
        return ResponseEntity.ok(ApiResponse.of(summaries));
    }

    /**
     * 指定バッチの直近実行状況を取得する（履歴が無ければ {@code lastJobLog=null} で 200）。
     */
    @GetMapping("/{name}/status")
    @Operation(summary = "バッチ直近実行状況取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "未登録のバッチ")
    public ResponseEntity<ApiResponse<BatchStatusResponse>> getStatus(@PathVariable String name) {
        if (registry.find(name).isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        BatchJobLogResponse lastLog = batchJobLogService.findLatestByJobName(name)
                .map(adminMapper::toBatchJobLogResponse)
                .orElse(null);
        return ResponseEntity.ok(ApiResponse.of(new BatchStatusResponse(name, lastLog)));
    }

    /**
     * 指定バッチをキックする。
     *
     * <p>{@code sync=true}: 同期実行。完了まで待ち、結果を 200 / 500 で返す。<br>
     * {@code sync=false}（既定）: 非同期実行。即座に 202 Accepted を返し、ジョブは job-pool で動く。</p>
     *
     * <p>ShedLock 取得中の同名バッチが存在する場合は 409 Conflict を返す
     * （早期判定であり、競合状態は ShedLock 本体が最終的に防ぐ）。</p>
     */
    @PostMapping("/{name}/trigger")
    @Operation(summary = "バッチ起動")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "同期実行成功")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "202", description = "非同期実行受付")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "未登録のバッチ")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "他インスタンスがロック中")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "同期実行で例外発生")
    public ResponseEntity<ApiResponse<BatchTriggerResponse>> trigger(
            @PathVariable String name,
            @RequestParam(value = "sync", defaultValue = "false") boolean sync) {
        Optional<BatchEndpointDescriptor> descriptorOpt = registry.find(name);
        if (descriptorOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        BatchEndpointDescriptor descriptor = descriptorOpt.get();

        // 早期 409: ShedLock 取得中なら即返却
        String lockName = descriptor.schedulerLockName();
        if (lockName != null && shedLockProbe.isLocked(lockName)) {
            BatchTriggerResponse body = new BatchTriggerResponse(
                    name,
                    "LOCKED",
                    null,
                    "他インスタンスがロックを保持中のため起動を拒否しました: lockName=" + lockName);
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.of(body));
        }

        if (sync) {
            return invokeSync(name);
        }
        return invokeAsync(name);
    }

    /**
     * 同期実行: 呼び出しスレッドでそのまま invoke。
     *
     * <p>{@link BatchEndpointRegistry#invoke(String)} は {@link com.mannschaft.app.admin.batch.BatchExecutionAspect}
     * を介して batch_job_logs を書き、完了/失敗イベントを発火する。
     * 同期成功時は更新された直近 log を引き直して jobLogId を返す。</p>
     */
    private ResponseEntity<ApiResponse<BatchTriggerResponse>> invokeSync(String name) {
        try {
            registry.invoke(name);
            Long jobLogId = batchJobLogService.findLatestByJobName(name)
                    .map(BatchJobLogEntity::getId)
                    .orElse(null);
            return ResponseEntity.ok(ApiResponse.of(
                    BatchTriggerResponse.completed(name, jobLogId, "同期実行が完了しました")));
        } catch (RuntimeException ex) {
            log.error("バッチ同期実行失敗: name={}", name, ex);
            Long jobLogId = batchJobLogService.findLatestByJobName(name)
                    .map(BatchJobLogEntity::getId)
                    .orElse(null);
            String message = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getName();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                    ApiResponse.of(BatchTriggerResponse.failed(name, jobLogId, message)));
        }
    }

    /**
     * 非同期実行: job-pool に投入し即座に 202 を返す。
     *
     * <p>job-pool は {@code AsyncConfig#jobPoolExecutor()} で定義され、core=2 / max=4 / queue=50。
     * ジョブの結果は batch_job_logs を {@code GET /{name}/status} で参照すること。</p>
     */
    private ResponseEntity<ApiResponse<BatchTriggerResponse>> invokeAsync(String name) {
        log.info("バッチ非同期起動受付: name={}", name);
        jobPoolExecutor.execute(() -> {
            try {
                registry.invoke(name);
            } catch (Exception ex) {
                // Aspect 側で batch_job_logs に失敗が記録される。ここではログのみ。
                log.error("バッチ非同期実行で例外: name={}", name, ex);
            }
        });
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(
                ApiResponse.of(BatchTriggerResponse.accepted(
                        name, "非同期実行を受け付けました。結果は /status で確認してください。")));
    }

    /**
     * Descriptor + 直近ログ → サマリ DTO 変換。
     */
    private BatchEndpointSummary toSummary(BatchEndpointDescriptor descriptor) {
        Optional<BatchJobLogEntity> latest = batchJobLogService.findLatestByJobName(descriptor.name());
        if (latest.isEmpty()) {
            return BatchEndpointSummary.withoutHistory(
                    descriptor.name(), descriptor.description(), descriptor.schedulerLockName());
        }
        BatchJobLogEntity entity = latest.get();
        return new BatchEndpointSummary(
                descriptor.name(),
                descriptor.description(),
                descriptor.schedulerLockName(),
                entity.getStatus() != null ? entity.getStatus().name() : null,
                entity.getStartedAt());
    }
}
