package com.mannschaft.app.common.featuregate;

import com.mannschaft.app.admin.entity.FeatureFlagEntity;
import com.mannschaft.app.admin.repository.FeatureFlagRepository;
import com.mannschaft.app.advertising.PricingModel;
import com.mannschaft.app.advertising.dto.RateSimulatorResponse;
import com.mannschaft.app.advertising.service.RateSimulatorService;
import com.mannschaft.app.billing.EntitlementScopeKind;
import com.mannschaft.app.billing.api.BillingEntitlementQueryService;
import com.mannschaft.app.billing.api.dto.EntitlementSummaryResponse;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.security.AccessGuard;
import com.mannschaft.app.equipment.service.EquipmentItemService;
import com.mannschaft.app.family.service.CareLinkService;
import com.mannschaft.app.gamification.PeriodType;
import com.mannschaft.app.gamification.service.GamificationRankingService;
import com.mannschaft.app.market.service.MarketQueryService;
import com.mannschaft.app.matching.service.MatchProposalService;
import com.mannschaft.app.moderation.service.ContentReportService;
import com.mannschaft.app.moderation.service.ModerationAppealService;
import com.mannschaft.app.moderation.service.UserViolationService;
import com.mannschaft.app.moderation.service.WarningReReviewService;
import com.mannschaft.app.moderation.service.YabaiUnflagService;
import com.mannschaft.app.recruitment.service.RecruitmentListingService;
import com.mannschaft.app.repairplan.dto.RepairPlanDashboardResponse;
import com.mannschaft.app.repairplan.module.RepairPlanModuleGuard;
import com.mannschaft.app.repairplan.service.RepairPlanDashboardService;
import com.mannschaft.app.resume.service.ResumeService;
import com.mannschaft.app.shift.service.ShiftScheduleService;
import com.mannschaft.app.succession.service.SuccessionCovenantService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.translation.service.TranslationConfigService;
import com.mannschaft.app.translation.service.TranslationConfigService.TranslationConfigResponse;
import com.mannschaft.app.webhook.service.WebhookEndpointService;
import com.mannschaft.app.workflow.service.WorkflowTemplateService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.cache.CacheManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 既存17キーのうちGate対象16キーを、実Controller・実HTTP・実Aspectで検証する。 */
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("既存16 Gateキーの代表Controller HTTP統合試験")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class FeatureGateRepresentativeControllerIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private FeatureFlagRepository featureFlagRepository;
    @Autowired
    private CacheManager cacheManager;

    @MockitoSpyBean private ShiftScheduleService shiftScheduleService;
    @MockitoSpyBean private MatchProposalService matchProposalService;
    @MockitoSpyBean private BillingEntitlementQueryService billingEntitlementQueryService;
    @MockitoSpyBean private RateSimulatorService rateSimulatorService;
    @MockitoSpyBean private MarketQueryService marketQueryService;
    @MockitoSpyBean private WorkflowTemplateService workflowTemplateService;
    @MockitoSpyBean private EquipmentItemService equipmentItemService;
    @MockitoSpyBean private RepairPlanDashboardService repairPlanDashboardService;
    @MockitoSpyBean private RepairPlanModuleGuard repairPlanModuleGuard;
    @MockitoSpyBean private CareLinkService careLinkService;
    @MockitoSpyBean private ResumeService resumeService;
    @MockitoSpyBean private RecruitmentListingService recruitmentListingService;
    @MockitoSpyBean private SuccessionCovenantService successionCovenantService;
    @MockitoSpyBean private ContentReportService contentReportService;
    @MockitoSpyBean private UserViolationService userViolationService;
    @MockitoSpyBean private ModerationAppealService moderationAppealService;
    @MockitoSpyBean private YabaiUnflagService yabaiUnflagService;
    @MockitoSpyBean private WarningReReviewService warningReReviewService;
    @MockitoSpyBean private WebhookEndpointService webhookEndpointService;
    @MockitoSpyBean private TranslationConfigService translationConfigService;
    @MockitoSpyBean private GamificationRankingService gamificationRankingService;
    @MockitoSpyBean private AccessControlService accessControlService;
    @MockitoSpyBean private AccessGuard accessGuard;

    @BeforeEach
    void setUp() {
        clearFlagCaches();
        doReturn(true).when(accessGuard)
                .isScopeMember(any(Authentication.class), anyLong(), eq("TEAM"));
        doNothing().when(accessControlService).checkMembership(anyLong(), anyLong(), any());
        doNothing().when(repairPlanModuleGuard).requireEnabled(any(), anyLong());
    }

    @AfterEach
    void tearDown() {
        clearFlagCaches();
    }

    @ParameterizedTest(name = "OFF: {0}")
    @EnumSource(Representative.class)
    @WithMockUser(username = "1", roles = "SYSTEM_ADMIN")
    @DisplayName("OFF時は403 FEATURE_GATE_001でControllerサービスを呼ばない")
    void offRejectsBeforeControllerService(Representative representative) throws Exception {
        setFlag(representative.flagKey, false);
        clearInvocations(servicesFor(representative));

        try {
            mockMvc.perform(get(representative.path))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("FEATURE_GATE_001"));

            verifyNoInteractions(servicesFor(representative));
        } finally {
            // 後続の統合試験へ OFF を漏らさないよう、repository transaction で ON を確定する。
            setFlag(representative.flagKey, true);
        }
    }

    @ParameterizedTest(name = "ON: {0}")
    @EnumSource(Representative.class)
    @WithMockUser(username = "1", roles = "SYSTEM_ADMIN")
    @DisplayName("ON時は既存200契約を保ちControllerサービスへ到達する")
    void onPreservesExistingControllerContract(Representative representative) throws Exception {
        setFlag(representative.flagKey, true);
        stubSuccessfulResponse(representative);
        clearInvocations(servicesFor(representative));

        mockMvc.perform(get(representative.path))
                .andExpect(status().isOk());

        assertThat(List.of(servicesFor(representative)))
                .as(representative.name())
                .anySatisfy(service -> assertThat(mockingDetails(service).getInvocations()).isNotEmpty());
    }

    private void setFlag(String flagKey, boolean enabled) {
        FeatureFlagEntity entity = featureFlagRepository.findByFlagKey(flagKey)
                .orElseGet(() -> FeatureFlagEntity.builder()
                        .flagKey(flagKey)
                        .description("Gate代表Controller IT")
                        .build());
        entity.updateFlag(enabled, null);
        featureFlagRepository.saveAndFlush(entity);
        clearFlagCaches();
    }

    private void clearFlagCaches() {
        if (cacheManager.getCache("featureFlags") != null) cacheManager.getCache("featureFlags").clear();
        if (cacheManager.getCache("featureFlagsPublicList") != null) {
            cacheManager.getCache("featureFlagsPublicList").clear();
        }
    }

    private Object[] servicesFor(Representative representative) {
        return switch (representative) {
            case SHIFT -> new Object[]{shiftScheduleService};
            case MATCHING -> new Object[]{matchProposalService};
            case BILLING_PAYMENT -> new Object[]{billingEntitlementQueryService};
            case PROMOTION -> new Object[]{rateSimulatorService};
            case MARKET -> new Object[]{marketQueryService};
            case WORKFLOW_FORMS -> new Object[]{workflowTemplateService};
            case FACILITY -> new Object[]{equipmentItemService};
            case PROPERTY_REPAIRPLAN -> new Object[]{repairPlanDashboardService};
            case FAMILY_CARE -> new Object[]{careLinkService};
            case SKILL_RESUME -> new Object[]{resumeService};
            case RECRUITMENT -> new Object[]{recruitmentListingService};
            case SUCCESSION_PROXY -> new Object[]{successionCovenantService};
            case MODERATION_INCIDENT -> new Object[]{contentReportService, userViolationService,
                    moderationAppealService, yabaiUnflagService, warningReReviewService};
            case WEBHOOK_SYNC -> new Object[]{webhookEndpointService};
            case TRANSLATION_SEARCH -> new Object[]{translationConfigService};
            case GAMIFICATION -> new Object[]{gamificationRankingService};
        };
    }

    private void stubSuccessfulResponse(Representative representative) {
        switch (representative) {
            case SHIFT -> doReturn(List.of()).when(shiftScheduleService).listSchedules(1L, 1L);
            case MATCHING -> doReturn(Page.empty()).when(matchProposalService)
                    .listTeamProposals(eq(1L), any(), any());
            case BILLING_PAYMENT -> doReturn(mock(EntitlementSummaryResponse.class))
                    .when(billingEntitlementQueryService).getSummary(EntitlementScopeKind.USER, 1L);
            case PROMOTION -> doReturn(mock(RateSimulatorResponse.class)).when(rateSimulatorService)
                    .simulate(any(), any(), eq(PricingModel.CPM), any(), any(), any());
            case MARKET -> doReturn(Page.empty()).when(marketQueryService)
                    .searchListings(any(), any(), any(), any(), anyBoolean(), any(), any());
            case WORKFLOW_FORMS -> doReturn(Page.empty()).when(workflowTemplateService)
                    .listTemplates(eq("teams"), eq(1L), eq(1L), any());
            case FACILITY -> doReturn(Page.empty()).when(equipmentItemService)
                    .listByOrganization(eq(1L), eq(1L), any(), any(), any(), any());
            case PROPERTY_REPAIRPLAN -> doReturn(mock(RepairPlanDashboardResponse.class))
                    .when(repairPlanDashboardService).get(1L, "TEAM", 1L);
            case FAMILY_CARE -> doReturn(List.of()).when(careLinkService)
                    .getActiveLinksForCareRecipient(1L);
            case SKILL_RESUME -> doReturn(List.of()).when(resumeService).listResumes(1L);
            case RECRUITMENT -> doReturn(Page.empty()).when(recruitmentListingService)
                    .searchPublicListings(any(), any(), any(), any(), any(), any(), any(), any());
            case SUCCESSION_PROXY -> doReturn(Page.empty(PageRequest.of(0, 20))).when(successionCovenantService)
                    .listOrgCovenants(eq(1L), any(), eq(1L));
            case MODERATION_INCIDENT -> { }
            case WEBHOOK_SYNC -> doReturn(ApiResponse.of(List.of())).when(webhookEndpointService)
                    .listEndpoints(1L, "TEAM", 1L);
            case TRANSLATION_SEARCH -> doReturn(ApiResponse.of(mock(TranslationConfigResponse.class)))
                    .when(translationConfigService).getConfig("TEAM", 1L);
            case GAMIFICATION -> doReturn(ApiResponse.of(List.of())).when(gamificationRankingService)
                    .getRanking("TEAM", 1L, PeriodType.WEEKLY, "2026-W35");
        }
    }

    private enum Representative {
        SHIFT("FEATURE_SHIFT_ENABLED", "/api/v1/shifts/schedules?teamId=1"),
        MATCHING("FEATURE_MATCHING_ENABLED", "/api/v1/teams/1/matching/proposals"),
        BILLING_PAYMENT("FEATURE_BILLING_PAYMENT_ENABLED", "/api/v1/me/entitlements"),
        PROMOTION("FEATURE_PROMOTION_ENABLED", "/api/v1/advertiser/rate-simulator?pricingModel=CPM"),
        MARKET("FEATURE_MARKET_ENABLED", "/api/v1/public/market/listings"),
        WORKFLOW_FORMS("FEATURE_WORKFLOW_FORMS_ENABLED", "/api/v1/teams/1/workflow-templates"),
        FACILITY("FEATURE_FACILITY_ENABLED", "/api/v1/organizations/1/equipment"),
        PROPERTY_REPAIRPLAN("FEATURE_PROPERTY_REPAIRPLAN_ENABLED", "/api/v1/teams/1/repair-plan/dashboard"),
        FAMILY_CARE("FEATURE_FAMILY_CARE_ENABLED", "/api/v1/me/care-links/watchers"),
        SKILL_RESUME("FEATURE_SKILL_RESUME_ENABLED", "/api/v1/resumes"),
        RECRUITMENT("FEATURE_RECRUITMENT_ENABLED", "/api/v1/recruitment-listings/search"),
        SUCCESSION_PROXY("FEATURE_SUCCESSION_PROXY_ENABLED", "/api/v1/organizations/1/succession/covenants"),
        MODERATION_INCIDENT("FEATURE_MODERATION_INCIDENT_ENABLED", "/api/v1/system-admin/moderation/dashboard"),
        WEBHOOK_SYNC("FEATURE_WEBHOOK_SYNC_ENABLED", "/api/webhooks/endpoints?scopeType=TEAM&scopeId=1"),
        TRANSLATION_SEARCH("FEATURE_TRANSLATION_SEARCH_ENABLED", "/api/v1/teams/1/translations/config"),
        GAMIFICATION("FEATURE_GAMIFICATION_ENABLED", "/api/v1/teams/1/gamification/rankings?periodLabel=2026-W35");

        private final String flagKey;
        private final String path;

        Representative(String flagKey, String path) {
            this.flagKey = flagKey;
            this.path = path;
        }
    }
}
