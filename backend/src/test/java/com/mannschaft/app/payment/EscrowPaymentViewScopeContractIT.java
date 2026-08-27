package com.mannschaft.app.payment;

import com.mannschaft.app.payment.connect.ConnectAccountEntity;
import com.mannschaft.app.payment.connect.ConnectAccountRepository;
import com.mannschaft.app.payment.connect.OnboardingStatus;
import com.mannschaft.app.payment.connect.ScopeKind;
import com.mannschaft.app.payment.escrow.EscrowCaptureMode;
import com.mannschaft.app.payment.escrow.EscrowSourceKind;
import com.mannschaft.app.payment.escrow.EscrowStatus;
import com.mannschaft.app.payment.escrow.EscrowTransactionEntity;
import com.mannschaft.app.payment.escrow.EscrowTransactionRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認可漏れ監査 第2波（金銭）: エスクロー照会 2 エンドポイントの API 契約テスト。
 *
 * <p><b>対象 EP</b></p>
 * <ul>
 *   <li>{@code GET /api/v1/payment/escrow/{id}} — エスクロー状態照会</li>
 *   <li>{@code GET /api/v1/payment/escrow/recruitment/{listingId}/{participantId}/payment-intent}
 *       — 札主の決済確認</li>
 * </ul>
 *
 * <p><b>保証する内容</b>: 照会できるのは支払者本人と受取側スコープの権限保有者のみ。
 * どちらにも該当しない照会者には、金額も状態も返さず存在ごと 404
 * （{@code PAYMENT_C002}）で秘匿する。受取側でないスコープの管理者も同様に 404 とし、
 * 「無関係な第三者」と「別スコープの管理者」の応答を区別させない。</p>
 *
 * <p><b>Stripe 非依存の設計</b>: エスクローを {@code CAPTURED} で seed するため、
 * 支払者本人の照会でも {@code clientSecret} の retrieve（Stripe API 呼び出し）経路には入らない
 * （{@code ConnectChargeService.buildPaymentView} が {@code PENDING_CONFIRMATION} /
 * 未 capture の {@code AUTHORIZED} のみ retrieve する）。</p>
 *
 * <p><b>未認証（401）経路について</b>: {@code addFilters = false} のため未認証リクエストの経路は存在しない。
 * 未認証の遮断は {@code SecurityConfig} の {@code anyRequest().authenticated()} が担保する。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("エスクロー照会 認可契約テスト（認可根治 第2波・金銭）")
class EscrowPaymentViewScopeContractIT extends AbstractMySqlIntegrationTest {

    /** 対象が見つからない（404・存在秘匿）。 */
    private static final String PAYMENT_RESOURCE_NOT_FOUND = "PAYMENT_C002";

    /** 謝礼の出所 ID（札 ID / 応募 ID）。既存 seed と衝突しない高位の値を使う。 */
    private static final long LISTING_ID = 987_654_321L;
    private static final long PARTICIPANT_ID = 987_654_322L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ConnectAccountRepository connectAccountRepository;

    @Autowired
    private EscrowTransactionRepository escrowTransactionRepository;

    @PersistenceContext
    private EntityManager em;

    /** 受取側チーム。 */
    private Long payeeTeamId;
    /** 受取側ではない別チーム（その管理者も照会できてはならない）。 */
    private Long otherTeamId;

    /** 支払者本人（escrow の payer_scope_id）。 */
    private Long payerId;
    /** 支払者でも受取側でもない第三者。 */
    private Long outsiderId;
    /** 受取側ではない別チームの管理者。 */
    private Long otherTeamAdminId;

    private UUID escrowId;

    @BeforeEach
    void setUp() {
        payeeTeamId = insertTeam("エスクロー受取チーム " + System.nanoTime());
        otherTeamId = insertTeam("エスクロー無関係チーム " + System.nanoTime());

        payerId = insertUser("escrow-payer@example.com");
        outsiderId = insertUser("escrow-outsider@example.com");
        otherTeamAdminId = insertUser("escrow-other-admin@example.com");

        // priority は roles テーブル準拠（ADMIN=2）。別チームの ADMIN 権限を持たせても
        // 受取スコープが異なるため照会できないことを検証する。
        insertRole("ADMIN", "管理者", 2, true);
        em.flush();
        insertUserRole(otherTeamAdminId, roleId("ADMIN"), otherTeamId);

        ConnectAccountEntity payee = connectAccountRepository.save(ConnectAccountEntity.builder()
                .scopeKind(ScopeKind.TEAM)
                .scopeId(payeeTeamId)
                .stripeAccountId("acct_test_dummy_escrow")
                .onboardingStatus(OnboardingStatus.READY)
                .chargesEnabled(Boolean.TRUE)
                .payoutsEnabled(Boolean.TRUE)
                .country("JP")
                .defaultCurrency("JPY")
                .build());

        // CAPTURED で seed するため clientSecret の retrieve（Stripe 呼び出し）経路には入らない。
        EscrowTransactionEntity escrow = escrowTransactionRepository.save(EscrowTransactionEntity.builder()
                .sourceKind(EscrowSourceKind.RECRUITMENT)
                .captureMode(EscrowCaptureMode.MANUAL)
                .sourceId(LISTING_ID)
                .sourceParticipantId(PARTICIPANT_ID)
                .payerScopeKind(ScopeKind.USER)
                .payerScopeId(payerId)
                .payeeKind(ScopeKind.TEAM)
                .payeeConnectAccountId(payee.getId())
                .faceAmount(1000L)
                .amount(1025L)
                .currency("JPY")
                .applicationFeeAmount(25L)
                .feePolicyKey("DEFAULT")
                .status(EscrowStatus.CAPTURED)
                .build());
        escrowId = escrow.getId();

        em.flush();
        em.clear();
    }

