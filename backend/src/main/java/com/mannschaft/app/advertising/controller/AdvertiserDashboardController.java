package com.mannschaft.app.advertising.controller;

import com.mannschaft.app.advertising.PricingModel;
import com.mannschaft.app.advertising.dto.AdvertiserAccountResponse;
import com.mannschaft.app.advertising.dto.AdvertiserOverviewResponse;
import com.mannschaft.app.advertising.dto.PublicRateCardResponse;
import com.mannschaft.app.advertising.dto.RateSimulatorResponse;
import com.mannschaft.app.advertising.dto.RegisterAdvertiserRequest;
import com.mannschaft.app.advertising.dto.UpdateAdvertiserAccountRequest;
import com.mannschaft.app.advertising.service.AdRateCardService;
import com.mannschaft.app.advertising.service.AdvertiserAccountService;
import com.mannschaft.app.advertising.service.RateSimulatorService;
import com.mannschaft.app.common.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 広告主ダッシュボードコントローラー。
 * <p>
 * 広告主向けのアカウント管理・料金シミュレーション・概要表示を提供する。
 */
@RestController
@RequestMapping("/api/v1/advertiser")
@RequiredArgsConstructor
public class AdvertiserDashboardController {

    private final AdvertiserAccountService advertiserAccountService;
    private final AdRateCardService adRateCardService;
    private final RateSimulatorService rateSimulatorService;

    /**
     * 広告主アカウントを新規登録する。
     */
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AdvertiserAccountResponse> register(
            @RequestParam Long organizationId,
            @Valid @RequestBody RegisterAdvertiserRequest request) {
        return ApiResponse.of(advertiserAccountService.register(organizationId, request));
    }

    /**
     * 自組織の広告主アカウント情報を取得する。
     */
    @GetMapping("/account")
    public ApiResponse<AdvertiserAccountResponse> getAccount(@RequestParam Long organizationId) {
        return ApiResponse.of(advertiserAccountService.getByOrganizationId(organizationId));
    }

    /**
     * 広告主アカウントのプロフィールを更新する。
     */
    @PatchMapping("/account")
    public ApiResponse<AdvertiserAccountResponse> updateAccount(
            @RequestParam Long organizationId,
            @Valid @RequestBody UpdateAdvertiserAccountRequest request) {
        return ApiResponse.of(advertiserAccountService.updateProfile(organizationId, request));
    }

    /**
     * 料金シミュレーションを実行する。
     * <p>
     * 認証必須だが広告主登録は不要。
     */
    @GetMapping("/rate-simulator")
    public ApiResponse<RateSimulatorResponse> rateSimulator(
            @RequestParam(required = false) String prefecture,
            @RequestParam(required = false) String template,
            @RequestParam PricingModel pricingModel,
            @RequestParam(required = false) Integer impressions,
            @RequestParam(required = false) Integer clicks,
            @RequestParam(required = false) Integer days) {
        return ApiResponse.of(rateSimulatorService.simulate(
                prefecture, template, pricingModel, impressions, clicks, days));
    }

    /**
     * 公開料金カード一覧を取得する。
     * <p>
     * 認証必須だが広告主登録は不要。
     */
    @GetMapping("/rate-cards")
    public ApiResponse<List<PublicRateCardResponse>> rateCards(
            @RequestParam(required = false) PricingModel pricingModel,
            @RequestParam(required = false) String prefecture) {
        return ApiResponse.of(adRateCardService.findCurrentRateCards(pricingModel, prefecture));
    }

    /**
     * 広告主ダッシュボード概要を取得する。
     */
    @GetMapping("/overview")
    public ApiResponse<AdvertiserOverviewResponse> overview(@RequestParam Long organizationId) {
        // TODO: Phase 2 で ad_daily_stats テーブルと連携し、実際の統計データを返す
        advertiserAccountService.getByOrganizationId(organizationId); // 存在チェック
        LocalDate now = LocalDate.now();
        return ApiResponse.of(new AdvertiserOverviewResponse(
                new AdvertiserOverviewResponse.Period(now.withDayOfMonth(1), now),
                0, 0, 0, 0,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                List.of()
        ));
    }
}
