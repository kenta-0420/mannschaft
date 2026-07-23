package com.mannschaft.app.billing.beta.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.billing.beta.BetaGrantEntity;
import com.mannschaft.app.billing.beta.BetaGrantQueryService;
import com.mannschaft.app.billing.beta.BetaGrantService;
import com.mannschaft.app.billing.beta.BetaPerkCandidateService;
import com.mannschaft.app.billing.beta.BetaPerkCriteriaEntity;
import com.mannschaft.app.billing.beta.BetaPerkCriteriaId;
import com.mannschaft.app.billing.beta.BetaPerkCriteriaRepository;
import com.mannschaft.app.billing.beta.BetaPerkCriteriaService;
import com.mannschaft.app.billing.beta.BetaPerkErrorCode;
import com.mannschaft.app.billing.beta.GrantKind;
import com.mannschaft.app.billing.beta.dto.BetaGrantDetailResponse;
import com.mannschaft.app.billing.beta.dto.BetaGrantPageResponse;
import com.mannschaft.app.billing.beta.BetaRevokeReason;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.GlobalExceptionHandler;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.team.service.TeamOrgMembershipQueryService;
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
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F20.3 ベータ特典 シスアド API 契約テスト（試練・test-first）。
 *
 * <p>{@code MockMvcBuilders.standaloneSetup} + {@link GlobalExceptionHandler}（F20.1
 * {@code BillingContractControllerTest} 同型）。ステータス/レスポンス契約・エラーコードのマッピング・
 * DTO 一次ゲート（{@code @Min}/{@code @Max}・enum バインド）を検証する。認可の {@code @PreAuthorize} 実評価は
 * {@link BetaPerkApiRuntimeAuthorizationTest} が別途担保する。</p>
 *
 * <p>{@code BetaPerkCriteriaService} のみ<b>実物</b>を使い（条件マスタの全指標 NULL 検証＝AC-N2 を実挙動で確認）、
 * その他 Service は mock で差す。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("F20.3 シスアド ベータ特典 API 契約テスト")
class SystemAdminBetaPerkControllerTest {

    @Mock
    private BetaGrantService betaGrantService;
    @Mock
    private BetaGrantQueryService betaGrantQueryService;
    @Mock
    private BetaPerkCandidateService betaPerkCandidateService;
    @Mock
    private TeamOrgMembershipQueryService teamOrgMembershipQueryService;
    @Mock
    private BetaPerkCriteriaRepository criteriaRepository; // 実 BetaPerkCriteriaService の依存

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockedStatic<SecurityUtils> securityUtilsMock;

    private static final long ADMIN_ID = 1L;
    private static final UUID GRANT_ID = UUID.fromString("0198aaaa-bbbb-cccc-dddd-eeeeffff0001");

    @BeforeEach
    void setUp() {
        objectMapper.findAndRegisterModules();
        MessageSource ms = new StaticMessageSource();
        BetaPerkCriteriaService realCriteriaService = new BetaPerkCriteriaService(criteriaRepository);
        SystemAdminBetaPerkController controller = new SystemAdminBetaPerkController(
                betaGrantService, betaGrantQueryService, realCriteriaService,
                betaPerkCandidateService, teamOrgMembershipQueryService);
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .setControllerAdvice(new GlobalExceptionHandler(ms))
                .setValidator(validator)
                .build();
        securityUtilsMock = Mockito.mockStatic(SecurityUtils.class);
        securityUtilsMock.when(SecurityUtils::getCurrentUserId).thenReturn(ADMIN_ID);
    }

    @AfterEach
    void tearDown() {
        securityUtilsMock.close();
    }

    private BetaGrantDetailResponse detail() {
        return BetaGrantDetailResponse.builder()
                .grantId(GRANT_ID.toString())
                .betaPhase(2)
                .grantKind("TEAM_ORG")
                .scopeKind("TEAM")
                .scopeId(123L)
                .organizationId(45L)
                .grantedAt(LocalDateTime.now())
                .featureKeys(List.of("ads.hide"))
                .reviewFlag(false)
                .build();
    }

    // ============================================================
    // ② 手動付与
    // ============================================================

