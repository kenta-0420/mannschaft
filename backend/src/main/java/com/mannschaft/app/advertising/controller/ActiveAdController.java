package com.mannschaft.app.advertising.controller;

import com.mannschaft.app.advertising.dto.ActiveAdResponse;
import com.mannschaft.app.advertising.service.AffiliateConfigService;
import com.mannschaft.app.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 有効な広告一覧を返す公開コントローラー（認証不要）。
 */
@RestController
@RequestMapping("/api/v1/ads")
@RequiredArgsConstructor
public class ActiveAdController {

    private final AffiliateConfigService affiliateConfigService;

    /**
     * 現在有効な広告一覧を取得する。
     */
    @GetMapping("/active")
    public ApiResponse<List<ActiveAdResponse>> activeAds() {
        return ApiResponse.of(affiliateConfigService.findActiveAds());
    }

    /**
     * ユーザー属性に基づいてターゲティングされた広告一覧を取得する。
     * パラメータ未指定時は全対象の広告のみ返す。
     */
    @GetMapping("/targeted")
    public ApiResponse<List<ActiveAdResponse>> targetedAds(
            @RequestParam(required = false) String template,
            @RequestParam(required = false) String prefecture,
            @RequestParam(required = false) String locale) {
        return ApiResponse.of(affiliateConfigService.findTargetedAds(template, prefecture, locale));
    }
}
