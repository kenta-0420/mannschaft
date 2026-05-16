package com.mannschaft.app.advertising.campaign.controller;

import com.mannschaft.app.advertising.campaign.dto.UpdateUserAdPreferencesRequest;
import com.mannschaft.app.advertising.campaign.dto.UserAdPreferenceResponse;
import com.mannschaft.app.advertising.campaign.service.UserAdPreferenceService;
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
 * F09.17 Phase 11-a 受信者の広告受信設定 API コントローラ。
 *
 * <p>設計書「Preferences 域」§4 に対応。/api/v1/me 配下で認証ユーザー本人のみが操作可能。</p>
 *
 * <ul>
 *   <li>GET 取得（行が無ければデフォルトで作成して返す）</li>
 *   <li>PUT 更新（初回 PUT で {@code consented_at} を記録）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/me/ad-preferences")
@Tag(name = "広告受信設定", description = "F09.17 Phase 11-a 受信者の広告受信設定")
@RequiredArgsConstructor
public class UserAdPreferencesController {

    private final UserAdPreferenceService preferenceService;

    /**
     * 自分の広告受信設定を取得する。
     *
     * <p>初回アクセス時はデフォルト行（全許可・未同意・token_version=0）を自動作成して返す。</p>
     */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "自分の広告受信設定を取得")
    public ResponseEntity<ApiResponse<UserAdPreferenceResponse>> getMyAdPreferences() {
        Long userId = SecurityUtils.getCurrentUserId();
        UserAdPreferenceResponse response = preferenceService.getOrCreateForUser(userId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 自分の広告受信設定を更新する。
     *
     * <ul>
     *   <li>各 accept_*_ads / blocked_advertiser_account_ids は null なら現在値維持（部分更新）</li>
     *   <li>初回 PUT で consented_at を now() に設定。2 回目以降は維持</li>
     *   <li>rotateUnsubscribeTokens=true で unsubscribe_token_version を +1</li>
     *   <li>422 AD_PREFERENCES_BLOCKED_LIMIT: blocked 上限 100 件超過</li>
     * </ul>
     */
    @PutMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "自分の広告受信設定を更新")
    public ResponseEntity<ApiResponse<UserAdPreferenceResponse>> updateMyAdPreferences(
            @Valid @RequestBody UpdateUserAdPreferencesRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        UserAdPreferenceResponse response = preferenceService.updateForUser(userId, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
