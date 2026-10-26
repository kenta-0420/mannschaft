package com.mannschaft.app.admin.systemlog;

import com.mannschaft.app.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * システム管理者向けシステムログ参照コントローラー。
 * R2 上のスロークエリログ・SSR エラーログの一覧と Presigned ダウンロード URL を提供する。
 */
@RestController
@RequestMapping("/api/v1/system-admin/system-logs")
@Tag(name = "システム管理 - システムログ", description = "F10.6 Phase 10-γ-③-a システムログ参照 API")
@RequiredArgsConstructor
public class SystemAdminSystemLogController {

    private final SystemLogService systemLogService;

    /**
     * R2 上のシステムログファイル一覧を取得する。
     *
     * @param type ログ種別フィルタ（"slow-query" | "ssr-error"。省略時は両方）
     * @param date 日付フィルタ（YYYY-MM-DD 形式。省略時は全件）
     * @return ログファイル一覧（Presigned ダウンロード URL 付き）
     */
    @GetMapping
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    @Operation(summary = "システムログファイル一覧取得", description = "R2 上のスロークエリ・SSR エラーログ一覧を Presigned URL 付きで返す")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<SystemLogFileResponse>>> listSystemLogs(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String date) {

        LocalDate parsedDate = date != null ? LocalDate.parse(date) : null;
        List<SystemLogFileResponse> files = systemLogService.listLogFiles(type, parsedDate);
        return ResponseEntity.ok(ApiResponse.of(files));
    }
}
