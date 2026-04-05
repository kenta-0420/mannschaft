package com.mannschaft.app.admin.controller;

import com.mannschaft.app.admin.dto.BatchJobLogResponse;
import com.mannschaft.app.admin.service.BatchJobLogService;
import com.mannschaft.app.common.ApiResponse;
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
 */
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
