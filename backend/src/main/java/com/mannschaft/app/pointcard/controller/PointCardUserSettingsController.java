package com.mannschaft.app.pointcard.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.security.SelfScopedEndpoint;
import com.mannschaft.app.pointcard.dto.PointCardUserSettingsResponse;
import com.mannschaft.app.pointcard.dto.UpdateUserSettingsRequest;
import com.mannschaft.app.pointcard.service.PointCardUserSettingsService;
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
 * ポイントカードウォレットのユーザー設定 API。
 *
 * <p>設計書: {@code docs/features/F18_point_card_wallet.md} §6.1
 *
 * <p>オプトイン状態・規約同意・WebAuthn 要求設定の取得・更新を担当する。
 * 認証必須・PUT は {@code PointCardRateLimitFilter} で 10/h 制限。
 */
@RestController
@RequestMapping("/api/v1/point-cards/settings")
@Tag(name = "ポイントカード ユーザー設定", description = "F18 オプトイン・規約同意・生体認証要求設定")
@RequiredArgsConstructor
public class PointCardUserSettingsController {

    private final PointCardUserSettingsService settingsService;

    @SelfScopedEndpoint("取得対象は SecurityUtils.getCurrentUserId() 固定で、"
            + "リクエストに他ユーザーの識別子を指定する項目が無い（getSettings メソッド本体）")
    @GetMapping
    @Operation(summary = "ウォレット設定取得",
            description = "自分の設定を返す。レコードが無ければデフォルト（オプトアウト状態）で作成して返却")
    public ResponseEntity<ApiResponse<PointCardUserSettingsResponse>> getSettings() {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(settingsService.getOrCreateSettings(userId)));
    }

    @SelfScopedEndpoint("更新対象は SecurityUtils.getCurrentUserId() 固定で、"
            + "リクエストに他ユーザーの識別子を指定する項目が無い（updateSettings メソッド本体）")
    @PutMapping
    @Operation(summary = "ウォレット設定更新",
            description = "オプトイン・規約同意・WebAuthn 要求設定を差分適用で更新")
    public ResponseEntity<ApiResponse<PointCardUserSettingsResponse>> updateSettings(
            @Valid @RequestBody UpdateUserSettingsRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(settingsService.updateSettings(userId, request)));
    }
}
