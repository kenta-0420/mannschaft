package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.EntitlementScopeKind;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** BC-16/28: callback自身が認証前後を完結させるHTTP契約の試練。 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PR4 billing return callback MockMvc 試練")
class BillingReturnControllerTrialTest {
    private static final String TOKEN = "kid-current.opaque-payload.signature";

    @Mock private BillingReturnStateService returnStateService;
    @Mock private BillingCheckoutScopeGuard scopeGuard;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new BillingReturnController(returnStateService, scopeGuard))
                .build();
    }

    @Test
    @DisplayName("BC-16: 未認証callbackはnonce未消費でSecure HttpOnly Lax cookieを保存しloginへ303")
    void checkoutSuccess_未認証_nonce未消費でlogin303() throws Exception {
        mockMvc.perform(get("/billing/checkout/success").param("state", TOKEN))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location", containsString("/login")))
                .andExpect(cookie().httpOnly("billing_return_state", true))
                .andExpect(cookie().secure("billing_return_state", true))
                .andExpect(header().string("Set-Cookie", containsString("SameSite=Lax")))
                .andExpect(content().string(not(containsString(TOKEN))));

        verify(returnStateService, never()).consumeNonce(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    @DisplayName("BC-16: 再認証後はHMAC/purpose/expiry→actor→Guard→nonce CAS順でclean 303")
    void checkoutSuccess_認証済み_callback自身が順序検証しclean303() throws Exception {
        BillingReturnStateService.ReturnState state = state(BillingReturnStateService.Purpose.CHECKOUT_SUCCESS);
        given(returnStateService.verify(TOKEN, BillingReturnStateService.Purpose.CHECKOUT_SUCCESS))
                .willReturn(state);

        mockMvc.perform(get("/billing/checkout/success")
                        .param("state", TOKEN)
                        .principal(() -> "7"))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location", "/billing?scopeKind=TEAM&scopeId=91&tab=plan"))
                .andExpect(header().string("Location", not(containsString("state="))))
                .andExpect(content().string(not(containsString(TOKEN))));

        InOrder order = inOrder(returnStateService, scopeGuard);
        order.verify(returnStateService).verify(TOKEN, BillingReturnStateService.Purpose.CHECKOUT_SUCCESS);
        order.verify(scopeGuard).check(7L, EntitlementScopeKind.TEAM, 91L);
        order.verify(returnStateService).consumeNonce(state, 7L);
    }

    @Test
    @DisplayName("BC-16/28: Origin/Referer無しのStripe top-level GETも正規callbackとして許可する")
    void checkoutCancel_OriginReferer無し_正規にclean303() throws Exception {
        BillingReturnStateService.ReturnState state = state(BillingReturnStateService.Purpose.CHECKOUT_CANCEL);
        given(returnStateService.verify(TOKEN, BillingReturnStateService.Purpose.CHECKOUT_CANCEL))
                .willReturn(state);

        mockMvc.perform(get("/billing/checkout/cancel")
                        .param("state", TOKEN)
                        .principal(() -> "7"))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location", containsString("/billing?")));
    }

    @Test
    @DisplayName("BC-28: PAYMENT_ACTION_RETURNはHttpOnly cookieだけをserver-side consumeしURL/bodyへ出さない")
    void paymentActionReturn_cookieだけをconsumeしclean303() throws Exception {
        BillingReturnStateService.ReturnState state = state(
                BillingReturnStateService.Purpose.PAYMENT_ACTION_RETURN);
        given(returnStateService.verify(TOKEN, BillingReturnStateService.Purpose.PAYMENT_ACTION_RETURN))
                .willReturn(state);

        mockMvc.perform(get("/billing/payment-action/return")
                        .cookie(new Cookie("billing_return_state", TOKEN))
                        .principal(() -> "7"))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location", not(containsString(TOKEN))))
                .andExpect(content().string(not(containsString(TOKEN))))
                .andExpect(cookie().maxAge("billing_return_state", 0));
    }

    @Test
    @DisplayName("BC-16/28: 改竄・期限切れ・再利用はPII/tokenなしgeneric USER hubへ303")
    void checkoutSuccess_不正state_genericUserHubへ遷移する() throws Exception {
        given(returnStateService.verify(TOKEN, BillingReturnStateService.Purpose.CHECKOUT_SUCCESS))
                .willThrow(new IllegalArgumentException("generic billing return error"));

        mockMvc.perform(get("/billing/checkout/success")
                        .param("state", TOKEN)
                        .principal(() -> "7"))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location", "/billing?scopeKind=USER&tab=plan&error=return"))
                .andExpect(header().string("Location", not(containsString(TOKEN))))
                .andExpect(content().string(not(containsString("cs_test"))));
    }

    private BillingReturnStateService.ReturnState state(BillingReturnStateService.Purpose purpose) {
        return new BillingReturnStateService.ReturnState(purpose, EntitlementScopeKind.TEAM, 91L,
                7L, "plan", UUID.fromString("01999d74-5130-7000-8000-000000000040"),
                "cs_test_safe", UUID.fromString("01999d74-5130-7000-8000-000000000041"),
                Instant.parse("2028-02-10T03:00:00Z"), Instant.parse("2028-02-10T04:00:00Z"),
                "nonce-safe");
    }
}
