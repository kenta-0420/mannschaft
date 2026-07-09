package com.mannschaft.app.advertising.operational;

import com.mannschaft.app.advertising.controller.AdvertiserAdminController;
import com.mannschaft.app.advertising.service.AdCreditLimitRequestService;
import com.mannschaft.app.advertising.service.AdInvoiceService;
import com.mannschaft.app.advertising.service.AdRateCardService;
import com.mannschaft.app.advertising.service.AdvertiserAccountService;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.common.security.AccessGuard;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F09.19.5 AC-5.5 {@link AdvertiserAdminController} のクラスレベル {@code @PreAuthorize} 二重ガード検証（試練 / red 先行）。
 *
 * <p>正本: {@code docs/features/F09.19_ad_slot_serving.md} §16 F09.19.5
 * （「AdvertiserAdminController の全メソッドが SYSTEM_ADMIN 以外に 403。
 * クラス @PreAuthorize 単体でも守られることを WebMvcTest で確認」）。</p>
 *
 * <p>本アプリは既定では {@code @EnableMethodSecurity} 未有効で、SYSTEM_ADMIN 制限は SecurityConfig の
 * URL ルール（{@code /api/v1/system-admin/**}）に依存する。本テストは<b>メソッドセキュリティを明示的に有効化</b>
 * （{@link MethodSecurityConfig} を {@code @Import}）し、URL ルールを外した（{@code addFilters=false}）状態でも
 * <b>クラスレベル {@code @PreAuthorize} 単体で全メソッドが SYSTEM_ADMIN 以外を 403 で弾く</b>ことを検証する
 * （二重ガード = 防御の多層化）。{@code @PreAuthorize} 拒否は {@code AuthorizationDeniedException}
 * （{@code AccessDeniedException} のサブクラス）→ {@code GlobalExceptionHandler} で 403 に変換される。</p>
 *
 * <p><b>red 分類（実装不在）</b>: 現状 {@code AdvertiserAdminController} にはクラス {@code @PreAuthorize} が無い。
 * メソッドセキュリティを有効化しても弾く注釈が無いため、非 SYSTEM_ADMIN でもメソッドが実行され 403 にならない
 * （未スタブの mock により 200/500 になる）。出陣で
 * {@code @PreAuthorize("hasRole('SYSTEM_ADMIN')")} をクラスに付与すると green。
 * 正常系（SYSTEM_ADMIN → 到達）は red/green 両状態で成立する companion 断言。</p>
 *
 * <p>金型: {@code SystemAdminGdprPurgeControllerTest}（@WebMvcTest + addFilters=false + 共通 web mock 群）。</p>
 */
@DisplayName("F09.19.5 AdvertiserAdminController クラス @PreAuthorize 二重ガード（試練）")
@WebMvcTest(AdvertiserAdminController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(MethodSecurityTestConfig.class)
class AdvertiserAdminControllerPreAuthorizeTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AdRateCardService adRateCardService;
    @MockitoBean
    private AdvertiserAccountService advertiserAccountService;
    @MockitoBean
    private AdInvoiceService adInvoiceService;
    @MockitoBean
    private AdCreditLimitRequestService adCreditLimitRequestService;

    // @WebMvcTest コンテキスト用: フィルタ・SpEL ガードの依存解決 mock
    @MockitoBean
    private AuthTokenService authTokenService;
    @MockitoBean
    private UserLocaleCache userLocaleCache;
    @MockitoBean
    private ProxyInputConsentRepository proxyInputConsentRepository;
    @MockitoBean
    private ProxyInputContext proxyInputContext;
    @MockitoBean
    private AccessGuard accessGuard;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    /** SYSTEM_ADMIN 以外（一般組織 ADMIN 相当）で認証済みにする。 */
    private void authenticateNonSystemAdmin() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        "100", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    /** SYSTEM_ADMIN で認証済みにする。 */
    private void authenticateSystemAdmin() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        "1", null, List.of(new SimpleGrantedAuthority("ROLE_SYSTEM_ADMIN"))));
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-5.5: 全メソッドが SYSTEM_ADMIN 以外に 403
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("ac5_5: 全 11 メソッドが非 SYSTEM_ADMIN に 403 を返す（クラス @PreAuthorize 単体）")
    void ac5_5_全メソッドが非SYSTEM_ADMINに403() throws Exception {
        RequestBuilder[] requests = new RequestBuilder[]{
                get("/api/v1/system-admin/ad-rate-cards"),
                post("/api/v1/system-admin/ad-rate-cards")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pricingModel\":\"CPM\",\"unitPrice\":500,"
                                + "\"minDailyBudget\":1000,\"effectiveFrom\":\"2026-08-01\"}"),
                delete("/api/v1/system-admin/ad-rate-cards/1"),
                get("/api/v1/system-admin/advertiser-accounts"),
                patch("/api/v1/system-admin/advertiser-accounts/1/approve"),
                patch("/api/v1/system-admin/advertiser-accounts/1/suspend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"規約違反\"}"),
                patch("/api/v1/system-admin/advertiser-accounts/1/credit-limit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"creditLimit\":200000}"),
                patch("/api/v1/system-admin/ad-invoices/1/mark-paid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paidAt\":\"2026-08-01T00:00:00\",\"note\":\"入金確認\"}"),
                get("/api/v1/system-admin/ad-credit-limit-requests"),
                patch("/api/v1/system-admin/ad-credit-limit-requests/1/approve"),
                patch("/api/v1/system-admin/ad-credit-limit-requests/1/reject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reviewNote\":\"却下\"}"),
        };

        for (RequestBuilder request : requests) {
            authenticateNonSystemAdmin();
            mockMvc.perform(request).andExpect(status().isForbidden());
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    @DisplayName("ac5_5: SYSTEM_ADMIN は 403 にならずメソッドへ到達する（companion）")
    void ac5_5_SYSTEM_ADMINは到達する() throws Exception {
        given(advertiserAccountService.findAll(any(), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of()));

        authenticateSystemAdmin();
        mockMvc.perform(get("/api/v1/system-admin/advertiser-accounts"))
                .andExpect(status().isOk());
    }
}
