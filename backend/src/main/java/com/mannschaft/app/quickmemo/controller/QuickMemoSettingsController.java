package com.mannschaft.app.quickmemo.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.security.AuthorizedInService;
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
 *
 * <p><b>認可根拠（{@link AuthorizedInService}）</b>: 両 EP とも自己スコープ。
 * {@code UserQuickMemoSettingsService} は
 * {@code settingsRepository.findByUserId}（getSettings:
 * UserQuickMemoSettingsService.java:49 / updateSettings:
 * UserQuickMemoSettingsService.java:71）で
 * {@link SecurityUtils#getCurrentUserId()} の設定行のみを参照・UPSERT する。
 * {@code apply_to} による既存メモの再計算も
 * {@code applyToExistingMemos}（UserQuickMemoSettingsService.java:104）が
 * userId 絞り込みで対象を取得するため（UserQuickMemoSettingsService.java:108-110）、
 * 他ユーザーのメモのリマインドを書き換えることはできない。
 * 認可番人の白名簿クラス経由ではないため監査済マーカーで明示承認する。
 * 回帰は {@code QuickMemoSelfScopeContractIT} で固定する。</p>
 */
@RestController
@RequestMapping("/api/v1/quick-memos/settings")
@Tag(name = "ポイっとメモ設定", description = "F02.5 リマインド設定管理")
@RequiredArgsConstructor
public class QuickMemoSettingsController {

    private final UserQuickMemoSettingsService settingsService;

    @GetMapping
    @Operation(summary = "リマインド設定取得")
    @AuthorizedInService
    public ResponseEntity<ApiResponse<UserQuickMemoSettingsResponse>> getSettings() {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(settingsService.getSettings(userId)));
    }

    @PutMapping
    @Operation(summary = "リマインド設定更新",
               description = "apply_to: NEW_ONLY（新規メモのみ）/ UNSENT（未送信枠を再計算）/ ALL（全未整理メモを再計算）")
    @AuthorizedInService
    public ResponseEntity<ApiResponse<UserQuickMemoSettingsResponse>> updateSettings(
            @Valid @RequestBody UpdateSettingsRequest request,
            @RequestParam(defaultValue = "NEW_ONLY") String apply_to) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(settingsService.updateSettings(userId, request, apply_to)));
    }
}
