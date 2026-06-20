package com.mannschaft.app.reflection.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.reflection.dto.ReflectionSettingsResponse;
import com.mannschaft.app.reflection.dto.UpdateReflectionSettingsRequest;
import com.mannschaft.app.reflection.service.ReflectionSettingsService;
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

/**
 * F06.5 想起通知設定コントローラー（§2.7 / §7 #14〜#15）。
 */
@RestController
@RequestMapping("/api/v1/me/reflections/settings")
@Tag(name = "振り返り通知設定", description = "F06.5 アクティブリコール学習機能 — 想起通知の時刻設定")
@RequiredArgsConstructor
public class ReflectionSettingsController {

    private final ReflectionSettingsService reflectionSettingsService;

    /** 想起通知設定取得（§7 #14・remind_hour・未設定は既定 8 時）。 */
    @GetMapping
    @Operation(summary = "想起通知設定取得")
    public ResponseEntity<ApiResponse<ReflectionSettingsResponse>> getSettings() {
        ReflectionSettingsResponse result =
                reflectionSettingsService.getSettings(SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(result));
    }

    /** 想起通知設定更新（§7 #15・remind_hour 0-23）。 */
    @PutMapping
    @Operation(summary = "想起通知設定更新")
    public ResponseEntity<ApiResponse<ReflectionSettingsResponse>> updateSettings(
            @Valid @RequestBody UpdateReflectionSettingsRequest request) {
        ReflectionSettingsResponse result =
                reflectionSettingsService.updateSettings(SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.ok(ApiResponse.of(result));
    }
}
