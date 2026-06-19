package com.mannschaft.app.schedule.controller;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.organization.service.OrganizationService;
import com.mannschaft.app.schedule.dto.AttendanceResponse;
import com.mannschaft.app.schedule.dto.AttendanceTeamBreakdownResponse;
import com.mannschaft.app.schedule.CalendarSyncScopeType;
import com.mannschaft.app.schedule.dto.CreateScheduleRequest;
import com.mannschaft.app.schedule.dto.ScheduleResponse;
import com.mannschaft.app.schedule.dto.UpdateScheduleRequest;
import com.mannschaft.app.schedule.service.ScheduleAttendanceService;
import com.mannschaft.app.schedule.service.ScheduleReminderService;
import com.mannschaft.app.schedule.service.ScheduleScheduledTaskService;
import com.mannschaft.app.schedule.service.ScheduleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import com.mannschaft.app.common.SecurityUtils;

/**
 * 組織スケジュールコントローラー。組織スコープのスケジュールCRUD・出欠管理APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/organizations/{orgPublicId}/schedules")
@Tag(name = "組織スケジュール管理", description = "F03.1 組織スコープのスケジュール・出欠管理")
@RequiredArgsConstructor
public class OrgScheduleController {

    private static final String SCOPE_TYPE_ORGANIZATION = "ORGANIZATION";

    private final ScheduleService scheduleService;
    private final ScheduleAttendanceService attendanceService;
    private final ScheduleReminderService reminderService;
    private final ScheduleScheduledTaskService scheduledTaskService;
    private final NameResolverService nameResolverService;
    private final OrganizationService organizationService;
    private final AccessControlService accessControlService;


    /**
     * 組織スケジュール一覧を取得する。
     */
    @GetMapping
    @Operation(summary = "組織スケジュール一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<ScheduleResponse>>> listSchedules(
            @PathVariable String orgPublicId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(required = false) String eventType,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String cursor) {
        Long orgId = organizationService.resolveOrgId(orgPublicId);
        List<ScheduleResponse> schedules = scheduleService.listOrgSchedules(
                orgId, from, to, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(schedules));
    }

    /**
     * 組織スケジュールを作成する。
     */
    @PostMapping
    @Operation(summary = "組織スケジュール作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<ScheduleResponse>> createSchedule(
            @PathVariable String orgPublicId,
            @Valid @RequestBody CreateScheduleRequest request) {
        Long orgId = organizationService.resolveOrgId(orgPublicId);
        ScheduleResponse response = scheduleService.createSchedule(
                request, orgId, SCOPE_TYPE_ORGANIZATION, SecurityUtils.getCurrentUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * 組織スケジュール詳細を取得する。
     */
    @GetMapping("/{scheduleId}")
    @Operation(summary = "組織スケジュール詳細")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<ScheduleResponse>> getSchedule(
            @PathVariable String orgPublicId,
            @PathVariable Long scheduleId) {
        Long orgId = organizationService.resolveOrgId(orgPublicId);
        var entity = scheduleService.getScheduleWithAccessCheck(scheduleId, SecurityUtils.getCurrentUserId());
        String createdByDisplayName = nameResolverService.resolveUserDisplayName(entity.getCreatedBy());
        String scopeName = nameResolverService.resolveScopeName(SCOPE_TYPE_ORGANIZATION, orgId);
        String scopeIconUrl = nameResolverService.resolveIconUrl(SCOPE_TYPE_ORGANIZATION, orgId);
        String myAttendanceStatus = attendanceService
                .getMyAttendanceStatus(scheduleId, SecurityUtils.getCurrentUserId())
                .orElse(null);
        ScheduleResponse response = ScheduleResponse.builder()
                .id(entity.getId())
                .content(new ScheduleResponse.ScheduleContentDto(
                        entity.getTitle(),
                        entity.getStatus().name(),
                        entity.getEventType().name(),
                        entity.getLocation(),
                        entity.getAttendanceRequired()))
                .time(new ScheduleResponse.ScheduleTimeDto(
                        entity.getStartAt(), entity.getEndAt(), entity.getAllDay()))
                .scope(new ScheduleResponse.ScheduleScopeDto(scopeName, scopeIconUrl))
                .academic(new ScheduleResponse.ScheduleAcademicDto(
                        null,
                        entity.getAcademicYear() != null ? entity.getAcademicYear().intValue() : null,
                        entity.getSourceScheduleId()))
                .audit(new ScheduleResponse.ScheduleAuditDto(entity.getCreatedAt(), createdByDisplayName))
                .myAttendanceStatus(myAttendanceStatus)
                .reminders(reminderService.getReminders(scheduleId))
                .scheduledTasks(scheduledTaskService.findTaskResponsesForSchedule(scheduleId))
                .build();
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 組織スケジュールの予約タスク（予約アンケート / 予約出欠募集）を取り消す（機能55 第三陣）。
     *
     * <p>PENDING（作成待ち）のタスクのみ取消可能。既に materialize 済み等は 409。</p>
     */
    @DeleteMapping("/{scheduleId}/scheduled-tasks/{taskId}")
    @Operation(summary = "予約タスク取消")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "取消成功")
    public ResponseEntity<Void> cancelScheduledTask(
            @PathVariable String orgPublicId,
            @PathVariable Long scheduleId,
            @PathVariable UUID taskId) {
        Long orgId = organizationService.resolveOrgId(orgPublicId);
        // 認可: 当該予定が閲覧可能か（CanView）を確認してから取消する（既存の予定操作と同等基準）
        scheduleService.getScheduleWithAccessCheck(scheduleId, SecurityUtils.getCurrentUserId());
        scheduledTaskService.cancelTask(taskId, CalendarSyncScopeType.ORGANIZATION, orgId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 組織スケジュールを更新する。
     */
    @PatchMapping("/{scheduleId}")
    @Operation(summary = "組織スケジュール更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<ScheduleResponse>> updateSchedule(
            @PathVariable String orgPublicId,
            @PathVariable Long scheduleId,
            @Valid @RequestBody UpdateScheduleRequest request,
            @RequestParam(defaultValue = "THIS_ONLY") String updateScope) {
        ScheduleResponse response = scheduleService.updateSchedule(
                scheduleId, request, updateScope, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 組織スケジュールを削除する（論理削除）。
     */
    @DeleteMapping("/{scheduleId}")
    @Operation(summary = "組織スケジュール削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteSchedule(
            @PathVariable String orgPublicId,
            @PathVariable Long scheduleId,
            @RequestParam(defaultValue = "THIS_ONLY") String updateScope) {
        scheduleService.deleteSchedule(scheduleId, updateScope);
        return ResponseEntity.noContent().build();
    }

    /**
     * 組織スケジュールをキャンセルする。
     */
    @PostMapping("/{scheduleId}/cancel")
    @Operation(summary = "組織スケジュールキャンセル")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "キャンセル成功")
    public ResponseEntity<Void> cancelSchedule(
            @PathVariable String orgPublicId,
            @PathVariable Long scheduleId) {
        scheduleService.cancelSchedule(scheduleId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.noContent().build();
    }

    /**
     * 組織スケジュールの出欠集計を取得する。
     */
    @GetMapping("/{scheduleId}/attendances")
    @Operation(summary = "組織出欠集計")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<AttendanceResponse>>> getAttendances(
            @PathVariable String orgPublicId,
            @PathVariable Long scheduleId) {
        List<AttendanceResponse> responses = attendanceService.getAttendances(scheduleId);
        return ResponseEntity.ok(ApiResponse.of(responses));
    }

    /**
     * 組織スケジュールの出欠一覧をCSVエクスポートする。
     */
    @GetMapping("/{scheduleId}/attendances/export")
    @Operation(summary = "組織出欠CSVエクスポート")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "エクスポート成功")
    public ResponseEntity<byte[]> exportAttendancesCsv(
            @PathVariable String orgPublicId,
            @PathVariable Long scheduleId) {
        String csv = attendanceService.exportAttendancesCsv(scheduleId);
        byte[] csvBytes = csv.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=attendances_" + scheduleId + ".csv")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(csvBytes);
    }

    /**
     * 組織スケジュールの出欠をチーム別内訳（by_team）で集計取得する
     * （(B) 組織→参加チーム配信 案C フェーズB・出欠のチーム別内訳）。
     *
     * <p>全体集計（{@code total}・実人数 DISTINCT）＋チーム別内訳（{@code by_team}・重複計上あり）を返す。
     * 作成時トグル {@code team_breakdown_enabled = TRUE} の組織スケジュールでのみ by_team を算出する。
     * トグル OFF（既定）は {@code by_team = null}（従来挙動＝全体集計のみ）。個別メンバーの出欠情報は含まない。</p>
     *
     * <p><b>認可</b>: チーム別内訳は組織の運用管理データのため、当該組織の ADMIN / DEPUTY_ADMIN のみ参照可能
     * （兄弟の組織管理 EP と同じ {@code checkAdminOrAbove} 正準パターン。F03.1 §6「組織レベルの出欠集計・
     * 個人名付き一覧は ADMIN のみ」に準拠）。非 ADMIN は 403（{@code COMMON_002}）。</p>
     */
    @GetMapping("/{scheduleId}/attendances/team-breakdown")
    @Operation(summary = "組織出欠チーム別内訳集計")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<AttendanceTeamBreakdownResponse>> getAttendanceTeamBreakdown(
            @PathVariable String orgPublicId,
            @PathVariable Long scheduleId) {
        Long orgId = organizationService.resolveOrgId(orgPublicId);
        accessControlService.checkAdminOrAbove(
                SecurityUtils.getCurrentUserId(), orgId, SCOPE_TYPE_ORGANIZATION);
        AttendanceTeamBreakdownResponse response = attendanceService.getAttendanceTeamBreakdown(scheduleId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 組織スケジュールの出欠チーム別内訳をCSVエクスポートする
     * （F03.1: {@code チーム名,出席,一部参加,欠席,未回答,合計} ＋末尾「合計」行）。
     *
     * <p><b>認可</b>: 集計 EP と同じく当該組織の ADMIN / DEPUTY_ADMIN のみ。非 ADMIN は 403。</p>
     */
    @GetMapping("/{scheduleId}/attendances/team-breakdown/export")
    @Operation(summary = "組織出欠チーム別内訳CSVエクスポート")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "エクスポート成功")
    public ResponseEntity<byte[]> exportAttendanceTeamBreakdownCsv(
            @PathVariable String orgPublicId,
            @PathVariable Long scheduleId) {
        Long orgId = organizationService.resolveOrgId(orgPublicId);
        accessControlService.checkAdminOrAbove(
                SecurityUtils.getCurrentUserId(), orgId, SCOPE_TYPE_ORGANIZATION);
        String csv = attendanceService.exportAttendanceTeamBreakdownCsv(scheduleId);
        byte[] csvBytes = csv.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=org_attendance_team_breakdown_" + scheduleId + ".csv")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(csvBytes);
    }

    /**
     * 組織スケジュールを複製する。
     */
    @PostMapping("/{scheduleId}/duplicate")
    @Operation(summary = "組織スケジュール複製")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "複製成功")
    public ResponseEntity<ApiResponse<ScheduleResponse>> duplicateSchedule(
            @PathVariable String orgPublicId,
            @PathVariable Long scheduleId) {
        ScheduleResponse response = scheduleService.duplicateSchedule(scheduleId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }
}
