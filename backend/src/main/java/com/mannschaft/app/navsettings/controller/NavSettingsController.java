package com.mannschaft.app.navsettings.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.navsettings.dto.NavSettingsResponse;
import com.mannschaft.app.navsettings.dto.UpdateNavSettingsRequest;
import com.mannschaft.app.navsettings.service.NavSettingsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/settings/nav")
@Tag(name = "ナビゲーション設定", description = "F20.1 個人ナビゲーション表示設定")
@RequiredArgsConstructor
public class NavSettingsController {

    private final NavSettingsService navSettingsService;

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "ナビ設定取得", description = "現在ユーザーのナビ項目表示設定を返す。is_enabled=TRUE の項目のみ。")
    public ResponseEntity<ApiResponse<NavSettingsResponse>> getNavSettings() {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(navSettingsService.getMyNavSettings(userId)));
    }

    @PutMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "ナビ設定更新",
            description = "非表示にするナビキーリストと個人並び順を全量上書き保存する。navDisplayOrder 省略時はマスタ順にリセット。")
    public ResponseEntity<Void> updateNavSettings(@Valid @RequestBody UpdateNavSettingsRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        navSettingsService.updateMyNavSettings(userId, request.getHiddenNavKeys(), request.getNavDisplayOrder());
        return ResponseEntity.noContent().build();
    }
}
