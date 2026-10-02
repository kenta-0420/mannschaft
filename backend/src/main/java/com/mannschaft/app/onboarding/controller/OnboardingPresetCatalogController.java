package com.mannschaft.app.onboarding.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.security.AuthorizedByPathConfig;
import com.mannschaft.app.onboarding.OnboardingPresetCategory;
import com.mannschaft.app.onboarding.dto.PresetCatalogResponse;
import com.mannschaft.app.onboarding.service.OnboardingPresetService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * ADMIN向けオンボーディングプリセットカタ���グコントローラー。
 * <p>
 * 組織・チーム管理者が利用可能なプリセット一覧を閲覧するためのエンドポ��ントを提��する。
 */
@RestController
@RequestMapping("/api/v1/onboarding/presets")
@RequiredArgsConstructor
public class OnboardingPresetCatalogController {

    private final OnboardingPresetService onboardingPresetService;

    /**
     * プリセットカタログ一覧を取得する。カテゴリでフィルタ可能。
     *
     * <p>全ユーザーに同一内容を返す参照系マスタ EP（組織固有データを含まない）。
     * {@code /api/v1/onboarding/presets} は permitAll 未登録のため SecurityConfig の
     * {@code anyRequest().authenticated()} で認証必須が強制される。</p>
     */
    @AuthorizedByPathConfig("anyRequest().authenticated()")
    @GetMapping
    public ApiResponse<List<PresetCatalogResponse>> listCatalog(
            @RequestParam(required = false) OnboardingPresetCategory category) {
        if (category != null) {
            return ApiResponse.of(onboardingPresetService.listCatalogByCategory(category));
        }
        return ApiResponse.of(onboardingPresetService.listCatalog());
    }
}
