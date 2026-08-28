package com.mannschaft.app.admin.controller;

import com.mannschaft.app.admin.dto.BatchJobLogResponse;
import com.mannschaft.app.admin.service.BatchJobLogService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.security.AuthorizedByPathConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * システム管理者向けバッチジョブログコントローラー。
 *
 * <p><b>認可根拠（{@link AuthorizedByPathConfig} クラス付与・凍結ストア該当 1 EP）</b>:
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
@RequestMapping("/api/v1/system-admin/batch-logs")
@Tag(name = "システム管理 - バッチログ", description = "F10.1 バッチジョブログ参照API")
@RequiredArgsConstructor
public class SystemAdminBatchLogController {

    private final BatchJobLogService batchJobLogService;

    /**
     * バッチジョブログ一覧を取得する。
     */
    @GetMapping
    @Operation(summary = "バッチジョブログ一覧取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<BatchJobLogResponse>>> getLogs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String jobName) {
        List<BatchJobLogResponse> logs;
        if (jobName != null && !jobName.isBlank()) {
            logs = batchJobLogService.getLogsByJobName(jobName);
        } else {
            logs = batchJobLogService.getLogs(page, size);
        }
        return ResponseEntity.ok(ApiResponse.of(logs));
    }
}
