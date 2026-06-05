package com.mannschaft.app.team.controller;

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

import java.util.UUID;

/**
 * チームシフト設定コントローラー。リマインド間隔カスタマイズのAPIエンドポイントを提供する。
 */
@RestController
@RequestMapping("/api/v1/teams/{publicId}/shift-settings")
@Tag(name = "チームシフト設定")
@RequiredArgsConstructor
public class TeamShiftSettingsController {

    private final TeamShiftSettingsService settingsService;
    private final TeamService teamService;

    @GetMapping
    @Operation(summary = "チームシフト設定取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<TeamShiftSettingsResponse> getSettings(@PathVariable UUID publicId) {
        Long teamId = teamService.resolveTeamId(publicId);
        return ResponseEntity.ok(settingsService.getSettings(teamId));
    }

    @PatchMapping
    @Operation(summary = "チームシフト設定更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<TeamShiftSettingsResponse> updateSettings(
            @PathVariable UUID publicId,
            @Valid @RequestBody UpdateTeamShiftSettingsRequest request) {
        Long teamId = teamService.resolveTeamId(publicId);
        return ResponseEntity.ok(settingsService.updateSettings(teamId, request));
    }
}
