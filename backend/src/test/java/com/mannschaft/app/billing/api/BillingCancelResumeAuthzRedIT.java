package com.mannschaft.app.billing.api;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.billing.ActiveBillingContractOperationPointerRepository;
import com.mannschaft.app.billing.api.BillingDurableIdempotencyService;
import com.mannschaft.app.billing.BillingContractEntity;
import com.mannschaft.app.billing.BillingContractOperationRepository;
import com.mannschaft.app.billing.ContractKind;
import com.mannschaft.app.billing.ContractStatus;
import com.mannschaft.app.billing.EntitlementScopeKind;
import com.mannschaft.app.role.entity.PermissionEntity;
import com.mannschaft.app.role.entity.PermissionGroupEntity;
import com.mannschaft.app.role.entity.PermissionGroupPermissionEntity;
import com.mannschaft.app.role.entity.RoleEntity;
import com.mannschaft.app.role.entity.RolePermissionEntity;
import com.mannschaft.app.role.entity.UserPermissionGroupEntity;
import com.mannschaft.app.role.entity.UserRoleEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Billing Center PR6a — D群 認可（AC-50〜AC-56）の受け入れテスト（試練C・red）。
 *
 * <p><b>AC-51（最重要）</b>: 他スコープの contractId への cancel/resume は <b>404</b> で畳む。
 * PR5 で「pointer 競合と誤認して 409 を返し、存在オラクルになっていた」前科がある
 * （台帳 2026-09-11 軍議・敵対的家老指摘）。本テストは 403/409 ではなく <b>404</b> をピンポイントで要求する。</p>
 *
 * <p><b>空虚な緑への備え</b>: 401/403/404 それぞれについて、同じ契約への正規要求が 200 になる
 * 陽性対照（{@link #AC50_陽性対照_正規ユーザーは200になる}）を対で置く。認可を一切実装しない
 * 「全部 404 を返す」ような雑な実装では陽性対照が緑にならず見抜ける。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("PR6a 解約/撤回 認可（D群 AC-50〜56・試練C red）")
class BillingCancelResumeAuthzRedIT extends AbstractBillingCancelResumeApiIT {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final long TEAM_ID = 883_501L;
    private static final String TEAM_PERMISSION = "MANAGE_TEAM_BILLING";
    private static final String SUB_REF = "sub_pr6a_authz";

    private Long ownerId;
    private Long strangerId;
    private LocalDateTime periodEnd;

    @org.springframework.beans.factory.annotation.Autowired
    private BillingContractOperationRepository operationRepository;
    @org.springframework.beans.factory.annotation.Autowired
    private ActiveBillingContractOperationPointerRepository pointerRepository;

    @BeforeEach
    void setUp() {
        ownerId = insertUser("authz-owner");
        strangerId = insertUser("authz-stranger");
        periodEnd = LocalDateTime.now(clock).plusDays(20).withNano(0);
        stubStripeSubscription(SUB_REF, false, periodEnd);
    }

    @AfterEach
    void tearDown() {
        cleanupScope(ownerId);
        cleanupScope(strangerId);
    }

    // ═════════ AC-50: 未認証は401 ═════════

    @Test
    @DisplayName("AC-50: 未認証のcancelは401でStripeを呼ばずDBを変更しない")
    void AC50_未認証は401() throws Exception {
        UUID contractId = insertContract(ownerId, ContractStatus.ACTIVE, PRICE_JPY, SUB_REF, periodEnd, null);

        mockMvc.perform(MockMvcRequestBuilders.post(String.format(CANCEL_PATH, contractId))
                        .header("Idempotency-Key", newKey())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"version\":0}"))
                .andExpect(status().isUnauthorized());

        assertThat(reloadContract(contractId).getCancelledAt()).isNull();
        assertThat(stripeCalls("cancelAtPeriodEnd")).isZero();
    }

    @Test
    @DisplayName("AC-50: 未認証の撤回(DELETE)も401")
    void AC50_未認証の撤回も401() throws Exception {
        UUID contractId = insertContract(ownerId, ContractStatus.ACTIVE, PRICE_JPY, SUB_REF, periodEnd,
                LocalDateTime.now(clock).minusDays(1));

        mockMvc.perform(MockMvcRequestBuilders.delete(String.format(CANCEL_PATH, contractId))
                        .header("Idempotency-Key", newKey())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"version\":0}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("AC-50: 陽性対照 — 正規ユーザーは200になる（401固定の雑実装ではないことの証明）")
    void AC50_陽性対照_正規ユーザーは200になる() throws Exception {
        UUID contractId = insertContract(ownerId, ContractStatus.ACTIVE, PRICE_JPY, SUB_REF, periodEnd, null);

        cancel(ownerId, contractId, 0L, newKey()).andExpect(status().isOk());
    }

    // ═════════ AC-51: 他スコープ(他人)のcontractIdは404（存在オラクルを残さない） ═════════

    @Test
    @DisplayName("AC-51: 無関係な他ユーザーのcontractIdへのcancelは404であり403/409ではない")
    void AC51_他人の契約へのcancelは404() throws Exception {
        UUID contractId = insertContract(ownerId, ContractStatus.ACTIVE, PRICE_JPY, SUB_REF, periodEnd, null);

        cancel(strangerId, contractId, 0L, newKey())
                .andExpect(status().isNotFound());

        // DB・Stripeとも無変化（認可に落ちた要求はその先の状態を一切変えない）
        assertThat(reloadContract(contractId).getCancelledAt()).isNull();
        assertThat(stripeCalls("cancelAtPeriodEnd")).isZero();
    }

    @Test
    @DisplayName("AC-51: 無関係な他ユーザーのcontractIdへの撤回も404")
    void AC51_他人の契約への撤回も404() throws Exception {
        UUID contractId = insertContract(ownerId, ContractStatus.ACTIVE, PRICE_JPY, SUB_REF, periodEnd,
                LocalDateTime.now(clock).minusDays(1));

        resume(strangerId, contractId, 0L, newKey())
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("AC-51: 存在しないcontractIdへのcancelも同じ404（存在有無を区別させない）")
    void AC51_存在しないcontractIdも同じ404() throws Exception {
        UUID randomId = UUID.randomUUID();

        cancel(strangerId, randomId, 0L, newKey())
                .andExpect(status().isNotFound());
    }

    // ═════════ AC-52: MEMBERロールは403 ═════════

    @Test
    @DisplayName("AC-52: TEAM契約に対しMEMBERロールのcancelは403")
    void AC52_MEMBERロールは403() throws Exception {
        Long member = insertUser("authz-member");
        grantRole(member, "MEMBER", TEAM_ID);
        UUID contractId = insertTeamContract(TEAM_ID, ContractStatus.ACTIVE, PRICE_JPY, SUB_REF, periodEnd, null);

        cancel(member, contractId, 0L, newKey())
                .andExpect(status().isForbidden());

        assertThat(reloadContract(contractId).getCancelledAt()).isNull();
        cleanupScope(member);
    }

    @Test
    @DisplayName("AC-52: 陽性対照 — 同じTEAMのADMINは200になる")
    void AC52_陽性対照_ADMINは200になる() throws Exception {
        Long admin = insertUser("authz-admin");
        grantRole(admin, "ADMIN", TEAM_ID);
        UUID contractId = insertTeamContract(TEAM_ID, ContractStatus.ACTIVE, PRICE_JPY, SUB_REF, periodEnd, null);

        cancel(admin, contractId, 0L, newKey()).andExpect(status().isOk());
        cleanupScope(admin);
    }

    // ═════════ AC-53: DEPUTYはpermission group直付けのみ許可 ═════════

    @Test
    @DisplayName("AC-53: DEPUTY_ADMINでもpermission group未付与のcancelは403")
    void AC53_DEPUTYでpermissionGroup未付与は403() throws Exception {
        Long deputy = insertUser("authz-deputy-none");
        grantRole(deputy, "DEPUTY_ADMIN", TEAM_ID);
        UUID contractId = insertTeamContract(TEAM_ID, ContractStatus.ACTIVE, PRICE_JPY, SUB_REF, periodEnd, null);

        cancel(deputy, contractId, 0L, newKey())
                .andExpect(status().isForbidden());
        cleanupScope(deputy);
    }

    @Test
    @DisplayName("AC-53: DEPUTY_ADMINのrole_permissionsだけの付与ではcancelできない（cache/role_permissions経由は不可）")
    void AC53_DEPUTYのrole_permissionsだけでは403() throws Exception {
        Long deputy = insertUser("authz-deputy-roleperm");
        Long role = role("DEPUTY_ADMIN");
        Long permission = permission(TEAM_PERMISSION, PermissionEntity.Scope.TEAM);
        transactionTemplate.executeWithoutResult(tx -> {
            entityManager.persist(UserRoleEntity.builder().userId(deputy).roleId(role).teamId(TEAM_ID).build());
            entityManager.persist(RolePermissionEntity.builder().roleId(role).permissionId(permission)
                    .isDefault(true).build());
            entityManager.flush();
        });
        UUID contractId = insertTeamContract(TEAM_ID, ContractStatus.ACTIVE, PRICE_JPY, SUB_REF, periodEnd, null);

        cancel(deputy, contractId, 0L, newKey())
                .andExpect(status().isForbidden());
        cleanupScope(deputy);
    }

    @Test
    @DisplayName("AC-53: 陽性対照 — DEPUTY_ADMINへpermission groupを直付けすればcancelできる")
    void AC53_陽性対照_DEPUTYへ直付けは200() throws Exception {
        Long deputy = insertUser("authz-deputy-direct");
        grantRole(deputy, "DEPUTY_ADMIN", TEAM_ID);
        Long permission = permission(TEAM_PERMISSION, PermissionEntity.Scope.TEAM);
        Long group = group(TEAM_ID);
        transactionTemplate.executeWithoutResult(tx -> {
            entityManager.persist(PermissionGroupPermissionEntity.builder()
                    .groupId(group).permissionId(permission).build());
            entityManager.persist(UserPermissionGroupEntity.builder().userId(deputy).groupId(group).build());
            entityManager.flush();
        });
        UUID contractId = insertTeamContract(TEAM_ID, ContractStatus.ACTIVE, PRICE_JPY, SUB_REF, periodEnd, null);

        cancel(deputy, contractId, 0L, newKey()).andExpect(status().isOk());
        cleanupScope(deputy);
    }

    // ═════════ AC-54: 認可判定が冪等台帳への書込より先（PR5実在欠陥の回帰防止） ═════════

    /**
     * <p>PR5 の {@code BillingCustomerPortalController} で「認可より先に冪等台帳へ書けば、
     * 権限の無い scope でも hash 不一致の 409 が返り得て存在オラクルになる」という実在欠陥があった
     * （同クラス javadoc 41-46行）。第6/7隊が実装する新エンドポイントで同じ倒錯が起きないことを、
     * {@code BillingDurableIdempotencyService} を spy に差し替え、403/404 応答のとき
     * {@code begin/complete/fail} のいずれも一切呼ばれていないことで測る。</p>
     */
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private BillingDurableIdempotencyService idempotencyServiceSpy;

    @Test
    @DisplayName("AC-54: 他人の契約への403応答時、冪等台帳(begin/complete/fail)には一切書き込まない")
    void AC54_認可失敗時は冪等台帳に触れない() throws Exception {
        Long member = insertUser("authz-order-member");
        grantRole(member, "MEMBER", TEAM_ID);
        UUID contractId = insertTeamContract(TEAM_ID, ContractStatus.ACTIVE, PRICE_JPY, SUB_REF, periodEnd, null);

        cancel(member, contractId, 0L, newKey()).andExpect(status().isForbidden());

        org.mockito.Mockito.verifyNoInteractions(idempotencyServiceSpy);
        cleanupScope(member);
    }

    @Test
    @DisplayName("AC-54: 404(IDOR)応答時も冪等台帳には一切書き込まない")
    void AC54_IDOR404時も冪等台帳に触れない() throws Exception {
        UUID contractId = insertContract(ownerId, ContractStatus.ACTIVE, PRICE_JPY, SUB_REF, periodEnd, null);

        cancel(strangerId, contractId, 0L, newKey()).andExpect(status().isNotFound());

        org.mockito.Mockito.verifyNoInteractions(idempotencyServiceSpy);
    }

    // ═════════ AC-55/AC-56: レート制限（10回/時・cancelと撤回は同一バケット） ═════════

    @Test
    @DisplayName("AC-55/56: cancelと撤回の往復を繰り返すと10回目を超えたところで429になる（同一scopeバケット共有）")
    void AC55_56_cancelと撤回往復で10回超えると429() throws Exception {
        UUID contractId = insertContract(ownerId, ContractStatus.ACTIVE, PRICE_JPY, SUB_REF, periodEnd, null);
        int successCount = 0;
        boolean sawTooManyRequests = false;

        // cancel → resume を交互に5往復（=10回）まではバケット内で成功し得る。11回目は429であることを狙う。
        for (int i = 0; i < 6 && !sawTooManyRequests; i++) {
            var cancelResult = cancel(ownerId, contractId, successCount, newKey());
            int cancelStatus = cancelResult.andReturn().getResponse().getStatus();
            if (cancelStatus == 429) {
                sawTooManyRequests = true;
                break;
            }
            successCount++;

            var resumeResult = resume(ownerId, contractId, successCount, newKey());
            int resumeStatus = resumeResult.andReturn().getResponse().getStatus();
            if (resumeStatus == 429) {
                sawTooManyRequests = true;
                break;
            }
            successCount++;
        }

        assertThat(sawTooManyRequests)
                .as("scope あたり10回/時のバケットを cancel/resume が共有し、往復で上限に達する（AC-56）")
                .isTrue();
    }

    // ═════════ フィクスチャ ═════════

    /** TEAM スコープの有償契約を作る（scope 認可経路の検証用）。 */
    private UUID insertTeamContract(long teamId, ContractStatus status, Integer priceJpy, String subRef,
                                     LocalDateTime periodEnd, LocalDateTime cancelledAt) {
        return transactionTemplate.execute(tx -> {
            BillingContractEntity c = BillingContractEntity.builder()
                    .scopeKind(EntitlementScopeKind.TEAM).scopeId(teamId)
                    .contractKind(ContractKind.PLAN).planKey(PLAN_KEY)
                    .status(status)
                    .priceJpySnapshot(priceJpy)
                    .pspSubscriptionRef(subRef)
                    .pspCustomerRef(subRef == null ? null : "cus_pr6a_team_" + teamId)
                    .currentPeriodEnd(periodEnd)
                    .cancelledAt(cancelledAt)
                    .contractedAt(LocalDateTime.now(clock).minusMonths(1))
                    .createdBy(ownerId).payerUserId(ownerId)
                    .version(0L)
                    .build();
            entityManager.persist(c);
            entityManager.flush();
            return c.getId();
        });
    }

    private void grantRole(Long userId, String roleName, long teamId) {
        transactionTemplate.executeWithoutResult(tx -> {
            entityManager.persist(UserRoleEntity.builder().userId(userId).roleId(role(roleName)).teamId(teamId).build());
            entityManager.flush();
        });
    }

    private Long role(String name) {
        var ids = entityManager.createNativeQuery("SELECT id FROM roles WHERE name = :name")
                .setParameter("name", name).getResultList();
        if (!ids.isEmpty()) {
            return ((Number) ids.get(0)).longValue();
        }
        RoleEntity entity = RoleEntity.builder().name(name).displayName(name)
                .priority(1).isSystem(true).build();
        return transactionTemplate.execute(tx -> {
            entityManager.persist(entity);
            entityManager.flush();
            return entity.getId();
        });
    }

    private Long permission(String name, PermissionEntity.Scope scope) {
        var ids = entityManager.createNativeQuery("SELECT id FROM permissions WHERE name = :name")
                .setParameter("name", name).getResultList();
        if (!ids.isEmpty()) {
            return ((Number) ids.get(0)).longValue();
        }
        PermissionEntity entity = PermissionEntity.builder().name(name).displayName(name).scope(scope).build();
        return transactionTemplate.execute(tx -> {
            entityManager.persist(entity);
            entityManager.flush();
            return entity.getId();
        });
    }

    private Long group(long teamId) {
        return transactionTemplate.execute(tx -> {
            PermissionGroupEntity entity = PermissionGroupEntity.builder().teamId(teamId)
                    .targetRole(PermissionGroupEntity.TargetRole.DEPUTY_ADMIN)
                    .name("pr6a-authz-" + SEQ.incrementAndGet()).build();
            entityManager.persist(entity);
            entityManager.flush();
            return entity.getId();
        });
    }
}