    @Test
    @DisplayName("支払者本人はエスクロー状態を照会できる（正常系・clientSecret は含まれない）")
    void 支払者本人は照会できる() throws Exception {
        setAuthentication(payerId);
        mockMvc.perform(get("/api/v1/payment/escrow/{id}", escrowId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.escrowTransactionId").value(escrowId.toString()))
                .andExpect(jsonPath("$.data.status").value("CAPTURED"))
                // PCI: capture 済みのエスクローでは clientSecret を返さない（Stripe への retrieve も行わない）。
                .andExpect(jsonPath("$.data.clientSecret").value(nullValue()));
    }

    @Test
    @DisplayName("無関係な第三者はエスクロー状態を照会できない（404 で存在ごと秘匿）")
    void 第三者はエスクローを照会できない() throws Exception {
        setAuthentication(outsiderId);
        mockMvc.perform(get("/api/v1/payment/escrow/{id}", escrowId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value(PAYMENT_RESOURCE_NOT_FOUND));
    }

    @Test
    @DisplayName("受取側でない別チームの管理者もエスクロー状態を照会できない（404）")
    void 別スコープの管理者は照会できない() throws Exception {
        setAuthentication(otherTeamAdminId);
        mockMvc.perform(get("/api/v1/payment/escrow/{id}", escrowId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value(PAYMENT_RESOURCE_NOT_FOUND));
    }

    @Test
    @DisplayName("支払者本人は謝礼の決済確認ビューを取得できる（正常系）")
    void 支払者本人は決済確認できる() throws Exception {
        setAuthentication(payerId);
        mockMvc.perform(get("/api/v1/payment/escrow/recruitment/{listingId}/{participantId}/payment-intent",
                        LISTING_ID, PARTICIPANT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.escrowTransactionId").value(escrowId.toString()));
    }

    @Test
    @DisplayName("無関係な第三者は謝礼の決済確認ビューを取得できない（404 で存在ごと秘匿）")
    void 第三者は決済確認できない() throws Exception {
        setAuthentication(outsiderId);
        mockMvc.perform(get("/api/v1/payment/escrow/recruitment/{listingId}/{participantId}/payment-intent",
                        LISTING_ID, PARTICIPANT_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value(PAYMENT_RESOURCE_NOT_FOUND));
    }

    @Test
    @DisplayName("受取側でない別チームの管理者も謝礼の決済確認ビューを取得できない（404）")
    void 別スコープの管理者は決済確認できない() throws Exception {
        setAuthentication(otherTeamAdminId);
        mockMvc.perform(get("/api/v1/payment/escrow/recruitment/{listingId}/{participantId}/payment-intent",
                        LISTING_ID, PARTICIPANT_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value(PAYMENT_RESOURCE_NOT_FOUND));
    }

    // ═════════════════════════════════════════════════════════════════════
    // seed ヘルパー（MemberPaymentAuthzIntegrationTest 踏襲）
    // ═════════════════════════════════════════════════════════════════════

    private void setAuthentication(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    private void insertRole(String name, String displayName, int priority, boolean isSystem) {
        // 冪等化: roles はグローバル参照テーブルのため、既存なら再利用し二重INSERTしない
        // （同一 name の重複INSERTは roles の UNIQUE 制約違反になる。CI shard 再編成で
        // 同一 JVM 内の同居テストが変わり得るため、盲目的 INSERT は禁止）。
        Number existingRoleCount = (Number) em.createNativeQuery("SELECT COUNT(*) FROM roles WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult();
        if (existingRoleCount.longValue() > 0) {
            return;
        }
        em.createNativeQuery(
                        "INSERT INTO roles (name, display_name, priority, is_system, created_at, updated_at) "
                                + "VALUES (:name, :dn, :pri, :sys, NOW(), NOW())")
                .setParameter("name", name)
                .setParameter("dn", displayName)
                .setParameter("pri", priority)
                .setParameter("sys", isSystem ? 1 : 0)
                .executeUpdate();
    }

    private Long roleId(String name) {
        return ((Number) em.createNativeQuery("SELECT id FROM roles WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }

    private void insertUserRole(Long uid, Long roleIdParam, Long teamIdParam) {
        em.createNativeQuery(
                        "INSERT INTO user_roles (user_id, role_id, team_id, organization_id, created_at, updated_at) "
                                + "VALUES (:uid, :rid, :tid, NULL, NOW(), NOW())")
                .setParameter("uid", uid)
                .setParameter("rid", roleIdParam)
                .setParameter("tid", teamIdParam)
                .executeUpdate();
    }

    private Long insertUser(String email) {
        em.createNativeQuery(
                        "INSERT INTO users ("
                                + "email, last_name, first_name, display_name, status, "
                                + "is_searchable, handle_searchable, contact_approval_required, "
                                + "online_visibility, dm_receive_from, encryption_key_version, "
                                + "locale, timezone, reporting_restricted, follow_list_visibility, "
                                + "care_notification_enabled, offline_only, "
                                + "created_at, updated_at) "
                                + "VALUES (:email, 'エスクロー契約', 'テスト', 'エスクロー契約テスト', 'ACTIVE', "
                                + "1, 1, 1, "
                                + "'NOBODY', 'ANYONE', 1, "
                                + "'ja', 'Asia/Tokyo', 0, 'PUBLIC', "
                                + "1, 0, "
                                + "NOW(), NOW())")
                .setParameter("email", email)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM users WHERE email = :email")
                .setParameter("email", email)
                .getSingleResult()).longValue();
    }

    private Long insertTeam(String name) {
        em.createNativeQuery(
                        "INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, "
                                + "created_at, updated_at) "
                                + "VALUES (:name, 'PUBLIC', 1, 0, 0, "
                                + "CONCAT('s-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }
}
