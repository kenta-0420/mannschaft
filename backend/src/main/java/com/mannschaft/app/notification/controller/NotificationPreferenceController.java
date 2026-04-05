package com.mannschaft.app.notification.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.notification.dto.PreferenceResponse;
import com.mannschaft.app.notification.dto.PreferenceUpdateRequest;
import com.mannschaft.app.notification.dto.TypePreferenceBulkUpdateRequest;
import com.mannschaft.app.notification.dto.TypePreferenceResponse;
import com.mannschaft.app.notification.service.NotificationPreferenceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import com.mannschaft.app.common.SecurityUtils;

/**
 * 通知設定コントローラー。通知設定・通知種別設定の管理APIを提供する。
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "通知設定", description = "F04.3 通知設定管理")
@RequiredArgsConstructor
public class NotificationPreferenceController {

    private final NotificationPreferenceService preferenceService;


    /**
     * 通知設定一覧を取得する。
     */
    @GetMapping("/notification-preferences")
    @Operation(summary = "通知設定一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<PreferenceResponse>>> listPreferences() {
        List<PreferenceResponse> responses = preferenceService.listPreferences(SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(responses));
    }

    /**
     * 通知設定を更新する。
     */
    @PutMapping("/notification-preferences")
    @Operation(summary = "通知設定更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<PreferenceResponse>> updatePreference(
            @Valid @RequestBody PreferenceUpdateRequest request) {
        PreferenceResponse response = preferenceService.updatePreference(SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 通知種別設定一覧を取得する。
     */
    @GetMapping("/notification-type-preferences")
    @Operation(summary = "通知種別設定一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<TypePreferenceResponse>>> listTypePreferences() {
        List<TypePreferenceResponse> responses = preferenceService.listTypePreferences(SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(responses));
    }

    /**
     * 通知種別設定を一括更新する。
     */
    @PutMapping("/notification-type-preferences")
    @Operation(summary = "通知種別設定一括更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<List<TypePreferenceResponse>>> bulkUpdateTypePreferences(
            @Valid @RequestBody TypePreferenceBulkUpdateRequest request) {
        List<TypePreferenceResponse> responses =
                preferenceService.bulkUpdateTypePreferences(SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.ok(ApiResponse.of(responses));
    }
}
