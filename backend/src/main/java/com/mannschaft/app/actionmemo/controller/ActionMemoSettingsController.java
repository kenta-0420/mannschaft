package com.mannschaft.app.actionmemo.controller;

import com.mannschaft.app.actionmemo.dto.ActionMemoSettingsResponse;
import com.mannschaft.app.actionmemo.dto.UpdateActionMemoSettingsRequest;
import com.mannschaft.app.actionmemo.service.ActionMemoSettingsService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.security.AuthorizedInService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * F02.5 行動メモ設定コントローラー。
 *
 * <p>レコード未作成のユーザーに対しては Service 層がデフォルト値（mood_enabled = false）を返す。
 * 更新は UPSERT（1回目で INSERT、2回目以降は UPDATE）。</p>
 *
 * <p><b>認可根拠（{@link AuthorizedInService}）</b>: 両 EP とも自己スコープ。
 * {@code user_action_memo_settings} の主キーは userId であり、
 * {@code ActionMemoSettingsService}（getSettings: ActionMemoSettingsService.java:69 /
 * updateSettings: ActionMemoSettingsService.java:100）は
 * {@link SecurityUtils#getCurrentUserId()} を主キーとして参照・UPSERT する。
 * 対象ユーザーをリクエストで指定する余地がないため他ユーザーの設定には到達できない。
 * また {@code default_post_team_id} は所属チームであることを検証する
 * （ActionMemoSettingsService.java:116）。
 * 回帰は {@code ActionMemoScopeContractIT} の設定隔離テストで固定する。</p>
 */
@RestController
@RequestMapping("/api/v1/action-memo-settings")
@Tag(name = "行動メモ設定", description = "F02.5 行動メモ ユーザー個別設定")
@RequiredArgsConstructor
public class ActionMemoSettingsController {

    private final ActionMemoSettingsService settingsService;

    /**
     * 自分の設定を取得する。
     */
    @GetMapping
    @Operation(summary = "行動メモ設定取得")
    @AuthorizedInService
    public ResponseEntity<ApiResponse<ActionMemoSettingsResponse>> getSettings() {
        ActionMemoSettingsResponse response = settingsService.getSettings(SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 自分の設定を UPSERT する。
     */
    @PatchMapping
    @Operation(summary = "行動メモ設定更新")
    @AuthorizedInService
    public ResponseEntity<ApiResponse<ActionMemoSettingsResponse>> updateSettings(
            @Valid @RequestBody UpdateActionMemoSettingsRequest request) {
        ActionMemoSettingsResponse response = settingsService.updateSettings(
                SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
