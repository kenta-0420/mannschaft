package com.mannschaft.app.auth.controller;

import com.mannschaft.app.auth.AuditEventCategory;
import com.mannschaft.app.auth.dto.AuditLogResponse;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.CursorPagedResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.featuregate.AlwaysReachable;
import com.mannschaft.app.common.featuregate.AlwaysReachableCategory;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * チーム・組織ADMINスコープ監査ログ参照コントローラー。
 * TEAM/ORGのADMIN以上が自スコープのログのみ参照できる。
 */
@Tag(name = "監査ログ", description = "F10.3 監査ログ参照")
@RestController
@RequiredArgsConstructor
public class AuditLogScopeController {

    private final AuditLogService auditLogService;

    /**
     * チームの監査ログ一覧を取得する（チームADMIN以上・カーソルページング）。
     *
     * @param teamId         対象チームID
     * @param userId         絞り込みユーザーID
     * @param eventType      イベント種別（カンマ区切りで複数指定可）
     * @param eventCategory  イベントカテゴリ（複数指定可）
     * @param from           開始日時（ISO 8601）
     * @param to             終了日時（ISO 8601）
     * @param cursor         カーソル（前ページ末尾の id 文字列）
     * @param limit          取得件数（デフォルト20・最大100）
     */
    @Operation(summary = "チーム監査ログ一覧（チームADMIN）")
    @AlwaysReachable(category = AlwaysReachableCategory.CORE,
            reason = "チームの監査証跡をGate状態にかかわらず確認可能にするため")
    @GetMapping("/api/v1/teams/{teamId}/audit-logs")
    public CursorPagedResponse<AuditLogResponse> getTeamAuditLogs(
            @PathVariable Long teamId,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) List<AuditEventCategory> eventCategory,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit) {

        Long requestUserId = SecurityUtils.getCurrentUserId();
        List<String> eventTypes = parseEventTypes(eventType);

        return auditLogService.getTeamAuditLogs(
                requestUserId, teamId, userId,
                eventTypes, eventCategory,
                from, to, cursor, limit);
    }

    /**
     * 組織の監査ログ一覧を取得する（組織ADMIN以上・カーソルページング）。
     *
     * @param orgId          対象組織ID
     * @param userId         絞り込みユーザーID
     * @param eventType      イベント種別（カンマ区切りで複数指定可）
     * @param eventCategory  イベントカテゴリ（複数指定可）
     * @param from           開始日時（ISO 8601）
     * @param to             終了日時（ISO 8601）
     * @param cursor         カーソル
     * @param limit          取得件数
     */
    @Operation(summary = "組織監査ログ一覧（組織ADMIN）")
    @AlwaysReachable(category = AlwaysReachableCategory.CORE,
            reason = "組織の監査証跡をGate状態にかかわらず確認可能にするため")
    @GetMapping("/api/v1/organizations/{orgId}/audit-logs")
    public CursorPagedResponse<AuditLogResponse> getOrganizationAuditLogs(
            @PathVariable Long orgId,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) List<AuditEventCategory> eventCategory,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit) {

        Long requestUserId = SecurityUtils.getCurrentUserId();
        List<String> eventTypes = parseEventTypes(eventType);

        return auditLogService.getOrganizationAuditLogs(
                requestUserId, orgId, userId,
                eventTypes, eventCategory,
                from, to, cursor, limit);
    }

    private List<String> parseEventTypes(String eventType) {
        if (eventType == null || eventType.isBlank()) return null;
        return Arrays.stream(eventType.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
