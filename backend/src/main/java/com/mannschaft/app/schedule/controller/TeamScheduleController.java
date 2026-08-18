package com.mannschaft.app.schedule.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.schedule.dto.AttendanceResponse;
import com.mannschaft.app.schedule.dto.BulkAttendanceRequest;
import com.mannschaft.app.schedule.dto.CreateScheduleRequest;
import com.mannschaft.app.schedule.dto.CrossInviteRequest;
import com.mannschaft.app.schedule.CalendarSyncScopeType;
import com.mannschaft.app.schedule.dto.CrossRefResponse;
import com.mannschaft.app.schedule.dto.ScheduleResponse;
import com.mannschaft.app.schedule.dto.UpdateScheduleRequest;
import com.mannschaft.app.schedule.service.ScheduleAttendanceService;
import com.mannschaft.app.schedule.service.ScheduleCrossRefService;
import com.mannschaft.app.schedule.service.ScheduleReminderService;
import com.mannschaft.app.schedule.service.ScheduleScheduledTaskService;
import com.mannschaft.app.schedule.service.ScheduleService;
import com.mannschaft.app.team.service.TeamService;
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
 * チームスケジュールコントローラー。チームスコープのスケジュールCRUD・出欠管理・クロス招待APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/teams/{teamPublicId}/schedules")
@Tag(name = "チームスケジュール管理", description = "F03.1 チームスコープのスケジュール・出欠管理")
@RequiredArgsConstructor
public class TeamScheduleController {

    private static final String SCOPE_TYPE_TEAM = "TEAM";

    private final ScheduleService scheduleService;
    private final ScheduleAttendanceService attendanceService;
    private final ScheduleCrossRefService crossRefService;
    private final ScheduleReminderService reminderService;
    private final ScheduleScheduledTaskService scheduledTaskService;
    private final NameResolverService nameResolverService;
    private final TeamService teamService;


    /**
     * チームスケジュール一覧を取得する。
     */
    @GetMapping
    @Operation(summary = "チームスケジュール一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<ScheduleResponse>>> listSchedules(
            @PathVariable String teamPublicId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(required = false) String eventType,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String cursor) {
        Long teamId = teamService.resolveTeamId(teamPublicId);
        List<ScheduleResponse> schedules = scheduleService.listTeamSchedules(
                teamId, from, to, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(schedules));
    }

