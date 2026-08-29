package com.mannschaft.app.admin.controller;

import com.mannschaft.app.admin.dto.PublicFeatureFlagResponse;
import com.mannschaft.app.admin.service.FeatureFlagService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.featuregate.AlwaysReachable;
import com.mannschaft.app.common.featuregate.AlwaysReachableCategory;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 一般ユーザー向け公開フィーチャーフラグ読取APIコントローラー（Gate基盤工事①）。
 *
 * <p>認証済みユーザーであれば誰でも参照できる（{@code @PreAuthorize("isAuthenticated()")}、
 * {@link com.mannschaft.app.navsettings.controller.NavSettingsController} と同じパターン）。
 * 管理者専用情報（{@code description} / {@code updatedBy} / {@code id}）は含まない。</p>
 */
@RestController
@RequestMapping("/api/v1/feature-flags")
@Tag(name = "フィーチャーフラグ（公開）", description = "一般ユーザー向け公開フラグ読取API")
@RequiredArgsConstructor
public class FeatureFlagController {

    private final FeatureFlagService featureFlagService;

    /**
     * 公開フィーチャーフラグ一覧を取得する。
     */
    @AlwaysReachable(category = AlwaysReachableCategory.GATE_CONTROL_PLANE,
            reason = "クライアントがGate状態を取得して停止機能を安全に隠せるようにするため")
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "公開フィーチャーフラグ一覧取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<PublicFeatureFlagResponse>>> getPublicFlags() {
        List<PublicFeatureFlagResponse> flags = featureFlagService.getPublicFlags();
        return ResponseEntity.ok(ApiResponse.of(flags));
    }
}
