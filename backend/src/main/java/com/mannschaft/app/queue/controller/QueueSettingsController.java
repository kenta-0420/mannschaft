package com.mannschaft.app.queue.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.queue.QueueScopeType;
import com.mannschaft.app.queue.dto.QueueSettingsRequest;
import com.mannschaft.app.queue.dto.SettingsResponse;
import com.mannschaft.app.queue.service.QueueSettingsService;
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
 * 順番待ち設定コントローラー。スコープ単位の設定取得・更新APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}/queue/settings")
@Tag(name = "順番待ち設定管理", description = "F03.7 順番待ち設定の取得・更新")
@RequiredArgsConstructor
public class QueueSettingsController {

    private final QueueSettingsService settingsService;

    /**
     * 順番待ち設定を取得する。
     */
    @GetMapping
    @Operation(summary = "設定取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<SettingsResponse>> getSettings(
            @PathVariable Long teamId) {
        SettingsResponse settings = settingsService.getSettings(
                QueueScopeType.TEAM, teamId);
        return ResponseEntity.ok(ApiResponse.of(settings));
    }

    /**
     * 順番待ち設定を更新する。
     */
    @PatchMapping
    @Operation(summary = "設定更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<SettingsResponse>> updateSettings(
            @PathVariable Long teamId,
            @Valid @RequestBody QueueSettingsRequest request) {
        SettingsResponse settings = settingsService.updateSettings(
                QueueScopeType.TEAM, teamId, request);
        return ResponseEntity.ok(ApiResponse.of(settings));
    }
}
