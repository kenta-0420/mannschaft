package com.mannschaft.app.billing.api;

import com.mannschaft.app.advertising.operational.MethodSecurityTestConfig;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.billing.BillingPriceProvisionRecoveryService;
import com.mannschaft.app.billing.BillingPriceVersionStatus;
import com.mannschaft.app.billing.BillingProductKind;
import com.mannschaft.app.billing.EntitlementScopeKind;
import com.mannschaft.app.billing.api.dto.PriceRevisionResponse;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
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
 * 検分（第4版）で見つかったトレーサビリティ漏れの穴埋め: AC-134・AC-136。
 *
 * <p>{@code BillingCheckoutControllerIdempotencyTest}（BC-23）と同一の流儀で、
 * {@link PriceRevisionController} が {@link BillingDurableIdempotencyService} を
 * <b>実際に</b> 通していることを controller 経由で固定する。</p>
 *
 * <h2>AC-134: create の再送で revision が二重に作られない</h2>
 * <p>REPLAY 決定時に {@link PriceRevisionCreateService#create} が一度も呼ばれないことを直接検証する。
 * 冪等基盤（{@code BillingDurableIdempotencyService}）自体の汎用テストは別に存在するが、
 * price-revisions の create 経路としてこれを固定した検体はこれまで無かった。</p>
 *
 * <h2>AC-136: 業務上の失敗で冪等レコードが FAILED のまま残らない</h2>
 * <p>provision/retry-provision/reconcile-provision は Stripe 通信不能等の業務失敗を
 * 例外化せず fail-forward で吸収し、常に 200 + 終局状態（{@code PROVISION_FAILED} 等）を返す
 * （{@code PriceRevisionProvisionServiceTest} の AC-67 が service 単体でこれを固定済み）。
 * ここでは、その 200 応答が controller の {@code idempotent()} で
 * {@link BillingDurableIdempotencyService#complete} として確定され、
 * {@link BillingDurableIdempotencyService#fail} が呼ばれない（＝レコードが FAILED のまま残らない）
 * ことを 3 エンドポイントすべてについて固定する。</p>
 */
@DisplayName("price-revisions API 耐久冪等性の結線検証（AC-134/AC-136）")
@WebMvcTest(PriceRevisionController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(MethodSecurityTestConfig.class)
class PriceRevisionControllerIdempotencyTest {

    private static final String BASE_PATH = "/api/v1/system-admin/billing/price-revisions";
    private static final UUID REVISION_ID = UUID.fromString("01999d74-5130-7000-8000-000000000060");
    private static final UUID RECORD_ID = UUID.fromString("01999d74-5130-7000-8000-000000000061");
    private static final String KEY = "00000000-0000-0000-0000-000000000601";
    private static final String CREATE_BODY = "{\"productKind\":\"PLAN\",\"productKey\":\"FULL\","
            + "\"scopeKind\":\"USER\",\"effectiveFrom\":\"2099-01-01T00:00:00Z\","
            + "\"bands\":[{\"bandNo\":1,\"minMembers\":1,\"maxMembers\":null,\"inputAmount\":1000,"
            + "\"taxBehavior\":\"EXCLUSIVE\",\"taxCode\":\"TAX10\"}]}";
    private static final String LOCK_BODY = "{\"lockVersion\":0}";

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

    @BeforeEach
    void authenticate() {
        Authentication auth = new UsernamePasswordAuthenticationToken(
                "900", null, List.of(new SimpleGrantedAuthority("ROLE_SYSTEM_ADMIN")));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    // ═════════ AC-134 ═════════

    @Test
    @DisplayName("AC-134: create の同一キー再送は保存済み応答を replay し、createService を再実行しない")
    void create_replay_createServiceを再実行しない() throws Exception {
        given(idempotencyService.begin(eq(900L), eq("POST"), eq(BASE_PATH), eq(KEY), anyString(), anyString()))
                .willReturn(new BillingIdempotencyDecision(
                        BillingIdempotencyDecisionKind.REPLAY, RECORD_ID, 201,
                        "{\"data\":{\"id\":\"" + REVISION_ID + "\",\"status\":\"DRAFT\"}}", 0L));

        mockMvc.perform(post(BASE_PATH)
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isCreated());

        verify(createService, never()).create(any(), anyLong());
    }

    @Test
    @DisplayName("AC-134: create の初回（ACQUIRED）は revision を1件作り、201応答をそのまま耐久化する")
    void create_acquired_1件だけ作成し応答を耐久化する() throws Exception {
        given(idempotencyService.begin(eq(900L), eq("POST"), eq(BASE_PATH), eq(KEY), anyString(), anyString()))
                .willReturn(new BillingIdempotencyDecision(
                        BillingIdempotencyDecisionKind.ACQUIRED, RECORD_ID, null, null, 0L));
        given(createService.create(any(), eq(900L))).willReturn(PriceRevisionResponse.builder()
                .id(REVISION_ID).productKind(BillingProductKind.PLAN).productKey("FULL")
                .scopeKind(EntitlementScopeKind.USER).revisionNo(1L).catalogRevision("REV-1")
                .status(BillingPriceVersionStatus.DRAFT).lockVersion(0L).build());

        mockMvc.perform(post(BASE_PATH)
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isCreated());

        verify(createService, org.mockito.Mockito.times(1)).create(any(), eq(900L));
        verify(idempotencyService).complete(eq(RECORD_ID), anyString(), eq(201), anyString());
        verify(idempotencyService, never()).fail(any(), anyString());
    }

    // ═════════ AC-136 ═════════

    @Test
    @DisplayName("AC-136: provisionの業務失敗（fail-forward吸収済み・200+PROVISION_FAILED）は"
            + " complete として確定し、冪等レコードをFAILEDのまま残さない")
    void provision_業務失敗はcompleteされFAILEDのまま残らない() throws Exception {
        String path = BASE_PATH + "/" + REVISION_ID + "/provision";
        given(idempotencyService.begin(eq(900L), eq("POST"), eq(path), eq(KEY), anyString(), anyString(),
                any(java.time.Duration.class)))
                .willReturn(new BillingIdempotencyDecision(
                        BillingIdempotencyDecisionKind.ACQUIRED, RECORD_ID, null, null, 0L));
        given(provisionService.provision(eq(REVISION_ID), eq(0L), eq(900L))).willReturn(
                PriceRevisionResponse.builder().id(REVISION_ID)
                        .status(BillingPriceVersionStatus.PROVISION_FAILED).lockVersion(1L).build());

        mockMvc.perform(post(path)
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(LOCK_BODY))
                .andExpect(status().isOk());

        verify(idempotencyService).complete(eq(RECORD_ID), anyString(), eq(200), anyString());
        verify(idempotencyService, never()).fail(any(), anyString());
    }

    @Test
    @DisplayName("AC-136: retry-provisionの業務失敗も complete として確定し、FAILEDのまま残さない")
    void retryProvision_業務失敗はcompleteされFAILEDのまま残らない() throws Exception {
        String path = BASE_PATH + "/" + REVISION_ID + "/retry-provision";
        given(idempotencyService.begin(eq(900L), eq("POST"), eq(path), eq(KEY), anyString(), anyString(),
                any(java.time.Duration.class)))
                .willReturn(new BillingIdempotencyDecision(
                        BillingIdempotencyDecisionKind.ACQUIRED, RECORD_ID, null, null, 0L));
        given(retryProvisionService.retryProvision(eq(REVISION_ID), eq(0L), eq(900L))).willReturn(
                PriceRevisionResponse.builder().id(REVISION_ID)
                        .status(BillingPriceVersionStatus.PROVISION_FAILED).lockVersion(2L).build());

        mockMvc.perform(post(path)
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(LOCK_BODY))
                .andExpect(status().isOk());

        verify(idempotencyService).complete(eq(RECORD_ID), anyString(), eq(200), anyString());
        verify(idempotencyService, never()).fail(any(), anyString());
    }

    @Test
    @DisplayName("AC-136: reconcile-provisionの業務失敗も complete として確定し、FAILEDのまま残さない")
    void reconcileProvision_業務失敗はcompleteされFAILEDのまま残らない() throws Exception {
        String path = BASE_PATH + "/" + REVISION_ID + "/reconcile-provision";
        given(idempotencyService.begin(eq(900L), eq("POST"), eq(path), eq(KEY), anyString(), anyString(),
                any(java.time.Duration.class)))
                .willReturn(new BillingIdempotencyDecision(
                        BillingIdempotencyDecisionKind.ACQUIRED, RECORD_ID, null, null, 0L));
        given(reconcileService.reconcileProvision(eq(REVISION_ID), eq(0L), eq(900L))).willReturn(
                PriceRevisionResponse.builder().id(REVISION_ID)
                        .status(BillingPriceVersionStatus.PROVISION_FAILED).lockVersion(3L).build());

        mockMvc.perform(post(path)
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(LOCK_BODY))
                .andExpect(status().isOk());

        verify(idempotencyService).complete(eq(RECORD_ID), anyString(), eq(200), anyString());
        verify(idempotencyService, never()).fail(any(), anyString());
    }
}
