package com.mannschaft.app.billing;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.billing.BillingPaymentGateway.CheckoutSessionInfo;
import com.mannschaft.app.billing.BillingPaymentGateway.SubscriptionSnapshot;
import com.mannschaft.app.billing.BillingPayerHandoverService.HandoverAcceptResult;
import com.mannschaft.app.billing.BillingPayerHandoverService.HandoverRequestResult;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

/**
 * 柱③-B 請求担当引継（CMP-260901-1538・PR-2）: 引継状態機械を<b>実 MySQL</b>で検証する統合テスト。
 *
 * <p>本 IT が担うのは「モックでは嘘をつける DB 状態機械の遷移」だけである。Stripe の挙動そのもの・
 * 冪等キー文字列・List 全ページ走査は {@code BillingPayerHandoverServiceTest}（UT）が実測済みであり、
 * ここでは重複させない。{@link BillingPaymentGateway} は {@code @MockitoBean} で差し替え、
 * 返り値は設計書どおりの実値（{@code trial_end}＝旧 {@code current_period_end} 等）を用いる。</p>
 *
 * <h2>検証範囲（設計書 {@code docs/architecture/billing_payer_handover_design.md}）</h2>
 * <ul>
 *   <li>AC-27: 切替TXで旧 pointer 削除と新 pointer 作成が同一 tx で確定する（{@code active_contract_pointers} 実測）</li>
 *   <li>§3.1 P0-4: 承諾直後の {@code PENDING_HANDOVER} 契約は pointer を持たない（{@code uk_acp_slot} と衝突しない）</li>
 *   <li>§4.2: 同一契約への非終端要求は1件のみ／終端化後は再要求が通る</li>
 *   <li>AC-12: 複数 ADMIN 同時承諾の直列化（実 MySQL の行ロック）</li>
 *   <li>AC-32: FAILED 経路で {@code old_cancel_scheduled_at} が NULL へ戻る</li>
 *   <li>{@code findSwitchDueHandoverIds} が期末到達行だけを返す</li>
 * </ul>
 *
 * <p><b>生成列 {@code open_old_contract_id} + UNIQUE そのもの</b>（DDL 側の物理防衛）は、test profile が
 * {@code ddl-auto: create}＋{@code flyway.enabled: false} で Entity 由来のスキーマを作るため本 IT の
 * スキーマには存在しない。DDL 実物は
 * {@code com.mannschaft.app.billing.migration.BillingPayerHandoverFoundationFlywayIT} が Flyway を実適用して
 * 検証済みであり、本 IT はその上位にあたる<b>アプリ層の排他とその終端解除</b>を実 MySQL で押さえる。</p>
 *
 * <p><b>第一次キャッシュの罠回避</b>: クラスに {@code @Transactional} を付けず、フィクスチャ投入も検証も
 * {@link TransactionTemplate} で明示的に tx を区切り、読み出し前に {@link EntityManager#clear()} して
 * DB の実値を読む。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("柱③-B 請求担当引継: 実 MySQL 状態機械")
class BillingPayerHandoverStateMachineIT extends AbstractMySqlIntegrationTest {

    private static final long TEAM_ID = 771_204L;
    private static final long TIMEOUT_SECONDS = 20L;
    private static final String OLD_SUBSCRIPTION_REF = "sub_handover_old_it";
    private static final String NEW_SUBSCRIPTION_REF = "sub_handover_new_it";
    private static final int PRICE_JPY = 4_800;

    @Autowired private BillingPayerHandoverService handoverService;
    @Autowired private BillingContractRepository billingContractRepository;
    @Autowired private BillingPayerHandoverRequestRepository handoverRequestRepository;
    @Autowired private ActiveContractPointerRepository activeContractPointerRepository;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private Clock clock;
    @PersistenceContext private EntityManager entityManager;

    @MockitoBean private BillingPaymentGateway billingPaymentGateway;

    private Long oldPayerUserId;
    private Long adminAUserId;
    private Long adminBUserId;
    private UUID oldContractId;
    private LocalDateTime oldPeriodEnd;

    @BeforeEach
    void setUp() {
        Mockito.reset(billingPaymentGateway);
        transactionTemplate.executeWithoutResult(tx -> {
            oldPayerUserId = insertUser("old-payer");
            adminAUserId = insertUser("admin-a");
            adminBUserId = insertUser("admin-b");
            grantTeamAdminRole(oldPayerUserId);
            grantTeamAdminRole(adminAUserId);
            grantTeamAdminRole(adminBUserId);

            oldPeriodEnd = LocalDateTime.now(clock).plusDays(30);
            oldContractId = insertOldContract(ContractStatus.ACTIVE, oldPeriodEnd, OLD_SUBSCRIPTION_REF);
            insertPointerFor(oldContractId);
        });
        stubGatewayForHappyPath();
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.executeWithoutResult(tx -> {
            entityManager.createNativeQuery(
                            "DELETE FROM billing_payer_handover_requests WHERE scope_id = :scopeId")
                    .setParameter("scopeId", TEAM_ID).executeUpdate();
            entityManager.createNativeQuery(
                            "DELETE FROM active_contract_pointers WHERE scope_id = :scopeId")
                    .setParameter("scopeId", TEAM_ID).executeUpdate();
            entityManager.createNativeQuery("DELETE FROM billing_contracts WHERE scope_id = :scopeId")
                    .setParameter("scopeId", TEAM_ID).executeUpdate();
            entityManager.createNativeQuery("DELETE FROM user_roles WHERE team_id = :teamId")
                    .setParameter("teamId", TEAM_ID).executeUpdate();
            entityManager.createNativeQuery("DELETE FROM users WHERE id IN (:a, :b, :c)")
                    .setParameter("a", oldPayerUserId)
                    .setParameter("b", adminAUserId)
                    .setParameter("c", adminBUserId)
                    .executeUpdate();
        });
    }

    // ============================================================
    // AC-27: entitlement 空白ゼロ（切替TXの原子性を実テーブルで実測）
    // ============================================================

    @Test
    @DisplayName("AC-27: 切替TX後のpointerは当該スロットに1件だけ残り新契約を指す（旧CANCELLED・新ACTIVE・handover COMPLETED）")
    void executeSwitch_pointerMovesToNewContractAtomically() {
        UUID handoverId = requestAndAcceptAndComplete(adminAUserId);

        handoverService.executeSwitch(handoverId);

        BillingPayerHandoverRequestEntity handover = reloadHandover(handoverId);
        UUID newContractId = handover.getNewContractId();

        assertThat(handover.getStatus()).as("引継は COMPLETED で終端").isEqualTo(PayerHandoverStatus.COMPLETED);
        assertThat(handover.getCompletedAt()).isNotNull();
        assertThat(reloadContract(oldContractId).getStatus())
                .as("旧契約は CANCELLED").isEqualTo(ContractStatus.CANCELLED);
        assertThat(reloadContract(oldContractId).getCancelledAt()).isNotNull();
        assertThat(reloadContract(newContractId).getStatus())
                .as("新契約は PENDING_HANDOVER → ACTIVE").isEqualTo(ContractStatus.ACTIVE);
        assertThat(reloadContract(newContractId).getPspSubscriptionRef())
                .as("新契約に新サブスク参照が確定").isEqualTo(NEW_SUBSCRIPTION_REF);

        assertThat(planPointerCount())
                .as("AC-27: スロットの pointer はちょうど1件（空白ゼロ・二重付与ゼロ）").isEqualTo(1L);
        assertThat(planPointerContractId())
                .as("AC-27: 残った pointer は新契約を指す").isEqualTo(newContractId);
    }

    // ============================================================
    // §3.1 P0-4: 承諾直後は新契約が pointer を持たない（uk_acp_slot と衝突しない）
    // ============================================================

    @Test
    @DisplayName("§3.1 P0-4: 承諾直後のPENDING_HANDOVER新契約はpointerを持たず旧pointerが無傷で残る")
    void acceptHandover_doesNotCreatePointerForPendingHandoverContract() {
        HandoverRequestResult requested = handoverService.requestHandover(
                EntitlementScopeKind.TEAM, TEAM_ID, oldContractId, oldPayerUserId);
        HandoverAcceptResult accepted = handoverService.acceptHandover(
                EntitlementScopeKind.TEAM, TEAM_ID, requested.handoverRequestId(), adminAUserId);

        assertThat(accepted.status()).isEqualTo(PayerHandoverStatus.ACCEPTED);
        assertThat(reloadContract(accepted.newContractId()).getStatus())
                .as("新契約は PENDING_HANDOVER で先行作成される")
                .isEqualTo(ContractStatus.PENDING_HANDOVER);

        assertThat(planPointerCount())
                .as("スロットの pointer は1件のまま（新契約ぶんを張ると uk_acp_slot 違反になる）").isEqualTo(1L);
        assertThat(planPointerContractId())
                .as("旧契約の pointer が無傷で残る").isEqualTo(oldContractId);
    }

    // ============================================================
    // §4.2: 非終端の要求は同一契約に1件・終端化すると再要求が通る
    // ============================================================

    @Test
    @DisplayName("§4.2: 非終端の要求がある間は再要求が拒否され、終端(COMPLETED)化すると再要求が通る")
    void requestHandover_blockedWhileOpenThenAllowedAfterTerminalStatus() {
        HandoverRequestResult first = handoverService.requestHandover(
                EntitlementScopeKind.TEAM, TEAM_ID, oldContractId, oldPayerUserId);

        assertThatThrownBy(() -> handoverService.requestHandover(
                EntitlementScopeKind.TEAM, TEAM_ID, oldContractId, oldPayerUserId))
                .as("非終端の要求が残っている間は HANDOVER_ALREADY_IN_PROGRESS")
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(EntitlementErrorCode.HANDOVER_ALREADY_IN_PROGRESS);

        // 状態機械を経由せず DB 側で終端へ倒す（生成列 open_old_contract_id が NULL 化される状態を作る）。
        forceStatus(first.handoverRequestId(), PayerHandoverStatus.COMPLETED);

        HandoverRequestResult second = handoverService.requestHandover(
                EntitlementScopeKind.TEAM, TEAM_ID, oldContractId, oldPayerUserId);

        assertThat(second.handoverRequestId()).isNotEqualTo(first.handoverRequestId());
        assertThat(reloadHandover(second.handoverRequestId()).getStatus())
                .as("終端化後の再要求は REQUESTED で成立する").isEqualTo(PayerHandoverStatus.REQUESTED);
        assertThat(openRequestCount(oldContractId))
                .as("非終端の要求は常に1件だけ").isEqualTo(1L);
    }

    // ============================================================
    // AC-12: 複数 ADMIN 同時承諾の直列化（実 MySQL 行ロック）
    // ============================================================

    @Test
    @DisplayName("AC-12: 2人のADMINが同時に承諾しても成功は1回だけで状態は二重遷移しない")
    void acceptHandover_concurrentAdminsSerializeToSingleSuccess() throws Exception {
        HandoverRequestResult requested = handoverService.requestHandover(
                EntitlementScopeKind.TEAM, TEAM_ID, oldContractId, oldPayerUserId);
        UUID handoverId = requested.handoverRequestId();

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<HandoverAcceptResult> a = executor.submit(() -> {
                start.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                return handoverService.acceptHandover(
                        EntitlementScopeKind.TEAM, TEAM_ID, handoverId, adminAUserId);
            });
            Future<HandoverAcceptResult> b = executor.submit(() -> {
                start.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                return handoverService.acceptHandover(
                        EntitlementScopeKind.TEAM, TEAM_ID, handoverId, adminBUserId);
            });
            start.countDown();

            List<HandoverAcceptResult> succeeded = new ArrayList<>();
            List<Throwable> failed = new ArrayList<>();
            for (Future<HandoverAcceptResult> future : List.of(a, b)) {
                try {
                    succeeded.add(future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
                } catch (java.util.concurrent.ExecutionException e) {
                    failed.add(e.getCause());
                }
            }

            assertThat(succeeded).as("AC-12: 承諾の成功はちょうど1回").hasSize(1);
            assertThat(failed).as("もう一方は業務例外で弾かれる").hasSize(1);
            assertThat(failed.get(0)).isInstanceOf(BusinessException.class);

            BillingPayerHandoverRequestEntity handover = reloadHandover(handoverId);
            assertThat(handover.getStatus()).isEqualTo(PayerHandoverStatus.ACCEPTED);
            assertThat(handover.getNewPayerUserId())
                    .as("新 payer は勝者1名に確定する")
                    .isIn(adminAUserId, adminBUserId);
            assertThat(handover.getNewContractId())
                    .as("新契約は1本だけ作られる（勝者の結果と一致）")
                    .isEqualTo(succeeded.get(0).newContractId());
            assertThat(contractCountByHandoverRequestId(handoverId))
                    .as("PENDING_HANDOVER 契約が二重に作られていない").isEqualTo(1L);
            assertThat(planPointerCount()).as("承諾段階では pointer は旧のまま1件").isEqualTo(1L);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        }
    }

    // ============================================================
    // AC-32: FAILED 経路の後始末（old_cancel_scheduled_at の NULL 復帰）
    // ============================================================

    @Test
    @DisplayName("AC-32: pending_setup_intent未解決の切替はFAILED確定しold_cancel_scheduled_atがNULLへ戻る")
    void executeSwitch_failedPathClearsOldCancelScheduledAt() {
        UUID handoverId = requestAndAcceptAndComplete(adminAUserId);

        assertThat(reloadHandover(handoverId).getOldCancelScheduledAt())
                .as("承諾確定時点では旧サブスクの期末解約予約時刻が入っている").isNotNull();

        // 旧期末到達時点でも SCA/3DS が未解決のまま、という Stripe 実物の状態にする。
        Mockito.when(billingPaymentGateway.retrieveSubscription(NEW_SUBSCRIPTION_REF))
                .thenReturn(new SubscriptionSnapshot(NEW_SUBSCRIPTION_REF, "trialing", false,
                        clock.instant(), oldPeriodEndInstant(), "seti_unresolved_it"));

        handoverService.executeSwitch(handoverId);

        BillingPayerHandoverRequestEntity handover = reloadHandover(handoverId);
        assertThat(handover.getStatus()).as("引継は FAILED で終端").isEqualTo(PayerHandoverStatus.FAILED);
        assertThat(handover.getOldCancelScheduledAt())
                .as("AC-32: 旧サブスクの差し戻しと対で NULL クリアされる").isNull();
        assertThat(reloadContract(handover.getNewContractId()).getStatus())
                .as("新契約（PENDING_HANDOVER）は無効化される").isEqualTo(ContractStatus.CANCELLED);
        assertThat(reloadContract(oldContractId).getStatus())
                .as("旧契約は無傷で継続").isEqualTo(ContractStatus.ACTIVE);
        assertThat(planPointerContractId())
                .as("pointer は旧契約のまま（利用者影響ゼロ）").isEqualTo(oldContractId);
    }

    // ============================================================
    // findSwitchDueHandoverIds
    // ============================================================

    @Test
    @DisplayName("findSwitchDueHandoverIds: SWITCHINGかつ旧期末到達済みの行だけを返す")
    void findSwitchDueHandoverIds_returnsOnlyPeriodEndReachedRows() {
        UUID dueHandoverId = requestAndAcceptAndComplete(adminAUserId);

        // 期末未到達の SWITCHING 行（別契約）を隣に置く。
        UUID futureContractId = transactionTemplate.execute(tx ->
                insertOldContract(ContractStatus.ACTIVE, LocalDateTime.now(clock).plusDays(45),
                        OLD_SUBSCRIPTION_REF + "_future"));
        UUID notDueHandoverId = transactionTemplate.execute(tx ->
                handoverRequestRepository.save(BillingPayerHandoverRequestEntity.builder()
                        .oldContractId(futureContractId)
                        .scopeKind(EntitlementScopeKind.TEAM)
                        .scopeId(TEAM_ID)
                        .oldPayerUserId(oldPayerUserId)
                        .newPayerUserId(adminBUserId)
                        .status(PayerHandoverStatus.SWITCHING)
                        .requestedAt(clock.instant())
                        .expiresAt(clock.instant().plusSeconds(86_400L))
                        .build()).getId());

        // 旧期末に到達させる（切替の発火条件は「旧 current_period_end に到達したか」のみ・AC-27/R2-P1-2）。
        transactionTemplate.executeWithoutResult(tx -> {
            entityManager.clear();
            BillingContractEntity contract = billingContractRepository
                    .findByIdAndDeletedAtIsNull(oldContractId).orElseThrow();
            contract.setCurrentPeriodEnd(LocalDateTime.now(clock).minusMinutes(1));
            billingContractRepository.save(contract);
        });

        List<UUID> due = handoverService.findSwitchDueHandoverIds(clock.instant());

        assertThat(due).as("期末到達済みの SWITCHING 行は抽出される").contains(dueHandoverId);
        assertThat(due).as("期末未到達の SWITCHING 行は抽出されない").doesNotContain(notDueHandoverId);
    }

    // ============================================================
    // フロー・フィクスチャのヘルパ
    // ============================================================

    /** 要求 → 承諾 → {@code checkout.session.completed} まで進め、{@code SWITCHING} 状態の引継 ID を返す。 */
    private UUID requestAndAcceptAndComplete(Long newPayerUserId) {
        HandoverRequestResult requested = handoverService.requestHandover(
                EntitlementScopeKind.TEAM, TEAM_ID, oldContractId, oldPayerUserId);
        handoverService.acceptHandover(
                EntitlementScopeKind.TEAM, TEAM_ID, requested.handoverRequestId(), newPayerUserId);
        handoverService.onHandoverCheckoutCompleted(requested.handoverRequestId(), NEW_SUBSCRIPTION_REF);
        assertThat(reloadHandover(requested.handoverRequestId()).getStatus())
                .as("承諾確定で SWITCHING へ進む").isEqualTo(PayerHandoverStatus.SWITCHING);
        return requested.handoverRequestId();
    }

    /**
     * Stripe ポートの返り値を設計書どおりの実値で固定する。
     *
     * <p>新サブスクは {@code trial_end}＝旧 {@code current_period_end} の {@code trialing}、
     * 旧サブスクは承諾確定時に {@code cancel_at_period_end=true} が設定済み、という正常系の実物を表す。</p>
     */
    private void stubGatewayForHappyPath() {
        Instant oldEnd = oldPeriodEndInstant();
        Mockito.when(billingPaymentGateway.hasUsablePaymentMethod(anyLong())).thenReturn(true);
        Mockito.when(billingPaymentGateway.findHandoverSubscriptionRef(anyLong(), any(UUID.class)))
                .thenReturn(Optional.empty());
        Mockito.when(billingPaymentGateway.createHandoverSubscriptionCheckout(
                        anyLong(), anyInt(), anyString(), any(UUID.class), any(UUID.class), any(UUID.class),
                        eq(oldEnd), anyString(), anyString()))
                .thenReturn(new CheckoutSessionInfo(
                        "cs_test_handover_it", "https://checkout.stripe.test/cs_test_handover_it"));
        Mockito.when(billingPaymentGateway.scheduleCancelAtPeriodEndForHandover(
                eq(OLD_SUBSCRIPTION_REF), any(UUID.class))).thenReturn(oldEnd);
        // 新サブスク: trial 中・SCA 解決済み（pending_setup_intent なし）。
        Mockito.when(billingPaymentGateway.retrieveSubscription(NEW_SUBSCRIPTION_REF))
                .thenReturn(new SubscriptionSnapshot(NEW_SUBSCRIPTION_REF, "trialing", false,
                        clock.instant(), oldEnd, null));
        // 旧サブスク: 期末解約予約済み・現サイクルは旧期末で終わる（期末境界越えなし）。
        Mockito.when(billingPaymentGateway.retrieveSubscription(OLD_SUBSCRIPTION_REF))
                .thenReturn(new SubscriptionSnapshot(OLD_SUBSCRIPTION_REF, "active", true,
                        oldEnd.minusSeconds(30L * 86_400L), oldEnd, null));
    }

    private Instant oldPeriodEndInstant() {
        return oldPeriodEnd.atZone(clock.getZone()).toInstant();
    }

    private Long insertUser(String suffix) {
        UserEntity user = UserEntity.builder()
                .email("payer-handover-" + suffix + "-" + System.nanoTime() + "@example.com")
                .lastName("引継").firstName(suffix).displayName("引継 " + suffix)
                .status(UserEntity.UserStatus.ACTIVE).locale("ja").timezone("Asia/Tokyo")
                .isSearchable(true).build();
        entityManager.persist(user);
        entityManager.flush();
        return user.getId();
    }

    private void grantTeamAdminRole(Long userId) {
        entityManager.createNativeQuery("""
                        INSERT IGNORE INTO roles
                            (name, display_name, priority, is_system, created_at, updated_at)
                        VALUES ('ADMIN', '管理者', 100, false, NOW(6), NOW(6))
                        """)
                .executeUpdate();
        entityManager.flush();
        Number roleId = (Number) entityManager
                .createNativeQuery("SELECT id FROM roles WHERE name = 'ADMIN'").getSingleResult();
        entityManager.persist(UserRoleEntity.builder()
                .userId(userId).roleId(roleId.longValue()).teamId(TEAM_ID).build());
        entityManager.flush();
    }

    private UUID insertOldContract(
            ContractStatus status, LocalDateTime currentPeriodEnd, String subscriptionRef) {
        return billingContractRepository.save(BillingContractEntity.builder()
                .scopeKind(EntitlementScopeKind.TEAM)
                .scopeId(TEAM_ID)
                .contractKind(ContractKind.PLAN)
                .planKey("FULL")
                .status(status)
                .priceJpySnapshot(PRICE_JPY)
                .contractedAt(LocalDateTime.now(clock).minusDays(30))
                .currentPeriodEnd(currentPeriodEnd)
                .createdBy(oldPayerUserId)
                .payerUserId(oldPayerUserId)
                .pspSubscriptionRef(subscriptionRef)
                .build()).getId();
    }

    private void insertPointerFor(UUID contractId) {
        activeContractPointerRepository.saveAndFlush(ActiveContractPointerEntity.builder()
                .scopeKind(EntitlementScopeKind.TEAM)
                .scopeId(TEAM_ID)
                .contractKind(ContractKind.PLAN)
                .addonFeatureKey("")
                .contractId(contractId)
                .build());
    }

    // ============================================================
    // DB 実値の読み出し（第一次キャッシュを避けて必ず DB から読む）
    // ============================================================

    private BillingPayerHandoverRequestEntity reloadHandover(UUID handoverRequestId) {
        return transactionTemplate.execute(tx -> {
            entityManager.clear();
            return handoverRequestRepository.findById(handoverRequestId).orElseThrow();
        });
    }

    private BillingContractEntity reloadContract(UUID contractId) {
        return transactionTemplate.execute(tx -> {
            entityManager.clear();
            return billingContractRepository.findByIdAndDeletedAtIsNull(contractId).orElseThrow();
        });
    }

    private long planPointerCount() {
        return transactionTemplate.execute(tx -> {
            entityManager.clear();
            Number count = (Number) entityManager.createNativeQuery("""
                            SELECT COUNT(*) FROM active_contract_pointers
                             WHERE scope_kind = 'TEAM' AND scope_id = :scopeId
                               AND contract_kind = 'PLAN' AND addon_feature_key = ''
                            """)
                    .setParameter("scopeId", TEAM_ID).getSingleResult();
            return count.longValue();
        });
    }

    private UUID planPointerContractId() {
        return transactionTemplate.execute(tx -> {
            entityManager.clear();
            return activeContractPointerRepository
                    .findByScopeKindAndScopeIdAndContractKindAndAddonFeatureKey(
                            EntitlementScopeKind.TEAM, TEAM_ID, ContractKind.PLAN, "")
                    .orElseThrow().getContractId();
        });
    }

    private long openRequestCount(UUID contractId) {
        return transactionTemplate.execute(tx -> {
            entityManager.clear();
            return (long) handoverRequestRepository.findByOldContractIdAndStatusNotIn(
                    contractId, BillingPayerHandoverTxService.TERMINAL_STATUSES).size();
        });
    }

    private long contractCountByHandoverRequestId(UUID handoverRequestId) {
        return transactionTemplate.execute(tx -> {
            entityManager.clear();
            Number count = (Number) entityManager.createNativeQuery("""
                            SELECT COUNT(*) FROM billing_contracts
                             WHERE handover_request_id = UNHEX(:hex)
                            """)
                    .setParameter("hex", hex(handoverRequestId)).getSingleResult();
            return count.longValue();
        });
    }

    /** 状態機械を経由せず DB 側で status を書き換える（終端化による生成列 NULL 化の再現）。 */
    private void forceStatus(UUID handoverRequestId, PayerHandoverStatus status) {
        transactionTemplate.executeWithoutResult(tx ->
                entityManager.createNativeQuery("""
                                UPDATE billing_payer_handover_requests
                                   SET status = :status, updated_at = NOW(6)
                                 WHERE id = UNHEX(:hex)
                                """)
                        .setParameter("status", status.name())
                        .setParameter("hex", hex(handoverRequestId))
                        .executeUpdate());
    }

    private static String hex(UUID id) {
        return id.toString().replace("-", "").toUpperCase(java.util.Locale.ROOT);
    }
}