    /**
     * チームスケジュールを作成する。
     */
    @PostMapping
    @Operation(summary = "チームスケジュール作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<ScheduleResponse>> createSchedule(
            @PathVariable String teamPublicId,
            @Valid @RequestBody CreateScheduleRequest request) {
        Long teamId = teamService.resolveTeamId(teamPublicId);
        ScheduleResponse response = scheduleService.createSchedule(
                request, teamId, SCOPE_TYPE_TEAM, SecurityUtils.getCurrentUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * チームスケジュール詳細を取得する。
     */
    @GetMapping("/{scheduleId}")
    @Operation(summary = "チームスケジュール詳細")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<ScheduleResponse>> getSchedule(
            @PathVariable String teamPublicId,
            @PathVariable Long scheduleId) {
        Long teamId = teamService.resolveTeamId(teamPublicId);
        var entity = scheduleService.getScheduleWithAccessCheck(scheduleId, SecurityUtils.getCurrentUserId());
        String createdByDisplayName = nameResolverService.resolveUserDisplayName(entity.getCreatedBy());
        String scopeName = nameResolverService.resolveScopeName(SCOPE_TYPE_TEAM, teamId);
        String scopeIconUrl = nameResolverService.resolveIconUrl(SCOPE_TYPE_TEAM, teamId);
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
                .targets(scheduleService.targetResponseForViewer(entity, SecurityUtils.getCurrentUserId()))
                .reminders(reminderService.getReminders(scheduleId))
                .scheduledTasks(scheduledTaskService.findTaskResponsesForSchedule(scheduleId))
                .build();
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * チームスケジュールの予約タスク（予約アンケート / 予約出欠募集）を取り消す（機能55 第三陣）。
     *
     * <p>PENDING（作成待ち）のタスクのみ取消可能。既に materialize 済み等は 409。</p>
     */
    @DeleteMapping("/{scheduleId}/scheduled-tasks/{taskId}")
    @Operation(summary = "予約タスク取消")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "取消成功")
    public ResponseEntity<Void> cancelScheduledTask(
            @PathVariable String teamPublicId,
            @PathVariable Long scheduleId,
            @PathVariable UUID taskId) {
        Long teamId = teamService.resolveTeamId(teamPublicId);
        // 認可: 当該予定が閲覧可能か（CanView）を確認してから取消する（既存の予定操作と同等基準）
        scheduleService.getScheduleWithAccessCheck(scheduleId, SecurityUtils.getCurrentUserId());
        scheduledTaskService.cancelTask(taskId, CalendarSyncScopeType.TEAM, teamId);
        return ResponseEntity.noContent().build();
    }

    /**
     * チームスケジュールを更新する。
     */
    @PatchMapping("/{scheduleId}")
    @Operation(summary = "チームスケジュール更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<ScheduleResponse>> updateSchedule(
            @PathVariable String teamPublicId,
            @PathVariable Long scheduleId,
            @Valid @RequestBody UpdateScheduleRequest request,
            @RequestParam(defaultValue = "THIS_ONLY") String updateScope) {
        ScheduleResponse response = scheduleService.updateSchedule(
                scheduleId, request, updateScope, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * チームスケジュールを削除する（論理削除）。
     */
    @DeleteMapping("/{scheduleId}")
    @Operation(summary = "チームスケジュール削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteSchedule(
            @PathVariable String teamPublicId,
            @PathVariable Long scheduleId,
            @RequestParam(defaultValue = "THIS_ONLY") String updateScope) {
        scheduleService.deleteSchedule(scheduleId, updateScope, SecurityUtils.getCurrentUserId());
        return ResponseEntity.noContent().build();
    }

    /**
     * チームスケジュールをキャンセルする。
     */
    @PostMapping("/{scheduleId}/cancel")
    @Operation(summary = "チームスケジュールキャンセル")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "キャンセル成功")
    public ResponseEntity<Void> cancelSchedule(
            @PathVariable String teamPublicId,
            @PathVariable Long scheduleId) {
        scheduleService.cancelSchedule(scheduleId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.noContent().build();
    }

    /**
     * チームスケジュールの出欠一覧を取得する。
     *
     * <p><b>認可（認可根治 Wave3-B6）</b>: 個人名付き出欠一覧の漏洩を防ぐため、当該スケジュールが
     * 属するチームのメンバーのみ閲覧可（entity 由来 scope・{@code checkMembership} 水準）。</p>
     */
    @GetMapping("/{scheduleId}/attendances")
    @Operation(summary = "チーム出欠一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<AttendanceResponse>>> getAttendances(
            @PathVariable String teamPublicId,
            @PathVariable Long scheduleId) {
        List<AttendanceResponse> responses =
                attendanceService.getAttendances(scheduleId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(responses));
    }

    /**
     * チームスケジュールの出欠を一括更新する（管理者用）。
     *
     * <p><b>認可（認可根治 Wave3-B6）</b>: 「管理者用」の doc どおり、当該スケジュールが属する
     * チーム/組織の ADMIN/DEPUTY_ADMIN のみ実行可（entity 由来 scope・{@code checkAdminOrAbove} 水準）。
     * 従来は認可ゼロで一般メンバーも一括上書きできていた欠陥を是正。</p>
     */
    @PatchMapping("/{scheduleId}/attendances/bulk")
    @Operation(summary = "チーム出欠一括更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "更新成功")
    public ResponseEntity<Void> bulkUpdateAttendances(
            @PathVariable String teamPublicId,
            @PathVariable Long scheduleId,
            @Valid @RequestBody BulkAttendanceRequest request) {
        // 他者の出欠を書き換える操作のため、スケジュール実体由来のスコープに対する
        // ADMIN/DEPUTY_ADMIN 判定を public 入口で行う（sendReminder と同じ流儀）。
        scheduleService.checkScopeAdminAccess(scheduleId, SecurityUtils.getCurrentUserId());
        attendanceService.bulkUpdateAttendances(scheduleId, request, SecurityUtils.getCurrentUserId());
        return ResponseEntity.noContent().build();
    }

    /**
     * チームスケジュールの出欠一覧をCSVエクスポートする。
     *
     * <p><b>認可（認可根治 Wave3-B6）</b>: getAttendances と同じく当該チームのメンバーのみ。</p>
     */
    @GetMapping("/{scheduleId}/attendances/export")
    @Operation(summary = "チーム出欠CSVエクスポート")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "エクスポート成功")
    public ResponseEntity<byte[]> exportAttendancesCsv(
            @PathVariable String teamPublicId,
            @PathVariable Long scheduleId) {
        String csv = attendanceService.exportAttendancesCsv(scheduleId, SecurityUtils.getCurrentUserId());
        byte[] csvBytes = csv.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=attendances_" + scheduleId + ".csv")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(csvBytes);
    }

    /**
     * チームスケジュールを複製する。
     *
     * <p><b>認可（認可根治 Wave3-B6・BOLA是正）</b>: {@code ScheduleService.duplicateSchedule} は
     * クロス招待受諾（{@code ScheduleCrossRefService.acceptInvitation}）からも呼ばれる共有メソッドで
     * 認可を持たないため、この public な複製 API 入口で複製元(source)の entity 由来 scope に対する
     * ADMIN 認可を行う（他 team/org の scheduleId を渡す複製元なりすましを防ぐ）。</p>
     */
    @PostMapping("/{scheduleId}/duplicate")
    @Operation(summary = "チームスケジュール複製")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "複製成功")
    public ResponseEntity<ApiResponse<ScheduleResponse>> duplicateSchedule(
            @PathVariable String teamPublicId,
            @PathVariable Long scheduleId) {
        Long userId = SecurityUtils.getCurrentUserId();
        scheduleService.checkScopeAdminAccess(scheduleId, userId);
        ScheduleResponse response = scheduleService.duplicateSchedule(scheduleId, userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * クロスチーム・組織招待を送信する。
     */
    @PostMapping("/{scheduleId}/cross-invite")
    @Operation(summary = "クロス招待送信")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "招待送信成功")
    public ResponseEntity<ApiResponse<CrossRefResponse>> sendCrossInvite(
            @PathVariable String teamPublicId,
            @PathVariable Long scheduleId,
            @Valid @RequestBody CrossInviteRequest request) {
        CrossRefResponse response = crossRefService.sendCrossInvite(
                scheduleId, request, SecurityUtils.getCurrentUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * クロス招待をキャンセルする。
     */
    @DeleteMapping("/{scheduleId}/cross-invite/{invitationId}")
    @Operation(summary = "クロス招待キャンセル")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "キャンセル成功")
    public ResponseEntity<Void> cancelCrossInvite(
            @PathVariable String teamPublicId,
            @PathVariable Long scheduleId,
            @PathVariable Long invitationId) {
        crossRefService.cancelCrossInvite(invitationId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.noContent().build();
    }
}
