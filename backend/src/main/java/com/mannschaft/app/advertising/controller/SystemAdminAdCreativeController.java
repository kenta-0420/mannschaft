package com.mannschaft.app.advertising.controller;

import com.mannschaft.app.advertising.dto.AdCreativeResponse;
import com.mannschaft.app.advertising.entity.AdEntity;
import com.mannschaft.app.advertising.service.AdCreativeService;
import com.mannschaft.app.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 広告クリエイティブ審査・管理コントローラー（SYSTEM_ADMIN 用）。
 * <p>
 * 提供 API:
 * <ul>
 *   <li>{@code GET /api/v1/system-admin/ad-creatives} — 全クリエイティブ一覧（status フィルタ可）</li>
 *   <li>{@code PATCH /api/v1/system-admin/ad-creatives/{adId}/approve} — 審査承認 (DRAFT → ACTIVE)</li>
 *   <li>{@code PATCH /api/v1/system-admin/ad-creatives/{adId}/reject} — 審査却下 (DRAFT → ENDED)</li>
 * </ul>
 * クラスレベル {@code @PreAuthorize} で SYSTEM_ADMIN ロールに限定する。
 */
@RestController
@RequestMapping("/api/v1/system-admin/ad-creatives")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
public class SystemAdminAdCreativeController {

    private final AdCreativeService adCreativeService;

    /**
     * 全クリエイティブ一覧を取得する。status パラメータでフィルタリング可能。
     */
    @GetMapping
    public ApiResponse<List<AdCreativeResponse>> list(
            @RequestParam(required = false) AdEntity.AdStatus status) {
        return ApiResponse.of(adCreativeService.findAll(status));
    }

    /**
     * クリエイティブを審査承認する（DRAFT → ACTIVE）。
     */
    @PatchMapping("/{adId}/approve")
    public ApiResponse<AdCreativeResponse> approve(@PathVariable Long adId) {
        return ApiResponse.of(adCreativeService.approve(adId));
    }

    /**
     * クリエイティブを審査却下する（DRAFT → ENDED）。
     */
    @PatchMapping("/{adId}/reject")
    public ApiResponse<AdCreativeResponse> reject(@PathVariable Long adId) {
        return ApiResponse.of(adCreativeService.reject(adId));
    }
}
