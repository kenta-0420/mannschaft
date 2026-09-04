package com.mannschaft.app.billing.api;

import com.mannschaft.app.advertising.operational.MethodSecurityTestConfig;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.billing.BillingProductKind;
import com.mannschaft.app.billing.EntitlementErrorCode;
import com.mannschaft.app.billing.api.dto.BillingQuoteResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * BC-23: quote / Checkout の HTTP 入口が <b>実際に</b> 耐久冪等性を通ることの検証。
 *
 * <p><b>なぜ必要か</b>: {@code BillingIdempotencyServiceTrialTest} は service 単体を固定しているだけで、
 * PR4 実装では controller から一度も呼ばれていなかった（同じキーの再送で quote が毎回新規発行され、
 * Checkout は保存済み応答の replay も request hash 相違の検出も経なかった）。
 * 緑のテストが死んだコードを守っている状態だったため、<b>controller を通した経路</b>で
 * begin の 3 分岐が HTTP 応答へ写ることを固定する。</p>
 */
@DisplayName("PR4 quote/Checkout API 耐久冪等性の結線検証")
@WebMvcTest(BillingCheckoutController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(MethodSecurityTestConfig.class)
class BillingCheckoutControllerIdempotencyTest {

    private static final UUID QUOTE_ID = UUID.fromString("01999d74-5130-7000-8000-000000000050");
    private static final UUID RECORD_ID = UUID.fromString("01999d74-5130-7000-8000-000000000051");
    private static final String QUOTE_BODY =
            "{\"scopeKind\":\"TEAM\",\"scopeId\":91,\"productKind\":\"PLAN\",\"productKey\":\"PRO\"}";
    private static final String OTHER_QUOTE_BODY =
            "{\"scopeKind\":\"TEAM\",\"scopeId\":92,\"productKind\":\"PLAN\",\"productKey\":\"PRO\"}";
    private static final String CHECKOUT_BODY = "{\"quoteId\":\"" + QUOTE_ID + "\"}";
    private static final String KEY = "00000000-0000-0000-0000-000000000501";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BillingQuoteService quoteService;
    @MockitoBean
    private BillingCheckoutApplicationService checkoutApplicationService;
    @MockitoBean
    private BillingDurableIdempotencyService idempotencyService;

    @MockitoBean
    private AuthTokenService authTokenService;
    @MockitoBean
    private UserLocaleCache userLocaleCache;
    @MockitoBean
    private ProxyInputConsentRepository proxyInputConsentRepository;
    @MockitoBean
    private ProxyInputContext proxyInputContext;

    @BeforeEach
    void authenticate() {
        Authentication auth = new UsernamePasswordAuthenticationToken(
                "100", null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    // ═════════ REPLAY ═════════

    @Test
    @DisplayName("Checkout: 同一キー同一 hash の再送は保存済み応答を replay し本処理を再実行しない")
    void checkout_replay_保存済み応答を返しサービスを呼ばない() throws Exception {
        given(idempotencyService.begin(eq(100L), eq("POST"),
                eq("/api/v1/me/billing/checkout-sessions"), eq(KEY), anyString(), anyString()))
                .willReturn(new BillingIdempotencyDecision(
                        BillingIdempotencyDecisionKind.REPLAY, RECORD_ID, 201,
                        "{\"data\":{\"checkoutUrl\":\"https://checkout.stripe.test/c/cs_test_1\","
                                + "\"expiresAt\":\"2028-02-11T02:59:00Z\"}}", 0L));

        mockMvc.perform(post("/api/v1/me/billing/checkout-sessions")
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(CHECKOUT_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.checkoutUrl")
                        .value("https://checkout.stripe.test/c/cs_test_1"));

        verify(checkoutApplicationService, never()).create(anyLong(), any(), any());
    }

    @Test
    @DisplayName("quote: 同一キー同一 hash の再送は保存済み quote を replay し新規発行しない")
    void quote_replay_保存済み応答を返しサービスを呼ばない() throws Exception {
        given(idempotencyService.begin(eq(100L), eq("POST"), eq("/api/v1/me/billing/quotes"),
                eq(KEY), anyString(), anyString()))
                .willReturn(new BillingIdempotencyDecision(
                        BillingIdempotencyDecisionKind.REPLAY, RECORD_ID, 201,
                        "{\"data\":{\"quoteId\":\"" + QUOTE_ID + "\",\"productKind\":\"PLAN\","
                                + "\"productKey\":\"PRO\"}}", 0L));

        mockMvc.perform(post("/api/v1/me/billing/quotes")
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(QUOTE_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.quoteId").value(QUOTE_ID.toString()));

        verify(quoteService, never()).create(anyLong(), any(), any());
    }

    // ═════════ hash 相違 / PROCESSING ═════════

    @Test
    @DisplayName("Checkout: 同一キーで request hash が違えば 409 で本処理へ到達しない")
    void checkout_hash相違_409() throws Exception {
        willThrow(new BusinessException(EntitlementErrorCode.CHANGE_CONFLICT))
                .given(idempotencyService).begin(eq(100L), eq("POST"),
                        eq("/api/v1/me/billing/checkout-sessions"), eq(KEY), anyString(), anyString());

        mockMvc.perform(post("/api/v1/me/billing/checkout-sessions")
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(CHECKOUT_BODY))
                .andExpect(status().isConflict());

        verify(checkoutApplicationService, never()).create(anyLong(), any(), any());
    }

    @Test
    @DisplayName("Checkout: 先行要求が処理中なら 409 と Retry-After を返し本処理へ到達しない")
    void checkout_processing_RetryAfterつき409() throws Exception {
        given(idempotencyService.begin(eq(100L), eq("POST"),
                eq("/api/v1/me/billing/checkout-sessions"), eq(KEY), anyString(), anyString()))
                .willReturn(new BillingIdempotencyDecision(
                        BillingIdempotencyDecisionKind.PROCESSING, RECORD_ID, null, null, 37L));

        mockMvc.perform(post("/api/v1/me/billing/checkout-sessions")
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(CHECKOUT_BODY))
                .andExpect(status().isConflict())
                .andExpect(header().string("Retry-After", "37"));

        verify(checkoutApplicationService, never()).create(anyLong(), any(), any());
    }

    // ═════════ ACQUIRED ═════════

    @Test
    @DisplayName("Checkout: 初回はサービスを実行し 201 応答の status と body を耐久化する")
    void checkout_acquired_完了時にstatusとbodyを記録する() throws Exception {
        given(idempotencyService.begin(eq(100L), eq("POST"),
                eq("/api/v1/me/billing/checkout-sessions"), eq(KEY), anyString(), anyString()))
                .willReturn(new BillingIdempotencyDecision(
                        BillingIdempotencyDecisionKind.ACQUIRED, RECORD_ID, null, null, 0L));
        given(checkoutApplicationService.create(eq(100L), any(), eq(KEY)))
                .willReturn(new BillingCheckoutApplicationService.CheckoutSessionResponse(
                        "https://checkout.stripe.test/c/cs_test_9",
                        Instant.parse("2028-02-11T02:59:00Z")));

        mockMvc.perform(post("/api/v1/me/billing/checkout-sessions")
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(CHECKOUT_BODY))
                .andExpect(status().isCreated());

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(idempotencyService).complete(eq(RECORD_ID), anyString(), eq(201), json.capture());
        assertThat(json.getValue()).contains("cs_test_9").startsWith("{\"data\":");
    }

    @Test
    @DisplayName("Checkout: 本処理が倒れたら FAILED へ確定させてから元の例外をそのまま返す")
    void checkout_失敗時はFAILED確定して症状を隠さない() throws Exception {
        given(idempotencyService.begin(eq(100L), eq("POST"),
                eq("/api/v1/me/billing/checkout-sessions"), eq(KEY), anyString(), anyString()))
                .willReturn(new BillingIdempotencyDecision(
                        BillingIdempotencyDecisionKind.ACQUIRED, RECORD_ID, null, null, 0L));
        willThrow(new BusinessException(EntitlementErrorCode.PRICE_NOT_SELLABLE))
                .given(checkoutApplicationService).create(eq(100L), any(), eq(KEY));

        mockMvc.perform(post("/api/v1/me/billing/checkout-sessions")
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(CHECKOUT_BODY))
                .andExpect(status().isConflict());

        verify(idempotencyService).fail(eq(RECORD_ID), anyString());
        verify(idempotencyService, never()).complete(any(), anyString(), org.mockito.ArgumentMatchers.anyInt(), any());
    }

    // ═════════ request hash の中身 ═════════

    @Test
    @DisplayName("request hash は本文の違いを検出する（同一キー・別本文で hash が変わる）")
    void requestHashは本文の違いを検出する() throws Exception {
        given(idempotencyService.begin(anyLong(), anyString(), anyString(), anyString(),
                anyString(), anyString()))
                .willReturn(new BillingIdempotencyDecision(
                        BillingIdempotencyDecisionKind.ACQUIRED, RECORD_ID, null, null, 0L));
        given(quoteService.create(anyLong(), any(), anyString())).willReturn(quoteResponse());

        mockMvc.perform(post("/api/v1/me/billing/quotes").header("Idempotency-Key", KEY)
                .contentType(MediaType.APPLICATION_JSON).content(QUOTE_BODY));
        mockMvc.perform(post("/api/v1/me/billing/quotes").header("Idempotency-Key", KEY)
                .contentType(MediaType.APPLICATION_JSON).content(OTHER_QUOTE_BODY));

        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        verify(idempotencyService, org.mockito.Mockito.times(2)).begin(eq(100L), eq("POST"),
                eq("/api/v1/me/billing/quotes"), eq(KEY), hash.capture(), anyString());
        List<String> hashes = hash.getAllValues();
        assertThat(hashes.get(0)).hasSize(64).isNotEqualTo(hashes.get(1));
    }

    private BillingQuoteResponse quoteResponse() {
        BillingQuoteResponse.Money money =
                new BillingQuoteResponse.Money("JPY", 1000, 909, 91, "消費税", 1000);
        return new BillingQuoteResponse(QUOTE_ID, BillingProductKind.PLAN, "PRO", money, money,
                Instant.parse("2028-02-10T03:10:00Z"), Instant.parse("2028-01-31T15:00:00Z"),
                Instant.parse("2028-02-29T15:00:00Z"));
    }
}
