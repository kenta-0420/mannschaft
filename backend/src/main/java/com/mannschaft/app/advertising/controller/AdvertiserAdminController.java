package com.mannschaft.app.advertising.controller;

import com.mannschaft.app.advertising.AdvertiserAccountStatus;
import com.mannschaft.app.advertising.CreditLimitRequestStatus;
import com.mannschaft.app.advertising.PricingModel;
import com.mannschaft.app.advertising.dto.AdRateCardResponse;
import com.mannschaft.app.advertising.dto.AdvertiserAccountDetailResponse;
import com.mannschaft.app.advertising.dto.AdvertiserAccountResponse;
import com.mannschaft.app.advertising.dto.CreateAdRateCardRequest;
import com.mannschaft.app.advertising.dto.CreditLimitRequestDetailResponse;
import com.mannschaft.app.advertising.dto.CreditLimitRequestResponse;
import com.mannschaft.app.advertising.dto.InvoiceSummaryResponse;
import com.mannschaft.app.advertising.dto.MarkInvoicePaidRequest;
import com.mannschaft.app.advertising.dto.RejectCreditLimitRequest;
import com.mannschaft.app.advertising.dto.SuspendAdvertiserRequest;
import com.mannschaft.app.advertising.dto.UpdateCreditLimitRequest;
import com.mannschaft.app.advertising.service.AdCreditLimitRequestService;
import com.mannschaft.app.advertising.service.AdInvoiceService;
import com.mannschaft.app.advertising.service.AdRateCardService;
import com.mannschaft.app.advertising.service.AdvertiserAccountService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.common.SecurityUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 広告主管理コントローラー（SYSTEM_ADMIN用）。
 *
 * <p>広告料金カードの CRUD および広告主アカウントの審査・停止・与信枠管理を提供する。</p>
 */
@RestController
@RequestMapping("/api/v1/system-admin")
@RequiredArgsConstructor
public class AdvertiserAdminController {

    private final AdRateCardService adRateCardService;
    private final AdvertiserAccountService advertiserAccountService;
    private final AdInvoiceService adInvoiceService;
    private final AdCreditLimitRequestService adCreditLimitRequestService;

    // ─────────────────────────────────────────────
    // 広告料金カード
    // ─────────────────────────────────────────────

    /**
     * 広告料金カード一覧を取得する。
     */
    @GetMapping("/ad-rate-cards")
    public PagedResponse<AdRateCardResponse> listRateCards(
            @RequestParam(required = false) PricingModel pricingModel,
            @RequestParam(required = false) String prefecture,
            @RequestParam(required = false) Boolean activeOnly,
            Pageable pageable) {
        Page<AdRateCardResponse> page = adRateCardService.findAll(pricingModel, prefecture, activeOnly, pageable);
        return PagedResponse.of(
                page.getContent(),
                new PagedResponse.PageMeta(
                        page.getTotalElements(),
                        page.getNumber(),
                        page.getSize(),
                        page.getTotalPages()
                )
        );
    }

    /**
     * 広告料金カードを作成する。
     */
    @PostMapping("/ad-rate-cards")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AdRateCardResponse> createRateCard(
            @Valid @RequestBody CreateAdRateCardRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(adRateCardService.create(userId, request));
    }

    /**
     * 広告料金カードを削除する。
     */
    @DeleteMapping("/ad-rate-cards/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteRateCard(@PathVariable Long id) {
        adRateCardService.delete(id);
    }

    // ─────────────────────────────────────────────
    // 広告主アカウント
    // ─────────────────────────────────────────────

    /**
     * 広告主アカウント一覧を取得する。
     */
    @GetMapping("/advertiser-accounts")
    public PagedResponse<AdvertiserAccountDetailResponse> listAdvertiserAccounts(
            @RequestParam(required = false) AdvertiserAccountStatus status,
            Pageable pageable) {
        Page<AdvertiserAccountDetailResponse> page = advertiserAccountService.findAll(status, pageable);
        return PagedResponse.of(
                page.getContent(),
                new PagedResponse.PageMeta(
                        page.getTotalElements(),
                        page.getNumber(),
                        page.getSize(),
                        page.getTotalPages()
                )
        );
    }

    /**
     * 広告主アカウントを承認する。
     */
    @PatchMapping("/advertiser-accounts/{id}/approve")
    public ApiResponse<AdvertiserAccountResponse> approveAdvertiser(@PathVariable Long id) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(advertiserAccountService.approve(id, userId));
    }

    /**
     * 広告主アカウントを停止する。
     */
    @PatchMapping("/advertiser-accounts/{id}/suspend")
    public ApiResponse<AdvertiserAccountResponse> suspendAdvertiser(
            @PathVariable Long id,
            @Valid @RequestBody SuspendAdvertiserRequest request) {
        return ApiResponse.of(advertiserAccountService.suspend(id, request));
    }

    /**
     * 広告主アカウントの与信枠を更新する。
     */
    @PatchMapping("/advertiser-accounts/{id}/credit-limit")
    public ApiResponse<AdvertiserAccountResponse> updateCreditLimit(
            @PathVariable Long id,
            @Valid @RequestBody UpdateCreditLimitRequest request) {
        return ApiResponse.of(advertiserAccountService.updateCreditLimit(id, request));
    }

    // ─────────────────────────────────────────────
    // 請求書
    // ─────────────────────────────────────────────

    /**
     * 請求書の手動入金確認を行う（請求書払い用）。
     */
    @PatchMapping("/ad-invoices/{id}/mark-paid")
    public ApiResponse<InvoiceSummaryResponse> markInvoicePaid(
            @PathVariable Long id,
            @Valid @RequestBody MarkInvoicePaidRequest request) {
        return ApiResponse.of(adInvoiceService.markPaid(id, request));
    }

    // ─────────────────────────────────────────────
    // credit_limit 増額申請
    // ─────────────────────────────────────────────

    /**
     * 全広告主の増額申請一覧を取得する。
     */
    @GetMapping("/ad-credit-limit-requests")
    public PagedResponse<CreditLimitRequestDetailResponse> listCreditLimitRequests(
            @RequestParam(required = false) CreditLimitRequestStatus status,
            Pageable pageable) {
        Page<CreditLimitRequestDetailResponse> page = adCreditLimitRequestService.findAll(status, pageable);
        return PagedResponse.of(
                page.getContent(),
                new PagedResponse.PageMeta(
                        page.getTotalElements(),
                        page.getNumber(),
                        page.getSize(),
                        page.getTotalPages()
                )
        );
    }

    /**
     * 増額申請を承認する。
     */
    @PatchMapping("/ad-credit-limit-requests/{id}/approve")
    public ApiResponse<CreditLimitRequestResponse> approveCreditLimitRequest(@PathVariable Long id) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(adCreditLimitRequestService.approve(id, userId));
    }

    /**
     * 増額申請を却下する。
     */
    @PatchMapping("/ad-credit-limit-requests/{id}/reject")
    public ApiResponse<CreditLimitRequestResponse> rejectCreditLimitRequest(
            @PathVariable Long id,
            @Valid @RequestBody RejectCreditLimitRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(adCreditLimitRequestService.reject(id, userId, request));
    }
}
