package com.mannschaft.app.matching.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.matching.dto.NotificationPreferenceResponse;
import com.mannschaft.app.matching.dto.UpdateNotificationPreferenceRequest;
import com.mannschaft.app.matching.service.MatchNotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * マッチング推薦通知設定コントローラー。通知設定の取得・更新APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}/matching/notification-preferences")
@Tag(name = "マッチング通知設定", description = "F08.1 マッチング推薦通知設定")
@RequiredArgsConstructor
public class MatchNotificationController {

    private final MatchNotificationService notificationService;

    /**
     * 推薦通知設定の取得。
     */
    @GetMapping
    @Operation(summary = "推薦通知設定の取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<NotificationPreferenceResponse>> getPreference(
            @PathVariable Long teamId) {
        NotificationPreferenceResponse response = notificationService.getPreference(teamId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 推薦通知設定の更新（UPSERT）。
     */
    @PutMapping
    @Operation(summary = "推薦通知設定の更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<NotificationPreferenceResponse>> updatePreference(
            @PathVariable Long teamId,
            @RequestBody UpdateNotificationPreferenceRequest request) {
        NotificationPreferenceResponse response = notificationService.updatePreference(teamId, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
