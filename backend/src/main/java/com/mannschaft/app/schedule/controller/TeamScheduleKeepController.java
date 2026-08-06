package com.mannschaft.app.schedule.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.schedule.authz.ScheduleKeepScope;
import com.mannschaft.app.schedule.dto.CreateScheduleKeepRequest;
import com.mannschaft.app.schedule.dto.ScheduleKeepResponse;
import com.mannschaft.app.schedule.service.ScheduleKeepService;
import com.mannschaft.app.team.service.TeamService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * チームスコープのキープ（日付未定の予定）コントローラー（F03.17 §4.1・第三陣）。
 *
 * <p>認可は本クラスでは判定せず、{@code ScheduleKeepService} 経由で
 * {@code ScheduleKeepAccessGuard} を必ず通す（memory
 * {@code feedback_authz_gate_on_public_entry_not_shared_method} に従い public 入口ごとに
 * ガードへ到達することを保証する）。</p>
 */
@RestController
@RequestMapping("/api/v1/teams/{teamPublicId}/schedule-keeps")
@Tag(name = "チームキープ管理", description = "F03.17 チームスコープのキープ（日付未定の予定）CRUD")
@RequiredArgsConstructor
public class TeamScheduleKeepController {

    private final ScheduleKeepService scheduleKeepService;
    private final TeamService teamService;

    @PostMapping
    @Operation(summary = "チームキープ作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<ScheduleKeepResponse>> create(
            @PathVariable String teamPublicId,
            @RequestBody CreateScheduleKeepRequest request) {
        Long teamId = teamService.resolveTeamId(teamPublicId);
        ScheduleKeepResponse response = scheduleKeepService.create(
                ScheduleKeepScope.team(teamId), request, SecurityUtils.getCurrentUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    @GetMapping
    @Operation(summary = "チームキープ一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<ScheduleKeepResponse>>> list(
            @PathVariable String teamPublicId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long teamId = teamService.resolveTeamId(teamPublicId);
        List<ScheduleKeepResponse> response = scheduleKeepService.list(
                ScheduleKeepScope.team(teamId), status, page, size, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    @GetMapping("/{keepId}")
    @Operation(summary = "チームキープ詳細")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<ScheduleKeepResponse>> get(
            @PathVariable String teamPublicId,
            @PathVariable UUID keepId) {
        Long teamId = teamService.resolveTeamId(teamPublicId);
        ScheduleKeepResponse response = scheduleKeepService.get(
                ScheduleKeepScope.team(teamId), keepId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    @PatchMapping("/{keepId}")
    @Operation(summary = "チームキープ更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<ScheduleKeepResponse>> update(
            @PathVariable String teamPublicId,
            @PathVariable UUID keepId,
            @RequestBody Map<String, Object> body) {
        Long teamId = teamService.resolveTeamId(teamPublicId);
        ScheduleKeepResponse response = scheduleKeepService.update(
                ScheduleKeepScope.team(teamId), keepId, body, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    @DeleteMapping("/{keepId}")
    @Operation(summary = "チームキープ削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> delete(
            @PathVariable String teamPublicId,
            @PathVariable UUID keepId) {
        Long teamId = teamService.resolveTeamId(teamPublicId);
        scheduleKeepService.delete(ScheduleKeepScope.team(teamId), keepId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{keepId}/archive")
    @Operation(summary = "チームキープをアーカイブ")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "アーカイブ成功")
    public ResponseEntity<ApiResponse<ScheduleKeepResponse>> archive(
            @PathVariable String teamPublicId,
            @PathVariable UUID keepId) {
        Long teamId = teamService.resolveTeamId(teamPublicId);
        ScheduleKeepResponse response = scheduleKeepService.archive(
                ScheduleKeepScope.team(teamId), keepId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    @PostMapping("/{keepId}/restore")
    @Operation(summary = "チームキープのアーカイブ解除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "復帰成功")
    public ResponseEntity<ApiResponse<ScheduleKeepResponse>> restore(
            @PathVariable String teamPublicId,
            @PathVariable UUID keepId) {
        Long teamId = teamService.resolveTeamId(teamPublicId);
        ScheduleKeepResponse response = scheduleKeepService.restore(
                ScheduleKeepScope.team(teamId), keepId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    @PostMapping("/{keepId}/revert")
    @Operation(summary = "チームキープの変換取消")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取消成功")
    public ResponseEntity<ApiResponse<ScheduleKeepResponse>> revert(
            @PathVariable String teamPublicId,
            @PathVariable UUID keepId) {
        Long teamId = teamService.resolveTeamId(teamPublicId);
        ScheduleKeepResponse response = scheduleKeepService.revert(
                ScheduleKeepScope.team(teamId), keepId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
