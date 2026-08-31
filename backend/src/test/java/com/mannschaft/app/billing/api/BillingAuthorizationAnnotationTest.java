package com.mannschaft.app.billing.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F20.1: 課金 API の {@code @PreAuthorize} 注釈の存在・内容を機械的に照合する（AC-10 / AC-17）。
 *
 * <p>{@code standaloneSetup} の契約テストはメソッドセキュリティを実行しないため、認可の担保を
 * 「注釈が public 入口に正しく置かれていること」の静的照合で補完する（03 §2「public 入口に @PreAuthorize」）。
 * 実際の 403/401 発火は SecurityConfig（{@code anyRequest().authenticated()} ＋
 * {@code /api/v1/system-admin/**}=hasRole SYSTEM_ADMIN）＋ メソッドセキュリティが担う。</p>
 */
@DisplayName("課金 API @PreAuthorize 注釈 照合（AC-10/AC-17）")
class BillingAuthorizationAnnotationTest {

    private String preAuthorize(Class<?> type, String method, Class<?>... params) throws NoSuchMethodException {
        Method m = type.getMethod(method, params);
        PreAuthorize pre = m.getAnnotation(PreAuthorize.class);
        assertThat(pre)
                .as("%s#%s に @PreAuthorize が必要（public 入口の認可・03 §2）", type.getSimpleName(), method)
                .isNotNull();
        return pre.value();
    }

    // ---- 契約 API（AC-10: scope ADMIN 固定・本人固定） ----

    @Test
    @DisplayName("AC-10: 契約作成/解約/変更の TEAM は BillingAccessGuard.canManage('TEAM')")
    void contract_team_usesBillingAccessGuard() throws Exception {
        for (String m : new String[]{"createForTeam", "cancelForTeam", "changeForTeam"}) {
            Method[] all = BillingContractController.class.getMethods();
            Method target = null;
            for (Method x : all) {
                if (x.getName().equals(m)) {
                    target = x;
                    break;
                }
            }
            assertThat(target).as("メソッド %s が存在すること", m).isNotNull();
            PreAuthorize pre = target.getAnnotation(PreAuthorize.class);
            assertThat(pre).as("%s に @PreAuthorize", m).isNotNull();
            assertThat(pre.value()).contains("@billingAccessGuard.canManage")
                    .contains("EntitlementScopeKind).TEAM").doesNotContain("isScopeAdmin");
        }
    }

    @Test
    @DisplayName("AC-10: 契約作成/解約/変更の ORG は BillingAccessGuard.canManage('ORGANIZATION')")
    void contract_org_usesBillingAccessGuard() {
        for (String m : new String[]{"createForOrg", "cancelForOrg", "changeForOrg"}) {
            Method target = null;
            for (Method x : BillingContractController.class.getMethods()) {
                if (x.getName().equals(m)) {
                    target = x;
                    break;
                }
            }
            assertThat(target).as("メソッド %s が存在すること", m).isNotNull();
            PreAuthorize pre = target.getAnnotation(PreAuthorize.class);
            assertThat(pre).as("%s に @PreAuthorize", m).isNotNull();
            assertThat(pre.value()).contains("@billingAccessGuard.canManage")
                    .contains("EntitlementScopeKind).ORGANIZATION").doesNotContain("isScopeAdmin");
        }
    }

    @Test
    @DisplayName("AC-10: /me 契約はスコープ ID をパスで受けず isAuthenticated（本人固定）")
    void contract_me_isAuthenticated() throws Exception {
        assertThat(preAuthorize(BillingContractController.class, "cancelForMe", java.util.UUID.class))
                .contains("isAuthenticated");
    }

    // ---- 権利サマリ（メンバー以上） ----

    @Test
    @DisplayName("権利サマリ TEAM/ORG は isScopeMember")
    void summary_scopeMember() throws Exception {
        assertThat(preAuthorize(BillingEntitlementSummaryController.class, "team", Long.class))
                .contains("isScopeMember").contains("'TEAM'");
        assertThat(preAuthorize(BillingEntitlementSummaryController.class, "organization", Long.class))
                .contains("isScopeMember").contains("'ORGANIZATION'");
    }

    // ---- シスアド（AC-17: SYSTEM_ADMIN） ----

    @Test
    @DisplayName("AC-17: シスアド Controller はクラス・メソッド共に hasRole('SYSTEM_ADMIN')")
    void sysadmin_hasRole() throws Exception {
        PreAuthorize classPre = SystemAdminBillingController.class.getAnnotation(PreAuthorize.class);
        assertThat(classPre).as("クラスレベル @PreAuthorize").isNotNull();
        assertThat(classPre.value()).contains("hasRole('SYSTEM_ADMIN')");

        for (String m : new String[]{"listPlans", "createPlan", "deletePlan",
                "listFeatures", "createFeature", "replacePlanFeatures", "replacePriceBands",
                "grant", "searchContracts"}) {
            Method target = null;
            for (Method x : SystemAdminBillingController.class.getMethods()) {
                if (x.getName().equals(m)) {
                    target = x;
                    break;
                }
            }
            assertThat(target).as("メソッド %s が存在すること", m).isNotNull();
            PreAuthorize pre = target.getAnnotation(PreAuthorize.class);
            assertThat(pre).as("%s に @PreAuthorize", m).isNotNull();
            assertThat(pre.value()).contains("hasRole('SYSTEM_ADMIN')");
        }
    }
}
