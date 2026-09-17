package com.mannschaft.app.billing.api;

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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

/**
 * Billing Center PR6b-1 — G群 認可（AC-123/AC-124）の受け入れテスト（試練C・red）。
 *
 * <h2>対象3エンドポイント</h2>
 * <pre>
 * POST /api/v1/me/billing/contracts/{contractId}/change-previews
 * POST /api/v1/me/billing/contracts/{contractId}/changes
 * GET  /api/v1/me/billing/contracts/{contractId}/changes/{changeId}/payment-action
 * </pre>
 *
 * <p><b>第6/7/8隊への発注書</b>: PR6a の {@code BillingCancelResumeAuthzRedIT} と同じ判定表を要求する。
 * 未認証は401／他スコープ(IDOR)は404／MEMBERロールは403／DEPUTYはpermission group直付けのみ許可。
 * 3エンドポイントすべてで個別に固定する（束ねない）。</p>
 *
 * <p><b>AC-124（PR5実在欠陥の回帰防止）</b>: 認可判定より先に冪等台帳（
 * {@code BillingDurableIdempotencyService}）へ書き込んではならない。403/404応答時に
 * {@code verifyNoInteractions} で測る。</p>
 *
 * <p>現状これら3エンドポイントは未実装（404）であるため、多くのテストは
 * 「意図した403/404ではなく実装未着手による404」という<b>別理由の赤</b>になりうる。
 * 実装後に本テストが「狙った判定表どおりの赤 → 緑」へ切り替わることを担保するため、
 * 陽性対照（正規ユーザーは成功する）を必ず対で置く。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("PR6b-1 3エンドポイント認可（G群 AC-123/124・試練C red）")
class BillingPlanChangeAuthzRedIT extends AbstractBillingPlanChangeApiIT {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final long TEAM_ID = 883_601L;
    private static final String TEAM_PERMISSION = "MANAGE_TEAM_BILLING";

    @MockitoSpyBean
    private com.mannschaft.app.billing.api.BillingDurableIdempotencyService idempotencyServiceSpy;

    @BeforeEach
    void setUp() {
        seedUpgradableContract("authz");
    }

    @AfterEach
    void tearDown() {
        cleanupScope();
    }

    // ================================================================
    // change-previews
    // ================================================================

    @Nested
    @DisplayName("AC-123: POST change-previews のロール横断")
    class ChangePreviewsAuthz {

        @Test
        @DisplayName("AC-123: 未認証のchange-previewsは401")
        void unauthenticatedIs401() throws Exception {
            mockMvc.perform(MockMvcRequestBuilders.post(String.format(PREVIEW_PATH, contractId))
                            .header("Idempotency-Key", newKey())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"toProductKind\":\"PLAN\",\"toProductKey\":\"" + TO_PLAN_KEY + "\",\"version\":0}"))
                    .andExpect(MockMvcResultMatchers.status().isUnauthorized());
        }

        @Test
        @DisplayName("AC-123: 他スコープ(他ユーザー)のcontractIdへのchange-previewsは404")
        void foreignScopeIs404() throws Exception {
            ForeignScope stranger = insertForeignScope("authz-preview-stranger");

            preview(stranger.userId(), contractId, TO_PLAN_KEY, contractVersion(), newKey())
                    .andExpect(MockMvcResultMatchers.status().isNotFound());
        }

        @Test
        @DisplayName("AC-123: 陽性対照 — 正規ユーザーのchange-previewsは201になる")
        void ownerIsOk() throws Exception {
            preview(userId, contractId, TO_PLAN_KEY, contractVersion(), newKey())
                    .andExpect(MockMvcResultMatchers.status().isCreated());
        }

        @Test
        @DisplayName("AC-123: TEAM契約に対しMEMBERロールのchange-previewsは403")
        void memberRoleIs403() throws Exception {
            RoleFixture fx = teamContractWithRole("MEMBER");

            preview(fx.actorId(), fx.contractId(), TO_PLAN_KEY, 0L, newKey())
                    .andExpect(MockMvcResultMatchers.status().isForbidden());
            cleanupScopeOf(fx.actorId());
            // PR6b-1 残務①②の番人拡張で billing_contracts.uk_bc_psp_subscription が test profile の
            // schema にも再現されるようになったため、teamContractWithRole が固定 TEAM_ID・固定
            // pspSubscriptionRef で作る TEAM 契約を、次の同クラス内テスト（deputyWithoutGroupIs403）が
            // 再利用する前に必ず消す（scope_id = TEAM_ID は actorId とは別 scope のため
            // cleanupScopeOf(fx.actorId()) だけでは消えない）。
            cleanupScopeOf(TEAM_ID);
        }

        @Test
        @DisplayName("AC-123: DEPUTY_ADMINでもpermission group未付与なら403")
        void deputyWithoutGroupIs403() throws Exception {
            RoleFixture fx = teamContractWithRole("DEPUTY_ADMIN");

            preview(fx.actorId(), fx.contractId(), TO_PLAN_KEY, 0L, newKey())
                    .andExpect(MockMvcResultMatchers.status().isForbidden());
            cleanupScopeOf(fx.actorId());
            cleanupScopeOf(TEAM_ID);
        }

        @Test
        @DisplayName("AC-124: 他スコープ(404)応答時、change-previewsは冪等台帳に一切書き込まない")
        void foreignScopeDoesNotTouchIdempotencyLedger() throws Exception {
            ForeignScope stranger = insertForeignScope("authz-preview-ledger");

            preview(stranger.userId(), contractId, TO_PLAN_KEY, contractVersion(), newKey())
                    .andExpect(MockMvcResultMatchers.status().isNotFound());

            verifyNoInteractions(idempotencyServiceSpy);
        }
    }

    // ================================================================
    // changes
    // ================================================================

    @Nested
    @DisplayName("AC-123: POST changes のロール横断")
    class ChangesAuthz {

        @Test
        @DisplayName("AC-123: 未認証のchangesは401")
        void unauthenticatedIs401() throws Exception {
            mockMvc.perform(MockMvcRequestBuilders.post(String.format(CHANGES_PATH, contractId))
                            .header("Idempotency-Key", newKey())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"previewId\":\"" + UUID.randomUUID() + "\",\"version\":0}"))
                    .andExpect(MockMvcResultMatchers.status().isUnauthorized());
        }

        @Test
        @DisplayName("AC-123: 他スコープ(他ユーザー)のcontractIdへのchangesは404")
        void foreignScopeIs404() throws Exception {
            ForeignScope stranger = insertForeignScope("authz-changes-stranger");
            UUID previewId = createPreviewId();

            change(stranger.userId(), contractId, previewId, contractVersion(), newKey())
                    .andExpect(MockMvcResultMatchers.status().isNotFound());
        }

        @Test
        @DisplayName("AC-123: 陽性対照 — 正規ユーザーのchangesは202になる")
        void ownerIsAccepted() throws Exception {
            UUID previewId = createPreviewId();

            change(userId, contractId, previewId, contractVersion(), newKey())
                    .andExpect(MockMvcResultMatchers.status().isAccepted());
        }

        @Test
        @DisplayName("AC-124: 他スコープ(404)応答時、changesは冪等台帳に一切書き込まない")
        void foreignScopeDoesNotTouchIdempotencyLedger() throws Exception {
            ForeignScope stranger = insertForeignScope("authz-changes-ledger");
            UUID previewId = createPreviewId();

            change(stranger.userId(), contractId, previewId, contractVersion(), newKey())
                    .andExpect(MockMvcResultMatchers.status().isNotFound());

            verifyNoInteractions(idempotencyServiceSpy);
        }
    }

    // ================================================================
    // payment-action
    // ================================================================

    @Nested
    @DisplayName("AC-123: GET payment-action のロール横断（AC-70とは別のロール軸で固定）")
    class PaymentActionAuthz {

        private static final String PAYMENT_ACTION_PATH =
                "/api/v1/me/billing/contracts/%s/changes/%s/payment-action";

        @Test
        @DisplayName("AC-123: 未認証のpayment-actionは401")
        void unauthenticatedIs401() throws Exception {
            mockMvc.perform(MockMvcRequestBuilders.get(
                            String.format(PAYMENT_ACTION_PATH, contractId, UUID.randomUUID())))
                    .andExpect(MockMvcResultMatchers.status().isUnauthorized());
        }

        @Test
        @DisplayName("AC-123: 他スコープ(他ユーザー)のcontractIdへのpayment-actionは404")
        void foreignScopeIs404() throws Exception {
            ForeignScope stranger = insertForeignScope("authz-payment-stranger");

            mockMvc.perform(MockMvcRequestBuilders.get(
                                    String.format(PAYMENT_ACTION_PATH, contractId, UUID.randomUUID()))
                            .with(user(String.valueOf(stranger.userId()))))
                    .andExpect(MockMvcResultMatchers.status().isNotFound());
        }

        @Test
        @DisplayName("AC-124: 他スコープ(404)応答時、payment-actionはStripe retrieveも冪等台帳も一切呼ばない")
        void foreignScopeDoesNotTouchGatewayOrLedger() throws Exception {
            ForeignScope stranger = insertForeignScope("authz-payment-ledger");

            mockMvc.perform(MockMvcRequestBuilders.get(
                                    String.format(PAYMENT_ACTION_PATH, contractId, UUID.randomUUID()))
                            .with(user(String.valueOf(stranger.userId()))))
                    .andExpect(MockMvcResultMatchers.status().isNotFound());

            verifyNoInteractions(idempotencyServiceSpy);
            org.mockito.Mockito.verify(planChangeGateway, org.mockito.Mockito.never())
                    .retrievePaymentAction(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        }
    }

    // ================================================================
    // フィクスチャ
    // ================================================================

    /** TEAM スコープの upgrade 可能契約とロール割当をひとまとめにした検体。 */
    private record RoleFixture(Long actorId, UUID contractId) {}

    private RoleFixture teamContractWithRole(String roleName) throws Exception {
        Long actor = insertUser("authz-role-" + roleName.toLowerCase() + "-" + SEQ.incrementAndGet());
        grantRole(actor, roleName, TEAM_ID);
        // 【根治】以前はここで insertBand(TO_STRIPE_PRICE_REF) をもう一本作っていたが、
        // 戻り値の teamBand はどこからも参照されず（下の契約は fromBandId を使う）完全な死コードで、
        // かつ seedUpgradableContract（@BeforeEach）が既に同じ TO_STRIPE_PRICE_REF で toBandId を
        // 作っているため、そのまま呼べば同一テスト内で billing_price_band_versions.uk_bpbv_stripe_price
        // に必ず違反する。PR6b-1 残務②の番人拡張で billing_price_band_versions 側の宣言漏れが
        // 塞がれる（test profile の schema にも UNIQUE が再現される）まで、この死コードは実害を
        // 出さず埋もれていた。
        UUID teamContractId = transactionTemplate.execute(tx -> {
            com.mannschaft.app.billing.BillingContractEntity c =
                    com.mannschaft.app.billing.BillingContractEntity.builder()
                            .scopeKind(EntitlementScopeKind.TEAM).scopeId(TEAM_ID)
                            .contractKind(com.mannschaft.app.billing.ContractKind.PLAN).planKey(FROM_PLAN_KEY)
                            .status(com.mannschaft.app.billing.ContractStatus.ACTIVE)
                            .priceJpySnapshot((int) FROM_AMOUNT)
                            .memberCountSnapshot(1)
                            .priceBandVersionId(fromBandId)
                            .billingCustomerId(customerId)
                            .pspCustomerRef("cus_pr6b1_authz_team_" + TEAM_ID)
                            .pspSubscriptionRef("sub_pr6b1_authz_team_" + TEAM_ID)
                            .currentPeriodEnd(LocalDateTime.now(clock).plusDays(20).withNano(0))
                            .contractedAt(LocalDateTime.now(clock).minusMonths(1))
                            .createdBy(userId).payerUserId(userId)
                            .version(0L)
                            .build();
            entityManager.persist(c);
            entityManager.flush();
            return c.getId();
        });
        return new RoleFixture(actor, teamContractId);
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
}
