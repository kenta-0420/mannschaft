package com.mannschaft.app.billing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.billing.EntitlementErrorCode;
import com.mannschaft.app.billing.EntitlementNotEntitledDetails;
import com.mannschaft.app.billing.EntitlementScopeKind;
import com.mannschaft.app.billing.FeatureNotEntitledException;
import com.mannschaft.app.billing.api.dto.ContractResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.GlobalExceptionHandler;
import com.mannschaft.app.common.SecurityUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F20.1: {@link BillingContractController} 契約テスト（試練・test-first）。
 *
 * <p>{@code MockMvcBuilders.standaloneSetup} + {@link GlobalExceptionHandler}（既存
 * SystemAdminFeePolicyControllerTest 同型）。認可（scope ADMIN / 本人固定・{@code @PreAuthorize}）は
 * SecurityConfig の {@code anyRequest().authenticated()} ＋ メソッドセキュリティで担保し、
 * その注釈の存在は {@code BillingAuthorizationAnnotationTest} が別途照合する（AC-10）。本テストは
 * ステータス/レスポンス契約・IDOR 404 秘匿（AC-10）・402/403 マッピング（AC-09）・Idempotency-Key 必須を検証。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BillingContractController 契約テスト")
class BillingContractControllerTest {

    @Mock
    private BillingContractApplicationService appService;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockedStatic<SecurityUtils> securityUtilsMock;

    private static final long USER_ID = 9L;

    private ContractResponse sample(String scopeKind, Long scopeId, String status) {
        return ContractResponse.builder()
                .contractId(UUID.randomUUID().toString())
                .scopeKind(scopeKind)
                .scopeId(scopeId)
                .contractKind("PLAN")
                .planKey("FULL")
                .featureKey(null)
                .status(status)
                .memberCountSnapshot(34)
                .bandNoSnapshot((short) 2)
                .priceJpySnapshot(null)
                .contractedAt(LocalDateTime.now())
                .grantedFeatureKeys(List.of("ads.hide"))
                .build();
    }

