package com.mannschaft.app.common.architecture;

import com.mannschaft.app.common.featuregate.AlwaysReachable;
import com.mannschaft.app.common.featuregate.AlwaysReachableCategory;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static com.mannschaft.app.common.featuregate.AlwaysReachableCategory.CORE;
import static com.mannschaft.app.common.featuregate.AlwaysReachableCategory.GATE_CONTROL_PLANE;
import static com.mannschaft.app.common.featuregate.AlwaysReachableCategory.PLATFORM_INFRA;
import static com.mannschaft.app.common.featuregate.AlwaysReachableCategory.PUBLIC_LIFELINE;
import static org.assertj.core.api.Assertions.assertThat;

class AlwaysReachableDeclarationTest {

    private static final Map<String, AlwaysReachableCategory> EXPECTED = Map.ofEntries(
            entry("admin.controller.SystemAdminFeatureFlagController", "getAllFlags", GATE_CONTROL_PLANE),
            entry("admin.controller.SystemAdminFeatureFlagController", "updateFlag", GATE_CONTROL_PLANE),
            entry("admin.controller.FeatureFlagController", "getPublicFlags", GATE_CONTROL_PLANE),
            entry("admin.controller.SystemAdminGdprPurgeController", "listPurgeStatus", GATE_CONTROL_PLANE),
            entry("admin.controller.SystemAdminGdprPurgeController", "getSummary", GATE_CONTROL_PLANE),
            entry("admin.controller.SystemAdminGdprPurgeController", "getUserDetail", GATE_CONTROL_PLANE),
            entry("admin.controller.SystemAdminGdprPurgeController", "exportCsv", GATE_CONTROL_PLANE),
            entry("admin.controller.SystemAdminGdprPurgeController", "retryDomainPurge", GATE_CONTROL_PLANE),
            entry("gdpr.controller.GdprController", "requestExport", PUBLIC_LIFELINE),
            entry("gdpr.controller.GdprController", "getExportStatus", PUBLIC_LIFELINE),
            entry("gdpr.controller.GdprController", "getDownloadUrl", PUBLIC_LIFELINE),
            entry("gdpr.controller.GdprController", "getDeletionPreview", PUBLIC_LIFELINE),
            entry("advertising.campaign.controller.AdUnsubscribePublicController", "unsubscribe", PUBLIC_LIFELINE),
            entry("advertising.campaign.controller.AdUnsubscribePublicController", "unsubscribePost", PUBLIC_LIFELINE),
            entry("payment.controller.StripeWebhookController", "handleWebhook", PLATFORM_INFRA),
            entry("payment.controller.StripeWebhookController", "handleConnectWebhook", PLATFORM_INFRA),
            entry("advertising.controller.StripeAdInvoiceWebhookController", "handleInvoiceWebhook", PLATFORM_INFRA),
            entry("line.controller.LineWebhookController", "receiveWebhook", PLATFORM_INFRA),
            entry("schedule.controller.GoogleCalendarWebhookController", "receiveWebhook", PLATFORM_INFRA),
            entry("webhook.controller.IncomingWebhookController", "processIncoming", PLATFORM_INFRA),
            entry("auth.controller.AuthOAuthCallbackController", "handleCallback", PLATFORM_INFRA),
            entry("auth.controller.AuthOAuthController", "getGoogleAuthUrl", CORE),
            entry("auth.controller.AuthOAuthController", "loginWithOAuth", CORE),
            entry("auth.controller.AuthOAuthController", "confirmOAuthLinkage", CORE),
            entry("payment.escrow.controller.EscrowRefundController", "refund", CORE),
            entry("payment.controller.TeamPaymentController", "cancelPayment", CORE),
            entry("payment.controller.TeamPaymentController", "refundPayment", CORE),
            entry("payment.controller.OrganizationPaymentController", "cancelPayment", CORE),
            entry("payment.controller.OrganizationPaymentController", "refundPayment", CORE),
            entry("payment.controller.MembershipSubscriptionController", "cancel", CORE),
            entry("billing.api.BillingContractController", "cancelForMe", CORE),
            entry("billing.api.BillingContractController", "cancelForTeam", CORE),
            entry("billing.api.BillingContractController", "cancelForOrg", CORE),
            entry("auth.controller.AuditLogAdminController", "getAdminLogs", CORE),
            entry("auth.controller.AuditLogAdminController", "getMyLogs", CORE),
            entry("auth.controller.AuditLogScopeController", "getTeamAuditLogs", CORE),
            entry("auth.controller.AuditLogScopeController", "getOrganizationAuditLogs", CORE),
            entry("auth.controller.UserController", "requestWithdrawal", CORE),
            entry("auth.controller.UserController", "cancelWithdrawal", CORE),
            entry("village.controller.VillageInvitationController", "issue", CORE),
            entry("village.controller.VillageInvitationController", "list", CORE),
            entry("village.controller.VillageInvitationController", "revoke", CORE),
            entry("village.controller.VillageInvitationController", "accept", CORE),
            entry("billing.api.BillingReturnController", "checkoutSuccess", PLATFORM_INFRA),
            entry("billing.api.BillingReturnController", "checkoutCancel", PLATFORM_INFRA),
            entry("billing.api.BillingReturnController", "portalReturn", PLATFORM_INFRA),
            entry("billing.api.BillingReturnController", "paymentActionReturn", PLATFORM_INFRA));

    @Test
    @org.junit.jupiter.api.DisplayName("常時到達47入口は指定Controllerメソッドへ理由・区分付きで宣言される")
    void criticalEntryPointsDeclareTheReviewedAlwaysReachableCategory() throws ClassNotFoundException {
        assertThat(EXPECTED).hasSize(47);
        for (Map.Entry<String, AlwaysReachableCategory> expected : EXPECTED.entrySet()) {
            String[] key = expected.getKey().split("#", 2);
            Class<?> controller = Class.forName(key[0], false, getClass().getClassLoader());
            List<Method> methods = Arrays.stream(controller.getDeclaredMethods())
                    .filter(method -> method.getName().equals(key[1]))
                    .toList();
            assertThat(methods).as(expected.getKey()).hasSize(1);
            AlwaysReachable declaration = methods.getFirst().getAnnotation(AlwaysReachable.class);
            assertThat(declaration).as(expected.getKey()).isNotNull();
            assertThat(declaration.category()).as(expected.getKey()).isEqualTo(expected.getValue());
            assertThat(declaration.reason()).as(expected.getKey()).isNotBlank();
        }
    }

    private static Map.Entry<String, AlwaysReachableCategory> entry(
            String relativeClassName, String methodName, AlwaysReachableCategory category) {
        return Map.entry("com.mannschaft.app." + relativeClassName + "#" + methodName, category);
    }
}
