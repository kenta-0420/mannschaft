package com.mannschaft.app.auth.controller;

import com.mannschaft.app.auth.dto.AuditLogResponse;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.CursorPagedResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.common.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * 監査ログ参照コントローラー。
 * SYSTEM_ADMIN 向け全ログ参照（オフセット）と一般ユーザー向け自分のログ参照（カーソル）を提供する。
 */
@Tag(name = "監査ログ", description = "F10.3 監査ログ参照")
@RestController
@RequiredArgsConstructor
public class AuditLogAdminController {

    private final AuditLogService auditLogService;

    /**
     * 全監査ログ一覧を取得する（SYSTEM_ADMIN 専用・オフセットページング）。
     *
     * @param userId         絞り込みユーザーID
     * @param targetUserId   絞り込み対象ユーザーID
     * @param teamId         絞り込みチームID
     * @param organizationId 絞り込み組織ID
     * @param eventType      イベント種別（カンマ区切りで複数指定可）
     * @param sessionHash    セッションハッシュ完全一致
     * @param from           開始日時（ISO 8601）
     * @param to             終了日時（ISO 8601）
     * @param page           ページ番号（0始まり、デフォルト0）
     * @param size           ページサイズ（デフォルト20・最大100）
     */
    @Operation(summary = "監査ログ一覧（SYSTEM_ADMIN）")
    @GetMapping("/api/v1/admin/audit-logs")
    public PagedResponse<AuditLogResponse> getAdminLogs(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) Long targetUserId,
            @RequestParam(required = false) Long teamId,
            @RequestParam(required = false) Long organizationId,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) String sessionHash,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Long requestUserId = SecurityUtils.getCurrentUserId();
        List<String> eventTypes = parseEventTypes(eventType);

        return auditLogService.getAdminLogs(
                requestUserId,
                userId, targetUserId, teamId, organizationId,
                eventTypes, sessionHash,
                from, to, page, size);
    }

    /**
     * 自分の監査ログ一覧を取得する（カーソルページング）。
     *
     * @param eventType イベント種別（カンマ区切りで複数指定可）
     * @param from      開始日時
     * @param to        終了日時
     * @param cursor    カーソル（前ページ末尾の id 文字列）
     * @param limit     取得件数（デフォルト20・最大50）
     */
    @Operation(summary = "自分の監査ログ一覧")
    @GetMapping("/api/v1/users/me/audit-logs")
    public ApiResponse<List<AuditLogResponse>> getMyLogs(
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit) {

        Long currentUserId = SecurityUtils.getCurrentUserId();
        List<String> eventTypes = parseEventTypes(eventType);

        List<AuditLogResponse> logs = auditLogService.getMyLogs(
                currentUserId, eventTypes, from, to, cursor, limit);
        return ApiResponse.of(logs);
    }

    private List<String> parseEventTypes(String eventType) {
        if (eventType == null || eventType.isBlank()) return null;
        return Arrays.stream(eventType.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
