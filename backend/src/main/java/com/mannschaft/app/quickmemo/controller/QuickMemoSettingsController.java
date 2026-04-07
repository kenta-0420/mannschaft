package com.mannschaft.app.quickmemo.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.quickmemo.dto.UpdateSettingsRequest;
import com.mannschaft.app.quickmemo.dto.UserQuickMemoSettingsResponse;
import com.mannschaft.app.quickmemo.service.UserQuickMemoSettingsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * ポイっとメモ設定 コントローラー。
 * リマインドデフォルト設定の取得・更新を担当する。
 */
@RestController
@RequestMapping("/api/v1/quick-memos/settings")
@Tag(name = "ポイっとメモ設定", description = "F02.5 リマインド設定管理")
@RequiredArgsConstructor
public class QuickMemoSettingsController {

    private final UserQuickMemoSettingsService settingsService;

    @GetMapping
    @Operation(summary = "リマインド設定取得")
    public ResponseEntity<ApiResponse<UserQuickMemoSettingsResponse>> getSettings() {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.success(settingsService.getSettings(userId)));
    }

    @PutMapping
    @Operation(summary = "リマインド設定更新",
               description = "apply_to: NEW_ONLY（新規メモのみ）/ UNSENT（未送信枠を再計算）/ ALL（全未整理メモを再計算）")
    public ResponseEntity<ApiResponse<UserQuickMemoSettingsResponse>> updateSettings(
            @Valid @RequestBody UpdateSettingsRequest request,
            @RequestParam(defaultValue = "NEW_ONLY") String apply_to) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.success(settingsService.updateSettings(userId, request, apply_to)));
    }
}
