package com.mannschaft.app.schedule.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.schedule.dto.CalendarSyncSettingsResponse;
import com.mannschaft.app.schedule.dto.CalendarSyncToggleRequest;
import com.mannschaft.app.schedule.dto.CalendarSyncToggleResponse;
import com.mannschaft.app.schedule.dto.GoogleCalendarConnectRequest;
import com.mannschaft.app.schedule.dto.GoogleCalendarConnectResponse;
import com.mannschaft.app.schedule.dto.GoogleCalendarStatusResponse;
import com.mannschaft.app.schedule.dto.ManualSyncResponse;
import com.mannschaft.app.schedule.dto.PersonalSyncToggleResponse;
import com.mannschaft.app.schedule.service.GoogleCalendarService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.mannschaft.app.common.SecurityUtils;

/**
 * Google Calendar同期コントローラー。OAuth連携・同期設定・手動再同期APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/me")
@Tag(name = "Google Calendar同期", description = "F03.3 Google Calendar連携・同期設定管理")
@RequiredArgsConstructor
public class GoogleCalendarController {

    private final GoogleCalendarService googleCalendarService;


    /**
     * Google Calendar連携状態を取得する。
     */
    @GetMapping("/google-calendar/status")
    @Operation(summary = "Google Calendar連携状態取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<GoogleCalendarStatusResponse>> getConnectionStatus() {
        GoogleCalendarStatusResponse response = googleCalendarService.getConnectionStatus(SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * Google Calendar OAuth連携を実行する。
     */
    @PostMapping("/google-calendar/connect")
    @Operation(summary = "Google Calendar OAuth連携")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "連携成功")
    public ResponseEntity<ApiResponse<GoogleCalendarConnectResponse>> connect(
            @Valid @RequestBody GoogleCalendarConnectRequest request) {
        GoogleCalendarConnectResponse response = googleCalendarService.connect(request, SecurityUtils.getCurrentUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * Google Calendar連携を解除する。
     */
    @DeleteMapping("/google-calendar/disconnect")
    @Operation(summary = "Google Calendar連携解除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "解除成功")
    public ResponseEntity<Void> disconnect() {
        googleCalendarService.disconnect(SecurityUtils.getCurrentUserId());
        return ResponseEntity.noContent().build();
    }

    /**
     * カレンダー同期設定一覧を取得する。
     */
    @GetMapping("/calendar-sync-settings")
    @Operation(summary = "カレンダー同期設定一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<CalendarSyncSettingsResponse>> getSyncSettings() {
        CalendarSyncSettingsResponse response = googleCalendarService.getSyncSettings(SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * チームスコープのカレンダー同期をON/OFFする。
     */
    @PutMapping("/teams/{teamId}/calendar-sync")
    @Operation(summary = "チームカレンダー同期ON/OFF")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "設定変更成功")
    public ResponseEntity<ApiResponse<CalendarSyncToggleResponse>> toggleTeamSync(
            @PathVariable Long teamId,
            @Valid @RequestBody CalendarSyncToggleRequest request) {
        CalendarSyncToggleResponse response = googleCalendarService.toggleTeamSync(
                teamId, request.getIsEnabled(), SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 組織スコープのカレンダー同期をON/OFFする。
     */
    @PutMapping("/organizations/{orgId}/calendar-sync")
    @Operation(summary = "組織カレンダー同期ON/OFF")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "設定変更成功")
    public ResponseEntity<ApiResponse<CalendarSyncToggleResponse>> toggleOrgSync(
            @PathVariable Long orgId,
            @Valid @RequestBody CalendarSyncToggleRequest request) {
        CalendarSyncToggleResponse response = googleCalendarService.toggleOrgSync(
                orgId, request.getIsEnabled(), SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 個人スケジュールのカレンダー同期をON/OFFする。
     */
    @PutMapping("/google-calendar/personal-sync")
    @Operation(summary = "個人カレンダー同期ON/OFF")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "設定変更成功")
    public ResponseEntity<ApiResponse<PersonalSyncToggleResponse>> togglePersonalSync(
            @Valid @RequestBody CalendarSyncToggleRequest request) {
        PersonalSyncToggleResponse response = googleCalendarService.togglePersonalSync(
                request.getIsEnabled(), SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 手動再同期を実行する。
     */
    @PostMapping("/google-calendar/sync")
    @Operation(summary = "手動再同期")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "202", description = "再同期開始")
    public ResponseEntity<ApiResponse<ManualSyncResponse>> manualSync() {
        ManualSyncResponse response = googleCalendarService.manualSync(SecurityUtils.getCurrentUserId());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.of(response));
    }
}
