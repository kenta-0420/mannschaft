package com.mannschaft.app.common.architecture;

import com.mannschaft.app.common.featuregate.AlwaysReachable;
import com.mannschaft.app.common.featuregate.RequireFeature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("既存17 Gateキーの代表Controller宣言")
class FeatureGateRepresentativeDeclarationTest {

    private static final Map<String, String> GATED = Map.ofEntries(
            entry("shift.controller.ShiftScheduleController", "listSchedules", "FEATURE_SHIFT_ENABLED"),
            entry("matching.controller.MatchProposalController", "listTeamProposals", "FEATURE_MATCHING_ENABLED"),
            entry("billing.api.BillingEntitlementSummaryController", "me", "FEATURE_BILLING_PAYMENT_ENABLED"),
            entry("advertising.controller.AdvertiserDashboardController", "rateSimulator", "FEATURE_PROMOTION_ENABLED"),
            entry("market.controller.MarketController", "listListings", "FEATURE_MARKET_ENABLED"),
            entry("workflow.controller.WorkflowTemplateController", "listTemplates", "FEATURE_WORKFLOW_FORMS_ENABLED"),
            entry("equipment.controller.OrganizationEquipmentController", "listEquipment", "FEATURE_FACILITY_ENABLED"),
            entry("repairplan.controller.RepairPlanDashboardController", "getDashboard", "FEATURE_PROPERTY_REPAIRPLAN_ENABLED"),
            entry("family.controller.CareLinkController", "getActiveWatchers", "FEATURE_FAMILY_CARE_ENABLED"),
            entry("resume.controller.ResumeController", "listResumes", "FEATURE_SKILL_RESUME_ENABLED"),
            entry("recruitment.controller.RecruitmentListingController", "searchListings", "FEATURE_RECRUITMENT_ENABLED"),
            entry("succession.controller.SuccessionCovenantController", "listOrgCovenants", "FEATURE_SUCCESSION_PROXY_ENABLED"),
            entry("moderation.controller.SystemAdminModerationController", "getDashboard", "FEATURE_MODERATION_INCIDENT_ENABLED"),
            entry("webhook.controller.WebhookEndpointController", "listEndpoints", "FEATURE_WEBHOOK_SYNC_ENABLED"),
            entry("translation.controller.TranslationConfigController", "getTeamConfig", "FEATURE_TRANSLATION_SEARCH_ENABLED"),
            entry("gamification.controller.GamificationRankingController", "getRanking", "FEATURE_GAMIFICATION_ENABLED"));

    private static final List<String> GDPR_LIFELINES = List.of(
            "requestExport", "getExportStatus", "getDownloadUrl", "getDeletionPreview");

    @Test
    @DisplayName("16キーはレビュー済み代表入口へ正確にGate宣言される")
    void sixteenKeysAreDeclaredOnReviewedRepresentativeMethods() throws ClassNotFoundException {
        assertThat(GATED).hasSize(16);
        assertThat(GATED.values()).doesNotHaveDuplicates();
        for (Map.Entry<String, String> expected : GATED.entrySet()) {
            Method method = uniqueMethod(expected.getKey());
            RequireFeature declaration = method.getAnnotation(RequireFeature.class);
            assertThat(declaration).as(expected.getKey()).isNotNull();
            assertThat(declaration.value()).as(expected.getKey()).containsExactly(expected.getValue());
        }
    }

    @Test
    @DisplayName("GDPRキーは本人権利4入口をGateせず常時到達として代表される")
    void gdprKeyIsRepresentedByAlwaysReachableLifelines() throws ClassNotFoundException {
        assertThat(GDPR_LIFELINES).hasSize(4);
        for (String methodName : GDPR_LIFELINES) {
            String key = "com.mannschaft.app.gdpr.controller.GdprController#" + methodName;
            Method method = uniqueMethod(key);
            assertThat(method.getAnnotation(RequireFeature.class)).as(key).isNull();
            assertThat(method.getAnnotation(AlwaysReachable.class)).as(key).isNotNull();
        }
    }

    private static Method uniqueMethod(String key) throws ClassNotFoundException {
        String[] parts = key.split("#", 2);
        Class<?> controller = Class.forName(parts[0], false,
                FeatureGateRepresentativeDeclarationTest.class.getClassLoader());
        List<Method> methods = Arrays.stream(controller.getDeclaredMethods())
                .filter(method -> method.getName().equals(parts[1]))
                .toList();
        assertThat(methods).as(key).hasSize(1);
        return methods.getFirst();
    }

    private static Map.Entry<String, String> entry(
            String relativeClassName, String methodName, String flagKey) {
        return Map.entry("com.mannschaft.app." + relativeClassName + "#" + methodName, flagKey);
    }
}
