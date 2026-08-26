package com.mannschaft.app.team.controller;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.team.dto.TeamShiftSettingsResponse;
import com.mannschaft.app.team.dto.UpdateTeamShiftSettingsRequest;
import com.mannschaft.app.team.service.TeamService;
import com.mannschaft.app.team.service.TeamShiftSettingsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * チームシフト設定コントローラー。リマインド間隔カスタマイズのAPIエンドポイントを提供する。
 */
@RestController
@RequestMapping("/api/v1/teams/{slug}/shift-settings")
@Tag(name = "チームシフト設定")
@RequiredArgsConstructor
public class TeamShiftSettingsController {

    private static final String SCOPE_TYPE = "TEAM";

    private final TeamShiftSettingsService settingsService;
    private final TeamService teamService;
    private final AccessControlService accessControlService;

    @GetMapping
    @Operation(summary = "チームシフト設定取得（メンバー限定）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
            description = "当該チームのメンバーでない")
    public ResponseEntity<TeamShiftSettingsResponse> getSettings(@PathVariable String slug) {
        Long teamId = teamService.resolveTeamId(slug);
        // shift ドメインの既定の流儀（参照=checkMembership / 変更=checkAdminOrAbove。
        // 例: MemberWorkConstraintService / ShiftScheduleService）に揃える。
        accessControlService.checkMembership(SecurityUtils.getCurrentUserId(), teamId, SCOPE_TYPE);
        return ResponseEntity.ok(settingsService.getSettings(teamId));
    }

    @PatchMapping
    @Operation(summary = "チームシフト設定更新（ADMIN/DEPUTY のみ）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
            description = "当該チームの ADMIN/DEPUTY でない")
    public ResponseEntity<TeamShiftSettingsResponse> updateSettings(
            @PathVariable String slug,
            @Valid @RequestBody UpdateTeamShiftSettingsRequest request) {
        Long teamId = teamService.resolveTeamId(slug);
        // 設定変更はチーム運営の権限（shift ドメインの変更系と同じ粒度）。
        accessControlService.checkAdminOrAbove(SecurityUtils.getCurrentUserId(), teamId, SCOPE_TYPE);
        return ResponseEntity.ok(settingsService.updateSettings(teamId, request));
    }
}
