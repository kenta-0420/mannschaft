package com.mannschaft.app.billing.api;

import com.mannschaft.app.advertising.operational.MethodSecurityTestConfig;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.billing.BillingPriceProvisionRecoveryService;
import com.mannschaft.app.billing.BillingPriceVersionStatus;
import com.mannschaft.app.billing.api.dto.PriceRevisionResponse;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /price-revisions/{id}/cancel} の実 Security 認可検証（addFilters=false・@PreAuthorize 実評価）。
 *
 * <p>新規エンドポイントは注釈の静的照合だけでなく、メソッドセキュリティが実際に効いて
 * 非 SYSTEM_ADMIN が Service に到達しないことを MockMvc で確かめる（既存の流儀）。</p>
 */
@DisplayName("price-revisions cancel 認可（実 Security 評価）")
@WebMvcTest(PriceRevisionController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(MethodSecurityTestConfig.class)
class PriceRevisionCancelAuthorizationTest {

    private static final String BASE_PATH = "/api/v1/system-admin/billing/price-revisions";
    private static final UUID REVISION_ID = UUID.fromString("01999d74-5130-7000-8000-000000000070");
    private static final UUID RECORD_ID = UUID.fromString("01999d74-5130-7000-8000-000000000071");
    private static final String KEY = "00000000-0000-0000-0000-000000000701";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PriceRevisionCreateService createService;
    @MockitoBean
    private PriceRevisionQueryService queryService;
    @MockitoBean
    private PriceRevisionProvisionService provisionService;
    @MockitoBean
    private PriceRevisionRetryProvisionService retryProvisionService;
    @MockitoBean
    private BillingPriceProvisionRecoveryService reconcileService;
    @MockitoBean
    private PriceRevisionActivationService activationService;
    @MockitoBean
    private PriceRevisionCancelService cancelService;
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

    private void authenticate(String userId, String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                userId, null, List.of(new SimpleGrantedAuthority(role))));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("非 SYSTEM_ADMIN（テナント ADMIN）は403で、取り消しサービスに到達しない")
    void nonSystemAdminIsForbidden() throws Exception {
        authenticate("100", "ROLE_ADMIN");

        mockMvc.perform(post(BASE_PATH + "/" + REVISION_ID + "/cancel")
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"lockVersion\":0}"))
                .andExpect(status().isForbidden());

        verify(cancelService, never()).cancel(any(), anyLong(), any());
    }

    @Test
    @DisplayName("SYSTEM_ADMIN は冪等基盤を通して取り消しサービスに到達し200")
    void systemAdminReachesCancel() throws Exception {
        authenticate("900", "ROLE_SYSTEM_ADMIN");
        String path = BASE_PATH + "/" + REVISION_ID + "/cancel";
        given(idempotencyService.begin(eq(900L), eq("POST"), eq(path), eq(KEY), anyString(), anyString()))
                .willReturn(new BillingIdempotencyDecision(
                        BillingIdempotencyDecisionKind.ACQUIRED, RECORD_ID, null, null, 0L));
        given(cancelService.cancel(eq(REVISION_ID), eq(0L), eq(900L))).willReturn(PriceRevisionResponse.builder()
                .id(REVISION_ID).status(BillingPriceVersionStatus.CANCELLED).lockVersion(1L).build());

        mockMvc.perform(post(path)
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"lockVersion\":0}"))
                .andExpect(status().isOk());

        verify(cancelService).cancel(eq(REVISION_ID), eq(0L), eq(900L));
        verify(idempotencyService).complete(eq(RECORD_ID), anyString(), eq(200), anyString());
    }
}
