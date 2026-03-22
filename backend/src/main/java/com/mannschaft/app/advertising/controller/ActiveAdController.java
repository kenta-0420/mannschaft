package com.mannschaft.app.advertising.controller;

import com.mannschaft.app.advertising.dto.ActiveAdResponse;
import com.mannschaft.app.advertising.service.AffiliateConfigService;
import com.mannschaft.app.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
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
}
