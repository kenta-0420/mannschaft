package com.mannschaft.app.advertising.controller;

import com.mannschaft.app.advertising.dto.ActiveAdResponse;
import com.mannschaft.app.advertising.service.AffiliateConfigService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.security.AuthorizedByPathConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 有効な広告一覧を返すコントローラー。
 *
 * <p>クラス Javadoc の「認証不要」は誤り（{@code SecurityConfig} に本パス配下の
 * {@code permitAll} 定義は無く、{@code /active} / {@code /targeted} とも
 * {@code anyRequest().authenticated()} の対象）。応答はユーザー固有データを含まないため、
 * 認証必須のみで足りる（{@link AuthorizedByPathConfig} 参照）。</p>
 */
@RestController
@RequestMapping("/api/v1/ads")
@RequiredArgsConstructor
public class ActiveAdController {

    private final AffiliateConfigService affiliateConfigService;

    /**
     * 現在有効な広告一覧を取得する。
     *
     * <p><b>認可方式（{@link AuthorizedByPathConfig} メソッド付与）</b>:
     * {@code SecurityConfig の .anyRequest().authenticated()}。
     * 応答は全ユーザー共通（ユーザー固有データを含まない）。</p>
     *
     * <p>認可根治戦役 Wave6 監査済。</p>
     */
    @AuthorizedByPathConfig("anyRequest().authenticated()")
    @GetMapping("/active")
    public ApiResponse<List<ActiveAdResponse>> activeAds() {
        return ApiResponse.of(affiliateConfigService.findActiveAds());
    }

    /**
     * ユーザー属性に基づいてターゲティングされた広告一覧を取得する。
     * パラメータ未指定時は全対象の広告のみ返す。
     *
     * <p><b>認可方式（{@link AuthorizedByPathConfig} メソッド付与）</b>:
     * {@code SecurityConfig の .anyRequest().authenticated()}。
     * 応答は全ユーザー共通（ユーザー固有データを含まない）。</p>
     *
     * <p>認可根治戦役 Wave6 監査済。</p>
     */
    @AuthorizedByPathConfig("anyRequest().authenticated()")
    @GetMapping("/targeted")
    public ApiResponse<List<ActiveAdResponse>> targetedAds(
            @RequestParam(required = false) String template,
            @RequestParam(required = false) String prefecture,
            @RequestParam(required = false) String locale) {
        return ApiResponse.of(affiliateConfigService.findTargetedAds(template, prefecture, locale));
    }
}