    @BeforeEach
    void setUp() {
        objectMapper.findAndRegisterModules();
        MessageSource ms = new StaticMessageSource();
        BillingContractController controller = new BillingContractController(appService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .setControllerAdvice(new GlobalExceptionHandler(ms))
                .build();
        securityUtilsMock = Mockito.mockStatic(SecurityUtils.class);
        securityUtilsMock.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
    }

    @AfterEach
    void tearDown() {
        securityUtilsMock.close();
    }

    private String body(String contractKind, String planKey, String featureKey) throws Exception {
        return objectMapper.writeValueAsString(new java.util.LinkedHashMap<>() {{
            put("contractKind", contractKind);
            put("planKey", planKey);
            put("featureKey", featureKey);
        }});
    }

    // ---- 正常系（201/200） ----

    @Test
    @DisplayName("AC 正常系: /me 契約作成は 201・Idempotency-Key を渡す")
    void createForMe_201() throws Exception {
        given(appService.create(eq(EntitlementScopeKind.USER), eq(USER_ID), eq(USER_ID), any(), eq("idem-1")))
                .willReturn(sample("USER", USER_ID, "ACTIVE"));
        mockMvc.perform(post("/api/v1/me/billing/contracts")
                        .header("Idempotency-Key", "idem-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("PLAN", "FULL", null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.grantedFeatureKeys[0]").value("ads.hide"));
    }

    @Test
    @DisplayName("AC 正常系: /teams 契約作成は 201")
    void createForTeam_201() throws Exception {
        given(appService.create(eq(EntitlementScopeKind.TEAM), eq(123L), eq(USER_ID), any(), eq("idem-2")))
                .willReturn(sample("TEAM", 123L, "ACTIVE"));
        mockMvc.perform(post("/api/v1/teams/{teamId}/billing/contracts", 123L)
                        .header("Idempotency-Key", "idem-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("PLAN", "FULL", null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.scopeKind").value("TEAM"));
    }

    @Test
    @DisplayName("AC 正常系: /organizations 解約は 200・CANCELLED")
    void cancelForOrg_200() throws Exception {
        UUID cid = UUID.randomUUID();
        given(appService.cancel(eq(EntitlementScopeKind.ORG), eq(55L), eq(cid), eq(USER_ID)))
                .willReturn(sample("ORG", 55L, "CANCELLED"));
        mockMvc.perform(delete("/api/v1/organizations/{orgId}/billing/contracts/{cid}", 55L, cid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));
    }

    @Test
    @DisplayName("AC 正常系: /teams プラン変更は 200")
    void changeForTeam_200() throws Exception {
        UUID cid = UUID.randomUUID();
        given(appService.changePlan(eq(EntitlementScopeKind.TEAM), eq(123L), eq(cid), any(), eq(USER_ID)))
                .willReturn(sample("TEAM", 123L, "ACTIVE"));
        mockMvc.perform(put("/api/v1/teams/{teamId}/billing/contracts/{cid}", 123L, cid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of("planKey", "FULL"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.planKey").value("FULL"));
    }

    // ---- Idempotency-Key 必須（M-1） ----

    @Test
    @DisplayName("AC Idempotency: 作成で Idempotency-Key ヘッダ欠落は 400")
    void createForMe_missingIdempotencyKey_400() throws Exception {
        mockMvc.perform(post("/api/v1/me/billing/contracts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("PLAN", "FULL", null)))
                .andExpect(status().isBadRequest());
    }

    // ---- AC-10 IDOR: 他スコープの契約 ID は 404 秘匿 ----

    @Test
    @DisplayName("AC-10 IDOR: 別スコープの契約解約は 404 秘匿（ENTITLEMENT_007）")
    void cancelForTeam_crossScope_404() throws Exception {
        UUID cid = UUID.randomUUID();
        willThrow(new BusinessException(EntitlementErrorCode.CONTRACT_NOT_FOUND))
                .given(appService).cancel(eq(EntitlementScopeKind.TEAM), eq(123L), eq(cid), eq(USER_ID));
        mockMvc.perform(delete("/api/v1/teams/{teamId}/billing/contracts/{cid}", 123L, cid))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ENTITLEMENT_007"));
    }

    // ---- AC-09 402/403 マッピング ----

    @Test
    @DisplayName("AC-1/AC-22: FEATURE_NOT_ENTITLED は 402（購入導線あり）・details に購入導線情報が載る")
    void create_notEntitled_402() throws Exception {
        EntitlementNotEntitledDetails details = EntitlementNotEntitledDetails.builder()
                .featureKey("ads.hide")
                .addonAvailable(true)
                .addonPriceJpy(500)
                .plansContaining(List.of("FULL"))
                .scopeKind("USER")
                .scopeId(USER_ID)
                .build();
        willThrow(new FeatureNotEntitledException(details))
                .given(appService).create(any(), any(), any(), any(), any());
        mockMvc.perform(post("/api/v1/me/billing/contracts")
                        .header("Idempotency-Key", "idem-x")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("ADDON", null, "ads.hide")))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.error.code").value("ENTITLEMENT_003"))
                .andExpect(jsonPath("$.error.details.featureKey").value("ads.hide"))
                .andExpect(jsonPath("$.error.details.addonAvailable").value(true))
                .andExpect(jsonPath("$.error.details.addonPriceJpy").value(500))
                .andExpect(jsonPath("$.error.details.plansContaining[0]").value("FULL"))
                .andExpect(jsonPath("$.error.details.scopeKind").value("USER"))
                .andExpect(jsonPath("$.error.details.scopeId").value(USER_ID));
    }

    @Test
    @DisplayName("AC-16: FEATURE_FORBIDDEN_FOR_SCOPE は 403（購入手段なし）・details は存在しない")
    void create_forbidden_403() throws Exception {
        willThrow(new BusinessException(EntitlementErrorCode.FEATURE_FORBIDDEN_FOR_SCOPE))
                .given(appService).create(any(), any(), any(), any(), any());
        mockMvc.perform(post("/api/v1/me/billing/contracts")
                        .header("Idempotency-Key", "idem-y")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("ADDON", null, "monetization.paywall")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ENTITLEMENT_004"))
                .andExpect(jsonPath("$.error.details").doesNotExist());
    }

    // ---- 契約作成のエラー系（404/409/422） ----

    @Test
    @DisplayName("AC: 存在しない planKey は 404（ENTITLEMENT_001）")
    void create_planNotFound_404() throws Exception {
        willThrow(new BusinessException(EntitlementErrorCode.PLAN_NOT_FOUND))
                .given(appService).create(any(), any(), any(), any(), any());
        mockMvc.perform(post("/api/v1/me/billing/contracts")
                        .header("Idempotency-Key", "idem-z")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("PLAN", "NOPE", null)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ENTITLEMENT_001"));
    }

    @Test
    @DisplayName("AC: アクティブ契約重複は 409（ENTITLEMENT_006）")
    void create_alreadyActive_409() throws Exception {
        willThrow(new BusinessException(EntitlementErrorCode.CONTRACT_ALREADY_ACTIVE))
                .given(appService).create(any(), any(), any(), any(), any());
        mockMvc.perform(post("/api/v1/teams/{teamId}/billing/contracts", 123L)
                        .header("Idempotency-Key", "idem-w")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("PLAN", "FULL", null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ENTITLEMENT_006"));
    }

    @Test
    @DisplayName("AC: addon 不可の機能は 422（ENTITLEMENT_008）")
    void create_addonNotAvailable_422() throws Exception {
        willThrow(new BusinessException(EntitlementErrorCode.ADDON_NOT_AVAILABLE))
                .given(appService).create(any(), any(), any(), any(), any());
        mockMvc.perform(post("/api/v1/me/billing/contracts")
                        .header("Idempotency-Key", "idem-a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("ADDON", null, "template.premium_modules")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("ENTITLEMENT_008"));
    }
}
