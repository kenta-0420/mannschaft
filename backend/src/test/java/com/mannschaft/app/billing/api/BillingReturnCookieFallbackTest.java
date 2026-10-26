package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.EntitlementScopeKind;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P1-2 の根治確認（BC-16 / BC-28）。
 *
 * <p>回帰の的: 退避 Cookie を読む実装が payment-action にしか無く、Checkout success / cancel /
 * Portal return は {@code @RequestParam String state} だけを見ていた。そのため再ログイン後に
 * callback が state を受け取る手段が無く、nonce が消費されないまま復帰導線が失われていた。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PR4 billing return callback の Cookie 復帰導線")
class BillingReturnCookieFallbackTest {
    private static final String COOKIE_TOKEN = "kid-current.cookie-payload.signature";
    private static final String PARAM_TOKEN = "kid-current.param-payload.signature";

    @Mock private BillingReturnStateService returnStateService;
    @Mock private BillingCheckoutAccessGuard scopeGuard;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                new BillingReturnController(returnStateService, scopeGuard)).build();
    }

    @Test
    @DisplayName("checkout/success は param 無しでも Cookie の state を消費し clean URL へ 303 する")
    void checkoutSuccess_cookieだけで消費できる() throws Exception {
        BillingReturnStateService.ReturnState state =
                state(BillingReturnStateService.Purpose.CHECKOUT_SUCCESS);
        given(returnStateService.verify(COOKIE_TOKEN, BillingReturnStateService.Purpose.CHECKOUT_SUCCESS))
                .willReturn(state);

        mockMvc.perform(get("/billing/checkout/success")
                        .cookie(new Cookie("billing_return_state", COOKIE_TOKEN))
                        .principal(() -> "7"))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location", "/billing?scopeKind=TEAM&scopeId=91&tab=plan"))
                .andExpect(header().string("Location", not(containsString(COOKIE_TOKEN))))
                // 消費後の Cookie は必ず失効させる。
                .andExpect(cookie().maxAge("billing_return_state", 0));

        verify(returnStateService).consumeNonce(state, 7L);
    }

    @Test
    @DisplayName("checkout/cancel も Cookie の state を消費できる")
    void checkoutCancel_cookieだけで消費できる() throws Exception {
        BillingReturnStateService.ReturnState state =
                state(BillingReturnStateService.Purpose.CHECKOUT_CANCEL);
        given(returnStateService.verify(COOKIE_TOKEN, BillingReturnStateService.Purpose.CHECKOUT_CANCEL))
                .willReturn(state);

        mockMvc.perform(get("/billing/checkout/cancel")
                        .cookie(new Cookie("billing_return_state", COOKIE_TOKEN))
                        .principal(() -> "7"))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location", "/billing?scopeKind=TEAM&scopeId=91&tab=plan"))
                .andExpect(cookie().maxAge("billing_return_state", 0));

        verify(returnStateService).consumeNonce(state, 7L);
    }

    @Test
    @DisplayName("portal/return も Cookie の state を消費できる")
    void portalReturn_cookieだけで消費できる() throws Exception {
        BillingReturnStateService.ReturnState state =
                state(BillingReturnStateService.Purpose.PORTAL_RETURN);
        given(returnStateService.verify(COOKIE_TOKEN, BillingReturnStateService.Purpose.PORTAL_RETURN))
                .willReturn(state);

        mockMvc.perform(get("/billing/portal/return")
                        .cookie(new Cookie("billing_return_state", COOKIE_TOKEN))
                        .principal(() -> "7"))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location", "/billing?scopeKind=TEAM&scopeId=91&tab=plan"))
                .andExpect(cookie().maxAge("billing_return_state", 0));

        verify(returnStateService).consumeNonce(state, 7L);
    }

    @Test
    @DisplayName("param と Cookie が両方来たら param を優先する（明文化した順位）")
    void checkoutSuccess_param優先() throws Exception {
        BillingReturnStateService.ReturnState state =
                state(BillingReturnStateService.Purpose.CHECKOUT_SUCCESS);
        given(returnStateService.verify(PARAM_TOKEN, BillingReturnStateService.Purpose.CHECKOUT_SUCCESS))
                .willReturn(state);

        mockMvc.perform(get("/billing/checkout/success")
                        .param("state", PARAM_TOKEN)
                        .cookie(new Cookie("billing_return_state", COOKIE_TOKEN))
                        .principal(() -> "7"))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location", "/billing?scopeKind=TEAM&scopeId=91&tab=plan"));

        verify(returnStateService).verify(PARAM_TOKEN, BillingReturnStateService.Purpose.CHECKOUT_SUCCESS);
    }

    @Test
    @DisplayName("未認証時の login next は callback 自身のパスを指す（/billing では再呼出しされない）")
    void checkoutSuccess_未認証_login_nextはcallback自身() throws Exception {
        mockMvc.perform(get("/billing/checkout/success").param("state", PARAM_TOKEN))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location", "/login?next=%2Fbilling%2Fcheckout%2Fsuccess"))
                .andExpect(cookie().httpOnly("billing_return_state", true));
    }

    @Test
    @DisplayName("state が param にも Cookie にも無ければ 400 ではなく generic hub へ 303 する")
    void portalReturn_state不在_genericHubへ303() throws Exception {
        mockMvc.perform(get("/billing/portal/return").principal(() -> "7"))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location", "/billing?scopeKind=USER&tab=plan&error=return"));
    }

    private BillingReturnStateService.ReturnState state(BillingReturnStateService.Purpose purpose) {
        return new BillingReturnStateService.ReturnState(purpose, EntitlementScopeKind.TEAM, 91L,
                7L, "plan", UUID.fromString("01999d74-5130-7000-8000-000000000060"),
                "cs_test_safe", UUID.fromString("01999d74-5130-7000-8000-000000000061"),
                Instant.parse("2028-02-10T03:00:00Z"), Instant.parse("2028-02-10T04:00:00Z"),
                "nonce-safe");
    }
}
