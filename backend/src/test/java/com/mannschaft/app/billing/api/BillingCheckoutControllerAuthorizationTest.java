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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PR4: quote / Checkout API の <b>実 Spring Security（メソッドセキュリティ）経由</b>の認可検証。
 *
 * <p>{@code addFilters = false} で URL ルール層を外し、{@code @PreAuthorize} 単体で守られることを確認する
 * （金型: {@link BillingApiRuntimeAuthorizationTest}）。本 API は scope をパスではなく本文・quote から
 * 受け取るため、認可は「入口の認証必須」と「サービス層の scope guard」の二段になっている。
 * 拒否側は<b>両方</b>を通す（未認証で入口が閉じること／認証済みでも scope 権限が無ければ 403 になること）。</p>
 */
@DisplayName("PR4 quote/Checkout API 実 Security 認可検証")
@WebMvcTest(BillingCheckoutController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(MethodSecurityTestConfig.class)
class BillingCheckoutControllerAuthorizationTest {

    private static final UUID QUOTE_ID = UUID.fromString("01999d74-5130-7000-8000-000000000020");
    private static final String QUOTE_BODY =
            "{\"scopeKind\":\"TEAM\",\"scopeId\":91,\"productKind\":\"PLAN\",\"productKey\":\"PRO\"}";
    private static final String CHECKOUT_BODY = "{\"quoteId\":\"" + QUOTE_ID + "\"}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BillingQuoteService quoteService;
    @MockitoBean
    private BillingCheckoutApplicationService checkoutApplicationService;
    /**
     * BC-23 根治で入口が耐久冪等性を通るようになったため、slice へ mock を供給する。
     * 本クラスが測るのは<b>認可</b>であり、冪等性は素通り（ACQUIRED）に固定して影響を与えない
     * （冪等性そのものの検証は {@link BillingCheckoutControllerIdempotencyTest}）。
     */
    @MockitoBean
    private BillingDurableIdempotencyService idempotencyService;

    // ---- @WebMvcTest コンテキスト用: フィルタ・SpEL ガードの依存解決 mock ----
    @MockitoBean
    private AuthTokenService authTokenService;
    @MockitoBean
    private UserLocaleCache userLocaleCache;
    @MockitoBean
    private ProxyInputConsentRepository proxyInputConsentRepository;
    @MockitoBean
    private ProxyInputContext proxyInputContext;

    @BeforeEach
    void acquireIdempotencyLease() {
        given(idempotencyService.begin(anyLong(), anyString(), anyString(), anyString(),
                anyString(), anyString()))
                .willReturn(new BillingIdempotencyDecision(
                        BillingIdempotencyDecisionKind.ACQUIRED,
                        UUID.fromString("01999d74-5130-7000-8000-000000000021"), null, null, 0L));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    // ═════════════════════════════════════════════════════════════
    // 拒否側: 未認証は入口の @PreAuthorize で閉じる（サービスへ到達しない）
    // ═════════════════════════════════════════════════════════════

    @Test
    @DisplayName("見積り発行: 未認証は 403 でサービスへ到達しない")
    void createQuote_未認証_403() throws Exception {
        anonymous();

        mockMvc.perform(post("/api/v1/me/billing/quotes")
                        .header("Idempotency-Key", "quote-anon")
                        .contentType(MediaType.APPLICATION_JSON).content(QUOTE_BODY))
                .andExpect(status().isForbidden());

        verify(quoteService, never()).create(anyLong(), any(), any());
    }

    @Test
    @DisplayName("Checkout 作成: 未認証は 403 でサービスへ到達しない")
    void createCheckoutSession_未認証_403() throws Exception {
        anonymous();

        mockMvc.perform(post("/api/v1/me/billing/checkout-sessions")
                        .header("Idempotency-Key", "checkout-anon")
                        .contentType(MediaType.APPLICATION_JSON).content(CHECKOUT_BODY))
                .andExpect(status().isForbidden());

        verify(checkoutApplicationService, never()).create(anyLong(), any(), any());
    }

    // ═════════════════════════════════════════════════════════════
    // 拒否側: 認証済みでも scope 権限が無ければ 403（サービス層 guard）
    // ═════════════════════════════════════════════════════════════

    @Test
    @DisplayName("見積り発行: scope 管理権限が無ければ 403")
    void createQuote_scope権限なし_403() throws Exception {
        authenticate("100", "ROLE_USER");
        willThrow(new BusinessException(EntitlementErrorCode.SCOPE_FORBIDDEN))
                .given(quoteService).create(eq(100L), any(), eq("quote-forbidden"));

        mockMvc.perform(post("/api/v1/me/billing/quotes")
                        .header("Idempotency-Key", "quote-forbidden")
                        .contentType(MediaType.APPLICATION_JSON).content(QUOTE_BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Checkout 作成: quote の scope 管理権限が無ければ 403")
    void createCheckoutSession_scope権限なし_403() throws Exception {
        authenticate("100", "ROLE_USER");
        willThrow(new BusinessException(EntitlementErrorCode.SCOPE_FORBIDDEN))
                .given(checkoutApplicationService).create(eq(100L), any(), eq("checkout-forbidden"));

        mockMvc.perform(post("/api/v1/me/billing/checkout-sessions")
                        .header("Idempotency-Key", "checkout-forbidden")
                        .contentType(MediaType.APPLICATION_JSON).content(CHECKOUT_BODY))
                .andExpect(status().isForbidden());
    }

    // ═════════════════════════════════════════════════════════════
    // 許可側: 認証済みかつ scope 権限ありでメソッドへ到達し 201
    // ═════════════════════════════════════════════════════════════

    @Test
    @DisplayName("見積り発行: 認証済みはメソッドに到達し 201・actor は認証主体から解決される")
    void createQuote_認証済み_201() throws Exception {
        authenticate("100", "ROLE_USER");
        given(quoteService.create(eq(100L), any(), eq("quote-ok"))).willReturn(quoteResponse());

        mockMvc.perform(post("/api/v1/me/billing/quotes")
                        .header("Idempotency-Key", "quote-ok")
                        .contentType(MediaType.APPLICATION_JSON).content(QUOTE_BODY))
                .andExpect(status().isCreated());

        verify(quoteService).create(eq(100L), any(), eq("quote-ok"));
    }

    @Test
    @DisplayName("Checkout 作成: 認証済みはメソッドに到達し 201・actor は認証主体から解決される")
    void createCheckoutSession_認証済み_201() throws Exception {
        authenticate("100", "ROLE_USER");
        given(checkoutApplicationService.create(eq(100L), any(), eq("checkout-ok")))
                .willReturn(new BillingCheckoutApplicationService.CheckoutSessionResponse(
                        "https://checkout.stripe.test/c/cs_test_1",
                        Instant.parse("2028-02-11T02:59:00Z")));

        mockMvc.perform(post("/api/v1/me/billing/checkout-sessions")
                        .header("Idempotency-Key", "checkout-ok")
                        .contentType(MediaType.APPLICATION_JSON).content(CHECKOUT_BODY))
                .andExpect(status().isCreated());

        verify(checkoutApplicationService).create(eq(100L), any(), eq("checkout-ok"));
    }

    // ═════════════════════════════════════════════════════════════
    // 冪等キー必須（M-1: 二重押下で二重課金させない）
    // ═════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Checkout 作成: Idempotency-Key 欠落は 400 でサービスへ到達しない")
    void createCheckoutSession_冪等キー欠落_400() throws Exception {
        authenticate("100", "ROLE_USER");

        mockMvc.perform(post("/api/v1/me/billing/checkout-sessions")
                        .contentType(MediaType.APPLICATION_JSON).content(CHECKOUT_BODY))
                .andExpect(status().isBadRequest());

        verify(checkoutApplicationService, never()).create(anyLong(), any(), any());
    }

    private BillingQuoteResponse quoteResponse() {
        BillingQuoteResponse.Money money =
                new BillingQuoteResponse.Money("JPY", 1000, 909, 91, "消費税", 1000);
        return new BillingQuoteResponse(QUOTE_ID, BillingProductKind.PLAN, "PRO", money, money,
                Instant.parse("2028-02-10T03:10:00Z"), Instant.parse("2028-01-31T15:00:00Z"),
                Instant.parse("2028-02-29T15:00:00Z"));
    }

    /**
     * 未認証（匿名）状態を作る。{@code addFilters = false} では
     * {@code AnonymousAuthenticationFilter} が走らず SecurityContext が空のままになり、
     * {@code isAuthenticated()} の評価が {@code AuthenticationCredentialsNotFoundException}
     * （＝認可拒否ではなく「認証情報が無い」内部エラー）になってしまう。実運用のフィルタ鎖が
     * 必ず置く匿名トークンを自前で置くことで、<b>メソッド注釈単体が拒否すること</b>を測る。
     */
    private void anonymous() {
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "anonymous-key", "anonymousUser",
                List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
    }

    private void authenticate(String userId, String... roles) {
        List<SimpleGrantedAuthority> auths = Arrays.stream(roles)
                .map(SimpleGrantedAuthority::new).toList();
        Authentication auth = new UsernamePasswordAuthenticationToken(userId, null, auths);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}
