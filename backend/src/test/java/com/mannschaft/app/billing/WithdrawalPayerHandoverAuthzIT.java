package com.mannschaft.app.billing;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.billing.BillingPayerHandoverService.HandoverRequestResult;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * 柱③-B（CMP-260901-1538・PR-3）: <b>退会済み payer</b> からの引継要求作成を実 MySQL で検証する
 * 統合テスト（Codex 検分1巡目 P0・P1-3 の是正の受け入れ）。
 *
 * <h2>なぜ UT では捕まらなかったのか</h2>
 * <p>{@code WithdrawalStripeHandlerTest} は {@link BillingPayerHandoverService} をモックしていたため、
 * 「退会者は認可 SQL（{@code users.deleted_at IS NULL AND status='ACTIVE'}）に必ず落ちる」という
 * <b>実害を完全に隠していた</b>。本 IT は実 DB・実 Spring プロキシで
 * {@code UserService#requestWithdrawal} 相当の状態（{@code deleted_at} が commit 済み）を作ってから
 * 引継要求の作成を試みる。</p>
 *
 * <h2>検証範囲</h2>
 * <ul>
 *   <li>対話 API 経路（{@code requestHandover}）は退会済み payer では<b>必ず失敗する</b>
 *       ——是正前の欠陥がここに実在したことの明示（赤くなる理由が「認可」であることの確認）</li>
 *   <li>退会経路（{@code requestHandoverForWithdrawal}）は同じ状態で引継要求を実際に作成する</li>
 *   <li>退会経路でも<b>他人が payer の契約は動かせない</b>（任意ユーザーを渡した越境の不成立）</li>
 *   <li>退会取消で {@code REQUESTED} が {@code FAILED} へ終端化する（設計書 §4.2 遷移表）</li>
 * </ul>
 *
 * <p>金型は {@link BillingPayerHandoverStateMachineIT}。クラスに {@code @Transactional} を付けず、
 * {@link TransactionTemplate} で tx を明示的に区切り、読み出し前に {@link EntityManager#clear()} して
 * 第一次キャッシュではなく DB の実値を読む。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("柱③-B 退会済み payer からの引継要求（実 MySQL）")
class WithdrawalPayerHandoverAuthzIT extends AbstractMySqlIntegrationTest {

    private static final long TEAM_ID = 771_303L;
    private static final String OLD_SUBSCRIPTION_REF = "sub_withdrawal_handover_it";
    private static final int PRICE_JPY = 3_800;

    @Autowired private BillingPayerHandoverService handoverService;
    @Autowired private BillingContractRepository billingContractRepository;
    @Autowired private BillingPayerHandoverRequestRepository handoverRequestRepository;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private Clock clock;
    @PersistenceContext private EntityManager entityManager;

    private Long withdrawnPayerUserId;
    private Long otherAdminUserId;
    private UUID contractId;

    @BeforeEach
    void setUp() {
        transactionTemplate.executeWithoutResult(tx -> {
            withdrawnPayerUserId = insertUser("withdrawn-payer");
            otherAdminUserId = insertUser("other-admin");
            grantTeamAdminRole(withdrawnPayerUserId);
            grantTeamAdminRole(otherAdminUserId);

            LocalDateTime periodEnd = LocalDateTime.now(clock).plusDays(30).truncatedTo(ChronoUnit.SECONDS);
            contractId = billingContractRepository.save(BillingContractEntity.builder()
                    .scopeKind(EntitlementScopeKind.TEAM)
                    .scopeId(TEAM_ID)
                    .contractKind(ContractKind.PLAN)
                    .planKey("FULL")
                    .status(ContractStatus.ACTIVE)
                    .priceJpySnapshot(PRICE_JPY)
                    .contractedAt(LocalDateTime.now(clock).minusDays(30))
                    .currentPeriodEnd(periodEnd)
                    .createdBy(withdrawnPayerUserId)
                    .payerUserId(withdrawnPayerUserId)
                    .pspSubscriptionRef(OLD_SUBSCRIPTION_REF)
                    .build()).getId();

            // UserService#requestWithdrawal と同じ状態を作る（UserEntity#requestDeletion は deleted_at を立てる）。
            // 本番ではこれが【先に commit され】、その後 AFTER_COMMIT で決済連携が走る。
            entityManager.createNativeQuery("UPDATE users SET deleted_at = NOW(6) WHERE id = :id")
                    .setParameter("id", withdrawnPayerUserId).executeUpdate();
            entityManager.flush();
            entityManager.clear();
        });
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.executeWithoutResult(tx -> {
            entityManager.createNativeQuery(
                            "DELETE FROM billing_payer_handover_requests WHERE scope_id = :scopeId")
                    .setParameter("scopeId", TEAM_ID).executeUpdate();
            entityManager.createNativeQuery("DELETE FROM billing_contracts WHERE scope_id = :scopeId")
                    .setParameter("scopeId", TEAM_ID).executeUpdate();
            entityManager.createNativeQuery("DELETE FROM user_roles WHERE team_id = :teamId")
                    .setParameter("teamId", TEAM_ID).executeUpdate();
            entityManager.createNativeQuery("DELETE FROM users WHERE id IN (:a, :b)")
                    .setParameter("a", withdrawnPayerUserId)
                    .setParameter("b", otherAdminUserId).executeUpdate();
        });
    }

    @Test
    @DisplayName("是正前の欠陥: 対話API経路は退会済み payer では【認可で】落ち、引継要求が1件も作られない")
    void 欠陥再現_対話API経路は退会済みpayerでは失敗する() {
        // 例外型だけでは「別の業務エラーでたまたま赤い」を排除できない。赤くなった理由が
        // 狙った機構（requireCanManage の認可 SQL が deleted_at IS NULL を要求すること）である
        // ことを、エラーコードまで見て確定させる（Codex 検分2巡目 P2）。
        BusinessException thrown = catchThrowableOfType(
                () -> handoverService.requestHandover(
                        EntitlementScopeKind.TEAM, TEAM_ID, contractId, withdrawnPayerUserId),
                BusinessException.class);

        assertThat(thrown).isNotNull();
        // requireCanManage は allowed=false のとき CommonErrorCode.COMMON_002 を投げる。
        // 退会済みユーザーは billing_access の認可 SQL（users.deleted_at IS NULL AND status='ACTIVE'）に
        // 合致しないため、必ずこの経路で落ちる。
        assertThat(thrown.getErrorCode()).isEqualTo(CommonErrorCode.COMMON_002);
        assertThat(openRequests()).isEmpty();
    }

    @Test
    @DisplayName("対照: 同じ契約・同じ操作者でも、退会していなければ対話API経路は成功する（赤の原因の切り分け）")
    void 対照_退会していなければ対話API経路は成功する() {
        // 直前のテストの赤が「退会（deleted_at）」に起因することを、唯一の差分を戻して確かめる。
        transactionTemplate.executeWithoutResult(tx -> {
            entityManager.createNativeQuery("UPDATE users SET deleted_at = NULL WHERE id = :id")
                    .setParameter("id", withdrawnPayerUserId).executeUpdate();
        });

        assertThatCode(() -> handoverService.requestHandover(
                EntitlementScopeKind.TEAM, TEAM_ID, contractId, withdrawnPayerUserId))
                .doesNotThrowAnyException();
        assertThat(openRequests()).hasSize(1);
    }

    @Test
    @DisplayName("退会取消済みなら退会経路でも引継要求を作らない（イベント逆順・検分2巡目 P1-1）")
    void 逆順_退会取消後に届いた退会イベントは引継要求を作らない() {
        // 退会取消が先に確定した状態（deleted_at が NULL）。ここへ古い退会イベントが届く。
        transactionTemplate.executeWithoutResult(tx -> {
            entityManager.createNativeQuery("UPDATE users SET deleted_at = NULL WHERE id = :id")
                    .setParameter("id", withdrawnPayerUserId).executeUpdate();
        });

        BusinessException thrown = catchThrowableOfType(
                () -> handoverService.requestHandoverForWithdrawal(contractId, withdrawnPayerUserId),
                BusinessException.class);

        assertThat(thrown).isNotNull();
        assertThat(thrown.getErrorCode()).isEqualTo(EntitlementErrorCode.HANDOVER_CONTRACT_NOT_ELIGIBLE);
        assertThat(openRequests()).isEmpty();
    }

    @Test
    @DisplayName("是正: 退会経路は deleted_at 済みでも引継要求を実際に作成する（P0）")
    void 是正_退会経路は退会済みpayerでも引継要求を作成する() {
        HandoverRequestResult result = handoverService
                .requestHandoverForWithdrawal(contractId, withdrawnPayerUserId);

        assertThat(result.oldContractId()).isEqualTo(contractId);
        assertThat(result.scopeKind()).isEqualTo(EntitlementScopeKind.TEAM);
        assertThat(result.scopeId()).isEqualTo(TEAM_ID);

        List<BillingPayerHandoverRequestEntity> rows = openRequests();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getStatus()).isEqualTo(PayerHandoverStatus.REQUESTED);
        assertThat(rows.get(0).getOldPayerUserId()).isEqualTo(withdrawnPayerUserId);
    }

    @Test
    @DisplayName("越境不可: 契約の payer ではないユーザーを渡しても引継要求は作られない")
    void 越境_payer不一致は拒否される() {
        assertThatThrownBy(() -> handoverService
                .requestHandoverForWithdrawal(contractId, otherAdminUserId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(EntitlementErrorCode.HANDOVER_NOT_OLD_PAYER.getCode());

        assertThat(openRequests()).isEmpty();
    }

    @Test
    @DisplayName("退会取消: REQUESTED が FAILED へ終端化し、同一契約への再要求が通る（P1-3）")
    void 退会取消_REQUESTEDが終端化して再要求できる() {
        handoverService.requestHandoverForWithdrawal(contractId, withdrawnPayerUserId);
        assertThat(openRequests()).hasSize(1);

        int terminated = handoverService
                .failRequestedHandoversOnWithdrawalCancelled(withdrawnPayerUserId);
        assertThat(terminated).isEqualTo(1);

        List<BillingPayerHandoverRequestEntity> rows = allRequests();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getStatus()).isEqualTo(PayerHandoverStatus.FAILED);

        // 終端化されていれば同一契約への次の申請が通る（残っていれば「進行中」で塞がれる）。
        assertThatCode(() -> handoverService
                .requestHandoverForWithdrawal(contractId, withdrawnPayerUserId))
                .doesNotThrowAnyException();
    }

    // ============================================================
    // DB 実値の読み出し（第一次キャッシュを避けて必ず DB から読む）
    // ============================================================

    private List<BillingPayerHandoverRequestEntity> openRequests() {
        return transactionTemplate.execute(tx -> {
            entityManager.clear();
            return handoverRequestRepository.findByOldContractIdAndStatusNotIn(
                    contractId, BillingPayerHandoverTxService.TERMINAL_STATUSES);
        });
    }

    private List<BillingPayerHandoverRequestEntity> allRequests() {
        return transactionTemplate.execute(tx -> {
            entityManager.clear();
            return handoverRequestRepository.findAll().stream()
                    .filter(h -> contractId.equals(h.getOldContractId()))
                    .toList();
        });
    }

    private Long insertUser(String suffix) {
        UserEntity user = UserEntity.builder()
                .email("withdrawal-handover-" + suffix + "-" + System.nanoTime() + "@example.com")
                .lastName("退会").firstName(suffix).displayName("退会 " + suffix)
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
}
