package com.mannschaft.app.appearance.controller;

import com.mannschaft.app.appearance.dto.AppearanceResponse;
import com.mannschaft.app.appearance.dto.UpdateAppearanceRequest;
import com.mannschaft.app.appearance.service.AppearanceSettingsService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * F11.4 外観テーマ設定 — コントローラー。
 *
 * <p>エンドポイント:</p>
 * <ul>
 *   <li>GET  /api/v1/settings/appearance — 現在ユーザーの外観設定取得（未登録時はデフォルト値）</li>
 *   <li>PUT  /api/v1/settings/appearance — 現在ユーザーの外観設定保存（upsert）</li>
 * </ul>
 *
 * <p><b>セキュリティ</b>: {@code userId} は URL / リクエストボディから取得せず、
 * 必ず {@link SecurityUtils#getCurrentUserId()} 由来とする。
 * これにより他人の設定への不正アクセスを構造的に排除する（IDOR 防止）。</p>
 */
@RestController
@RequestMapping("/api/v1/settings/appearance")
@Tag(name = "外観テーマ設定", description = "F11.4 個人外観テーマ・背景色設定（複数端末同期）")
@RequiredArgsConstructor
public class AppearanceSettingsController {

    private final AppearanceSettingsService appearanceSettingsService;

    /**
     * 外観設定取得。未登録の場合はデフォルト値（LIGHT / #f3efe0 / null / false）を返す。
     */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "外観設定取得", description = "現在ユーザーの外観テーマ設定を返す。未登録時はデフォルト値を返す。")
    public ResponseEntity<ApiResponse<AppearanceResponse>> getAppearance() {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(appearanceSettingsService.getOrDefault(userId)));
    }

    /**
     * 外観設定保存（upsert）。保存後の値を返す。
     */
    @PutMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "外観設定更新", description = "現在ユーザーの外観テーマ設定を全量上書き保存する（1ユーザー1行upsert）。")
    public ResponseEntity<ApiResponse<AppearanceResponse>> updateAppearance(
            @Valid @RequestBody UpdateAppearanceRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(appearanceSettingsService.save(userId, request)));
    }
}
