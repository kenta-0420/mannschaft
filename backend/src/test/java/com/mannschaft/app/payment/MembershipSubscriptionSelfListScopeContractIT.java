package com.mannschaft.app.payment;

import com.mannschaft.app.payment.entity.MembershipSubscriptionEntity;
import com.mannschaft.app.payment.connect.ScopeKind;
import com.mannschaft.app.payment.repository.MembershipSubscriptionRepository;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認可根治戦役 第2波 — 継続課金（F08.9）自分の一覧 EP の自己スコープ契約テスト。
 *
 * <p>本テストが固定する防御仕様: {@code GET /api/v1/me/membership-subscriptions} の検索条件は
 * {@code payer_user_id = 認証主体の userId} のみに束縛され、エンドポイントは引数を一切取らない。
 * したがって他人が払い手である継続課金は結果に混入しない。</p>
 *
 * <p>本テストは自己スコープ宣言（{@code @SelfScopedEndpoint}）の証跡を兼ねる:
 * {@code MembershipSubscriptionController#listMySubscriptions}。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("継続課金 自分の一覧 自己スコープ 認可契約テスト（第2波）")
class MembershipSubscriptionSelfListScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MembershipSubscriptionRepository membershipSubscriptionRepository;

    @PersistenceContext
    private EntityManager em;

    /** 継続課金の払い手。 */
    private Long payerUserId;
    /** 継続課金を一切持たない第三者。 */
    private Long strangerUserId;

    @BeforeEach
    void setUp() {
        payerUserId = insertUser("subs-payer@example.com");
        strangerUserId = insertUser("subs-stranger@example.com");

        membershipSubscriptionRepository.save(MembershipSubscriptionEntity.builder()
                .paymentItemId(900_001L)
                .beneficiaryUserId(payerUserId)
                .payerUserId(payerUserId)
                .scopeKind(ScopeKind.TEAM)
                .scopeId(900_002L)
                .payeeConnectAccountId(UUID.randomUUID())
                .billingInterval(BillingInterval.MONTHLY)
                .status(MembershipSubscriptionStatus.ACTIVE)
                .feePolicyKey("DEFAULT")
                .faceAmount(3000)
                .currency("JPY")
                .stripeSubscriptionId("sub_selfscope_contract_it")
                .cancelAtPeriodEnd(false)
                .build());

        em.flush();
        em.clear();
    }

    @Test
    @DisplayName("GET /api/v1/me/membership-subscriptions: 払い手でない第三者には 1 件も返らない"
            + "（MembershipSubscriptionController#listMySubscriptions）")
    void 第三者の一覧は空() throws Exception {
        setAuth(strangerUserId);
        mockMvc.perform(get("/api/v1/me/membership-subscriptions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private void setAuth(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
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
                                + "VALUES (:email, 'SUBSAUTHZ', 'テスト', 'SUBSAUTHZ テスト', 'ACTIVE', "
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
}