    @Test
    @DisplayName("AC 正常系: 手動付与は 201・審査系を含む詳細を返す（transferable は露出しない=AC-A3）")
    void createGrant_201() throws Exception {
        given(teamOrgMembershipQueryService.findActiveOrganizationIds(123L)).willReturn(List.of(45L));
        BetaGrantEntity saved = Mockito.mock(BetaGrantEntity.class);
        given(saved.getId()).willReturn(GRANT_ID);
        given(betaGrantService.grantBetaPerk(eq(GrantKind.TEAM_ORG), eq(2),
                any(), eq(123L), eq(45L), eq(false), eq(ADMIN_ID))).willReturn(saved);
        given(betaGrantQueryService.getDetail(GRANT_ID)).willReturn(detail());

        mockMvc.perform(post("/api/v1/system-admin/beta-perks/grants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"grantKind\":\"TEAM_ORG\",\"betaPhase\":2,\"scopeKind\":\"TEAM\",\"scopeId\":123}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.grantId").value(GRANT_ID.toString()))
                .andExpect(jsonPath("$.data.transferable").doesNotExist());
    }

    @Test
    @DisplayName("AC-A1: INDIVIDUAL×TEAM は GRANT_SCOPE_MISMATCH 422")
    void createGrant_scopeMismatch_422() throws Exception {
        given(teamOrgMembershipQueryService.findActiveOrganizationIds(123L)).willReturn(List.of());
        willThrow(new BusinessException(BetaPerkErrorCode.GRANT_SCOPE_MISMATCH))
                .given(betaGrantService).grantBetaPerk(any(), anyInt(), any(), any(), any(), eq(false), any());

        mockMvc.perform(post("/api/v1/system-admin/beta-perks/grants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"grantKind\":\"INDIVIDUAL\",\"betaPhase\":2,\"scopeKind\":\"TEAM\",\"scopeId\":123}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("BETA_PERK_007"));
    }

    @Test
    @DisplayName("AC-B5: betaPhase=5 は BETA_PHASE_INVALID 400")
    void createGrant_phaseInvalid_400() throws Exception {
        willThrow(new BusinessException(BetaPerkErrorCode.BETA_PHASE_INVALID))
                .given(betaGrantService).grantBetaPerk(any(), anyInt(), any(), any(), any(), eq(false), any());

        mockMvc.perform(post("/api/v1/system-admin/beta-perks/grants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"grantKind\":\"INDIVIDUAL\",\"betaPhase\":5,\"scopeKind\":\"USER\",\"scopeId\":9}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("BETA_PERK_004"));
    }

    // ============================================================
    // ③ 取消
    // ============================================================

    @Test
    @DisplayName("AC 正常系: 取消は 200・reason をドメインへ変換して渡す")
    void revokeGrant_200() throws Exception {
        given(betaGrantQueryService.getDetail(GRANT_ID)).willReturn(detail());
        mockMvc.perform(post("/api/v1/system-admin/beta-perks/grants/{id}/revoke", GRANT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"TERMS_VIOLATION\"}"))
                .andExpect(status().isOk());
        verify(betaGrantService).revoke(eq(GRANT_ID), eq(BetaRevokeReason.TERMS_VIOLATION), eq(ADMIN_ID), eq(null));
    }

    @Test
    @DisplayName("AC-A6: 別テナント/不在 grantId の取消は GRANT_NOT_FOUND 404 秘匿")
    void revokeGrant_notFound_404() throws Exception {
        willThrow(new BusinessException(BetaPerkErrorCode.GRANT_NOT_FOUND))
                .given(betaGrantService).revoke(eq(GRANT_ID), any(), any(), any());
        mockMvc.perform(post("/api/v1/system-admin/beta-perks/grants/{id}/revoke", GRANT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"OTHER\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("BETA_PERK_001"));
    }

    @Test
    @DisplayName("AC 取消事由: WITHDRAWAL は API enum に無くバインド失敗で 400")
    void revokeGrant_withdrawalRejected_400() throws Exception {
        mockMvc.perform(post("/api/v1/system-admin/beta-perks/grants/{id}/revoke", GRANT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"WITHDRAWAL\"}"))
                .andExpect(status().isBadRequest());
    }

    // ============================================================
    // ④ 延長
    // ============================================================

    @Test
    @DisplayName("AC 正常系: 延長は 200")
    void extendGrant_200() throws Exception {
        given(betaGrantQueryService.getDetail(GRANT_ID)).willReturn(detail());
        mockMvc.perform(post("/api/v1/system-admin/beta-perks/grants/{id}/extend", GRANT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"extensionMonths\":12}"))
                .andExpect(status().isOk());
        verify(betaGrantService).extend(eq(GRANT_ID), eq(12), eq(null));
    }

    @Test
    @DisplayName("AC-B6: extensionMonths=0 は 400（DTO @Min）")
    void extendGrant_monthsTooSmall_400() throws Exception {
        mockMvc.perform(post("/api/v1/system-admin/beta-perks/grants/{id}/extend", GRANT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"extensionMonths\":0}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("AC-B6: extensionMonths=25 は 400（DTO @Max）")
    void extendGrant_monthsTooLarge_400() throws Exception {
        mockMvc.perform(post("/api/v1/system-admin/beta-perks/grants/{id}/extend", GRANT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"extensionMonths\":25}"))
                .andExpect(status().isBadRequest());
    }

    // ============================================================
    // ⑤⑥ 審査
    // ============================================================

    @Test
    @DisplayName("AC 正常系: 審査解決は 200")
    void resolveReview_200() throws Exception {
        given(betaGrantQueryService.getDetail(GRANT_ID)).willReturn(detail());
        mockMvc.perform(post("/api/v1/system-admin/beta-perks/grants/{id}/resolve-review", GRANT_ID))
                .andExpect(status().isOk());
        verify(betaGrantService).resolveReview(eq(GRANT_ID), eq(ADMIN_ID), eq(null));
    }

    @Test
    @DisplayName("AC 正常系: 審査フラグ設定は 200")
    void flagReview_200() throws Exception {
        given(betaGrantQueryService.getDetail(GRANT_ID)).willReturn(detail());
        mockMvc.perform(post("/api/v1/system-admin/beta-perks/grants/{id}/flag-review", GRANT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"疑いあり\"}"))
                .andExpect(status().isOk());
        verify(betaGrantService).flagReview(eq(GRANT_ID), eq(ADMIN_ID), eq(null));
    }

    // ============================================================
    // ① 一覧・⑦ 候補
    // ============================================================

    @Test
    @DisplayName("AC 正常系: 付与一覧は 200")
    void listGrants_200() throws Exception {
        given(betaGrantQueryService.searchGrants(any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .willReturn(BetaGrantPageResponse.builder()
                        .content(List.of(detail())).page(0).size(20).totalElements(1).build());
        mockMvc.perform(get("/api/v1/system-admin/beta-perks/grants"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    @DisplayName("AC 正常系: 候補 dry-run は 200")
    void listCandidates_200() throws Exception {
        given(betaPerkCandidateService.findCandidates(eq(GrantKind.TEAM_ORG), eq(2), anyInt(), anyInt()))
                .willReturn(List.of());
        mockMvc.perform(get("/api/v1/system-admin/beta-perks/candidates")
                        .param("grantKind", "TEAM_ORG").param("betaPhase", "2"))
                .andExpect(status().isOk());
    }

    // ============================================================
    // ⑧⑨ 条件マスタ（実 BetaPerkCriteriaService）
    // ============================================================

    @Test
    @DisplayName("AC 正常系: 条件マスタ取得は 200")
    void getCriteria_200() throws Exception {
        BetaPerkCriteriaEntity e = BetaPerkCriteriaEntity.builder()
                .betaPhase(2).grantKind(GrantKind.INDIVIDUAL)
                .evaluationWindowDays(30).minActiveDays(14).enabled(true).build();
        given(criteriaRepository.findById(new BetaPerkCriteriaId(2, GrantKind.INDIVIDUAL)))
                .willReturn(Optional.of(e));
        mockMvc.perform(get("/api/v1/system-admin/beta-perks/criteria/{p}/{k}", 2, "INDIVIDUAL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.minActiveDays").value(14));
    }

    @Test
    @DisplayName("AC 条件マスタ: 未定義は CRITERIA_NOT_FOUND 404")
    void getCriteria_notFound_404() throws Exception {
        given(criteriaRepository.findById(any())).willReturn(Optional.empty());
        mockMvc.perform(get("/api/v1/system-admin/beta-perks/criteria/{p}/{k}", 3, "TEAM_ORG"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("BETA_PERK_010"));
    }

    @Test
    @DisplayName("AC-N2: 条件マスタ upsert で全指標 NULL は CRITERIA_VALIDATION_FAILED 400")
    void upsertCriteria_allNull_400() throws Exception {
        mockMvc.perform(put("/api/v1/system-admin/beta-perks/criteria/{p}/{k}", 2, "INDIVIDUAL")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"evaluationWindowDays\":30,\"enabled\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("BETA_PERK_009"));
    }

    @Test
    @DisplayName("AC 正常系: 条件マスタ upsert は 200")
    void upsertCriteria_200() throws Exception {
        given(criteriaRepository.findById(any())).willReturn(Optional.empty());
        given(criteriaRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        mockMvc.perform(put("/api/v1/system-admin/beta-perks/criteria/{p}/{k}", 2, "INDIVIDUAL")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"evaluationWindowDays\":30,\"minActiveDays\":14,\"enabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.minActiveDays").value(14));
    }
}
