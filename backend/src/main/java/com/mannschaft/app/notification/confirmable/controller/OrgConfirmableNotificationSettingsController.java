package com.mannschaft.app.notification.confirmable.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.membership.ScopeType;
import com.mannschaft.app.notification.confirmable.dto.ConfirmableNotificationSettingsResponse;
import com.mannschaft.app.notification.confirmable.dto.ConfirmableNotificationSettingsUpdateRequest;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationSettingsEntity;
import com.mannschaft.app.notification.confirmable.mapper.ConfirmableNotificationMapper;
import com.mannschaft.app.notification.confirmable.service.ConfirmableNotificationSettingsService;
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
 * F04.9 組織確認通知設定コントローラー。
 *
 * <p>組織のデフォルトリマインド設定・送信者アラート閾値の取得・更新APIを提供する。</p>
 */
@RestController
@RequestMapping("/api/v1/organizations/{orgId}/confirmable-notification-settings")
@Tag(name = "確認通知設定（組織）", description = "F04.9 組織確認通知設定（リマインド・アラート閾値）")
@RequiredArgsConstructor
public class OrgConfirmableNotificationSettingsController {

    private final ConfirmableNotificationSettingsService settingsService;
    private final ConfirmableNotificationMapper mapper;

    /**
     * 組織の確認通知設定を取得する（存在しない場合はデフォルト値で作成）。
     */
    @GetMapping
    @Operation(summary = "確認通知設定取得（組織）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<ConfirmableNotificationSettingsResponse>> getSettings(
            @PathVariable Long orgId) {
        ConfirmableNotificationSettingsEntity entity =
                settingsService.getOrCreate(ScopeType.ORGANIZATION, orgId);
        return ResponseEntity.ok(ApiResponse.of(mapper.toSettingsResponse(entity)));
    }

    /**
     * 組織の確認通知設定を更新する。
     */
    @PutMapping
    @Operation(summary = "確認通知設定更新（組織）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<ConfirmableNotificationSettingsResponse>> updateSettings(
            @PathVariable Long orgId,
            @RequestBody ConfirmableNotificationSettingsUpdateRequest request) {
        ConfirmableNotificationSettingsEntity entity = settingsService.update(
                ScopeType.ORGANIZATION,
                orgId,
                request.getDefaultFirstReminderMinutes(),
                request.getDefaultSecondReminderMinutes(),
                request.getSenderAlertThresholdPercent());
        return ResponseEntity.ok(ApiResponse.of(mapper.toSettingsResponse(entity)));
    }
}
