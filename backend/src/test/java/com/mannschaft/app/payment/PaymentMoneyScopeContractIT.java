package com.mannschaft.app.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.payment.connect.ScopeKind;
import com.mannschaft.app.payment.dto.BulkCheckoutRequest;
import com.mannschaft.app.payment.dto.MembershipCheckoutRequest;
import com.mannschaft.app.payment.dto.SubscribeRequest;
import com.mannschaft.app.payment.entity.MemberPaymentEntity;
import com.mannschaft.app.payment.entity.MembershipSubscriptionEntity;
import com.mannschaft.app.payment.repository.MemberPaymentRepository;
import com.mannschaft.app.payment.repository.MembershipSubscriptionRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認可漏れ監査 第2波（金銭）: 会費・継続課金・会費領収書エンドポイントの API 契約テスト。
 *
 * <p><b>保証する内容</b></p>
 * <ul>
 *   <li>会費領収書は払い手本人または受益者本人のみ取得でき、第三者は 403、記録不在は 404。</li>
 *   <li>継続課金の解約・スキップ・再開は払い手本人（または受益者の後見保護者）のみ実行でき、
 *       無権原の操作では <b>DB の状態が一切変わらない</b>。</li>
 *   <li>権原のない受益者を指定した加入・チェックアウトは 403 で拒否し、
 *       <b>member_payments / membership_subscriptions への起票も外部課金も発生しない</b>
 *       （権原検証が副作用より前に位置することの実測）。</li>
 *   <li>自分の支払い一覧・未払い一覧・継続課金一覧は認証主体に閉じ、他会員の記録が混入しない。</li>
 * </ul>
 *
 * <p><b>Stripe 非依存の設計</b>: 権原検証を通過した経路は、いずれも受領 Connect 口座が未登録であるため
 * {@code ONBOARDING_NOT_READY}（{@code PAYMENT_C030} / 409）で停止する。これにより
 * 「認可は通ったが外部決済へは進まない」状態を Stripe を呼ばずに固定できる。
 * 認可拒否（403 / {@code MEMBERSHIP_BILLING_001}）と明確に区別できるため、
 * 正常系（＝認可が正当な払い手を通していること）の裏取りとして用いる。</p>
 *
 * <p><b>未認証（401）経路について</b>: {@code addFilters = false} でフィルタチェーンを外しているため
 * 未認証リクエストの経路は存在しない。未認証の遮断は {@code SecurityConfig} の
 * {@code anyRequest().authenticated()} が担保する（金型 {@code QuickMemoSelfScopeContractIT} と同方針）。</p>
 *
 * <p>seed ヘルパーは {@link MemberPaymentAuthzIntegrationTest} を踏襲する。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("会費・継続課金・領収書 認可契約テスト（認可根治 第2波・金銭）")
class PaymentMoneyScopeContractIT extends AbstractMySqlIntegrationTest {

    /** 代理払いの権原がない（403）。 */
    private static final String PAYER_NOT_AUTHORIZED = "MEMBERSHIP_BILLING_001";
    /** 継続課金が見つからない（404・存在秘匿）。 */
    private static final String SUBSCRIPTION_NOT_FOUND = "MEMBERSHIP_BILLING_015";
    /** 継続課金の操作者がサブスク所有者でない（403）。 */
    private static final String SUBSCRIPTION_NOT_AUTHORIZED = "MEMBERSHIP_BILLING_018";
    /** 受領 Connect 口座が未登録（409）。認可を通過した証跡として用いる。 */
    private static final String ONBOARDING_NOT_READY = "PAYMENT_C030";
    /** 会費支払い記録が見つからない（404・存在秘匿）。 */
    private static final String MEMBER_PAYMENT_NOT_FOUND = "PAYMENT_029";
    /** 払い手でも受益者でもない第三者の領収書アクセス（403）。 */
    private static final String PAYMENT_ACCESS_DENIED = "PAYMENT_030";
    /** 課金ゲート対象コンテンツが存在しない（404・存在秘匿）。 */
    private static final String CONTENT_NOT_FOUND = "PAYMENT_015";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MemberPaymentRepository memberPaymentRepository;

    @Autowired
    private MembershipSubscriptionRepository membershipSubscriptionRepository;

    @PersistenceContext
    private EntityManager em;

    private Long teamId;

    /** 払い手（サブスク・支払いの payer_user_id）。 */
    private Long payerId;
    /** 受益者（払い手とは別人。会費の受益者 user_id）。 */
    private Long beneficiaryId;
    /** payer / beneficiary のいずれとも関係のない第三者（越境してはならない）。 */
    private Long outsiderId;

    /** 単発会費項目（TEAM スコープ・is_recurring=false）。 */
    private Long oneTimeItemId;
    /** 継続課金項目（TEAM スコープ・is_recurring=true）。 */
    private Long recurringItemId;

    @BeforeEach
    void setUp() {
        teamId = insertTeam("金銭認可テスト チーム " + System.nanoTime());

        payerId = insertUser("money-payer@example.com");
        beneficiaryId = insertUser("money-beneficiary@example.com");
        outsiderId = insertUser("money-outsider@example.com");

        oneTimeItemId = insertPaymentItem("年会費（金銭認可テスト）", teamId, "ANNUAL_FEE", false);
        recurringItemId = insertPaymentItem("月会費（金銭認可テスト）", teamId, "MONTHLY_FEE", true);

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 会費領収書（GET /api/v1/member-payments/{id}/receipt）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("会費領収書 — 払い手本人・受益者本人のみ取得でき、第三者は拒否される")
    class MemberPaymentReceipt {

        @Test
        @DisplayName("第三者は他人の会費領収書を取得できない — 不在と同じ 404 で秘匿（払い手・受益者本人は取得できる）")
        void 第三者は会費領収書を取得できない() throws Exception {
            Long paymentId = insertPaidMemberPayment();

            // 越境は 403 ではなく 404。不在（下のテスト）と同一ステータスに揃えることで
            // 応答差から支払い記録 ID の実在を判別できないようにしている（存在オラクル封じ）。
            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/member-payments/{id}/receipt", paymentId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value(PAYMENT_ACCESS_DENIED));

            setAuthentication(payerId);
            mockMvc.perform(get("/api/v1/member-payments/{id}/receipt", paymentId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.memberPaymentId").value(paymentId));

            setAuthentication(beneficiaryId);
            mockMvc.perform(get("/api/v1/member-payments/{id}/receipt", paymentId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.memberPaymentId").value(paymentId));
        }

        @Test
        @DisplayName("存在しない支払い記録 ID は 404 で秘匿する")
        void 不在の支払い記録は404() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/member-payments/{id}/receipt", 999_999_999L))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value(MEMBER_PAYMENT_NOT_FOUND));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 継続課金の解約・スキップ・再開
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("継続課金の解約・スキップ・再開 — 払い手以外の操作は成立しない")
    class MembershipSubscriptionOperations {

        @Test
        @DisplayName("第三者は他人の継続課金を期末解約できない（解約予約フラグは立たない）")
        void 第三者は継続課金を解約できない() throws Exception {
            UUID subscriptionId = insertPendingSubscription();

            setAuthentication(outsiderId);
            mockMvc.perform(delete("/api/v1/membership-subscriptions/{id}", subscriptionId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value(SUBSCRIPTION_NOT_AUTHORIZED));

            // DB 実値: 期末解約予約は立っていない。
            assertThat(findSubscription(subscriptionId).getCancelAtPeriodEnd()).isFalse();
        }

        @Test
        @DisplayName("第三者は他人の継続課金をスキップできない（スキップ期日は書き込まれない）")
        void 第三者は継続課金をスキップできない() throws Exception {
            UUID subscriptionId = insertPendingSubscription();

            setAuthentication(outsiderId);
            mockMvc.perform(post("/api/v1/membership-subscriptions/{id}/skip", subscriptionId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value(SUBSCRIPTION_NOT_AUTHORIZED));

            // DB 実値: skip_until は未設定のまま。
            assertThat(findSubscription(subscriptionId).getSkipUntil()).isNull();
        }

        @Test
        @DisplayName("第三者は他人の継続課金を再開できない（状態は変わらない）")
        void 第三者は継続課金を再開できない() throws Exception {
            UUID subscriptionId = insertPendingSubscription();

            setAuthentication(outsiderId);
            mockMvc.perform(post("/api/v1/membership-subscriptions/{id}/resume", subscriptionId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value(SUBSCRIPTION_NOT_AUTHORIZED));

            assertThat(findSubscription(subscriptionId).getStatus())
                    .isEqualTo(MembershipSubscriptionStatus.PENDING);
            assertThat(findSubscription(subscriptionId).getSkipUntil()).isNull();
        }

        @Test
        @DisplayName("払い手本人は認可を通過する（PENDING ゆえ状態制約の 409 まで進む）")
        void 払い手本人は認可を通過する() throws Exception {
            UUID subscriptionId = insertPendingSubscription();

            // 認可（403）ではなく状態制約（409）で止まる＝門番が正当な払い手を通していることの裏取り。
            setAuthentication(payerId);
            mockMvc.perform(delete("/api/v1/membership-subscriptions/{id}", subscriptionId))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value(MembershipBillingErrorCode
                            .SUBSCRIPTION_NOT_ACTIVE.getCode()));
        }

        @Test
        @DisplayName("存在しない継続課金 ID は 404 で秘匿する")
        void 不在の継続課金は404() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(delete("/api/v1/membership-subscriptions/{id}", UUID.randomUUID()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value(SUBSCRIPTION_NOT_FOUND));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 加入・チェックアウト（受益者を指定する EP）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("加入・チェックアウト — 権原のない受益者では課金も起票も発生しない")
    class SubscribeAndCheckout {

        @Test
        @DisplayName("権原のない受益者を指定した継続課金 加入は 403 で拒否し、1 件も起票しない")
        void 権原なき受益者への加入は拒否される() throws Exception {
            long before = membershipSubscriptionRepository.count();

            setAuthentication(payerId);
            mockMvc.perform(post("/api/v1/payment-items/{itemId}/subscribe", recurringItemId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(subscribeRequest(outsiderId))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value(PAYER_NOT_AUTHORIZED));

            // DB 実値: membership_subscriptions は 1 件も増えていない。
            em.flush();
            assertThat(membershipSubscriptionRepository.count()).isEqualTo(before);
        }

        @Test
        @DisplayName("本人払いの継続課金 加入は認可を通過する（受領口座未登録の 409 まで進む）")
        void 本人払いの加入は認可を通過する() throws Exception {
            setAuthentication(payerId);
            mockMvc.perform(post("/api/v1/payment-items/{itemId}/subscribe", recurringItemId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(subscribeRequest(payerId))))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value(ONBOARDING_NOT_READY));
        }

        @Test
        @DisplayName("権原のない受益者を指定した会費チェックアウトは 403 で拒否し、支払い記録を作らない")
        void 権原なき受益者へのチェックアウトは拒否される() throws Exception {
            setAuthentication(payerId);
            mockMvc.perform(post("/api/v1/payment-items/{itemId}/checkout", oneTimeItemId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(checkoutRequest(outsiderId))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value(PAYER_NOT_AUTHORIZED));

            // DB 実値: member_payments は 1 件も起票されていない。
            em.flush();
            assertThat(memberPaymentRepository.findByPaymentItemId(oneTimeItemId)).isEmpty();
        }

        @Test
        @DisplayName("本人払いの会費チェックアウトは認可を通過する（受領口座未登録の 409 まで進む）")
        void 本人払いのチェックアウトは認可を通過する() throws Exception {
            setAuthentication(payerId);
            mockMvc.perform(post("/api/v1/payment-items/{itemId}/checkout", oneTimeItemId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(checkoutRequest(payerId))))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value(ONBOARDING_NOT_READY));
        }

        @Test
        @DisplayName("まとめ払いで権原のない受益者を指定した明細はスキップされ、支払い記録を作らない")
        void まとめ払いは権原なき明細をスキップする() throws Exception {
            setAuthentication(payerId);
            mockMvc.perform(post("/api/v1/me/payable-dues/bulk-checkout")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(new BulkCheckoutRequest(outsiderId, List.of(oneTimeItemId)))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.results.length()").value(1))
                    .andExpect(jsonPath("$.data.results[0].status").value("SKIPPED"))
                    .andExpect(jsonPath("$.data.results[0].skipReason").value("NOT_AUTHORIZED"));

            // DB 実値: member_payments は 1 件も起票されていない。
            em.flush();
            assertThat(memberPaymentRepository.findByPaymentItemId(oneTimeItemId)).isEmpty();
        }

        @Test
        @DisplayName("まとめ払いで本人払いの明細は認可を通過する（受領口座未登録としてスキップ理由が分かれる）")
        void まとめ払いの本人払い明細は認可を通過する() throws Exception {
            setAuthentication(payerId);
            mockMvc.perform(post("/api/v1/me/payable-dues/bulk-checkout")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(new BulkCheckoutRequest(payerId, List.of(oneTimeItemId)))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.results[0].skipReason").value("CONNECT_NOT_READY"));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 自己スコープの参照 EP
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("自分の支払い・未払い・継続課金の一覧 — 認証主体に閉じる")
    class SelfScopedQueries {

        @Test
        @DisplayName("自分の支払い一覧には他会員の支払い記録が混入しない")
        void 支払い一覧は自己スコープに閉じる() throws Exception {
            Long paymentId = insertPaidMemberPayment();

            // 受益者（user_id）本人には見える。
            setAuthentication(beneficiaryId);
            mockMvc.perform(get("/api/v1/me/payments"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].id").value(paymentId));

            // 無関係な第三者には 1 件も見えない。
            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/me/payments"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(0));
        }

        @Test
        @DisplayName("自分の継続課金一覧には他会員のサブスクが混入しない")
        void 継続課金一覧は自己スコープに閉じる() throws Exception {
            UUID subscriptionId = insertPendingSubscription();

            setAuthentication(payerId);
            mockMvc.perform(get("/api/v1/me/membership-subscriptions"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].id").value(subscriptionId.toString()));

            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/me/membership-subscriptions"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(0));
        }

        @Test
        @DisplayName("未払い項目一覧・払える未払い会費一覧は認証主体の分だけを返す")
        void 未払い一覧は自己スコープに閉じる() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/me/payment-requirements"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(0));

            mockMvc.perform(get("/api/v1/me/payable-dues"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items.length()").value(0));
        }

        @Test
        @DisplayName("ペイウォール判定は存在しないコンテンツを 404 で秘匿する")
        void ペイウォール判定は存在しないコンテンツを秘匿する() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/content-gates/check")
                            .param("contentType", "POST")
                            .param("contentId", "999999999"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value(CONTENT_NOT_FOUND));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // リクエスト DTO / seed ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private void setAuthentication(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    private String json(Object body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    private SubscribeRequest subscribeRequest(Long beneficiaryUserId) {
        return new SubscribeRequest(beneficiaryUserId, null, null);
    }

    private MembershipCheckoutRequest checkoutRequest(Long beneficiaryUserId) {
        return new MembershipCheckoutRequest(beneficiaryUserId, null);
    }

    /** 払い手 payer・受益者 beneficiary の支払い済み記録を 1 件作る（金額は明らかにテスト用の値）。 */
    private Long insertPaidMemberPayment() {
        MemberPaymentEntity payment = MemberPaymentEntity.builder()
                .userId(beneficiaryId)
                .paymentItemId(oneTimeItemId)
                .amountPaid(new BigDecimal("1000.00"))
                .currency("JPY")
                .paymentMethod(PaymentMethod.MANUAL)
                .status(PaymentStatus.PAID)
                .payerUserId(payerId)
                .payerRelationship(PayerRelationship.GUARDIAN)
                .paidAt(LocalDateTime.now())
                .build();
        Long id = memberPaymentRepository.save(payment).getId();
        em.flush();
        em.clear();
        return id;
    }

    /**
     * 払い手 payer・受益者 beneficiary の継続課金を PENDING で 1 件作る。
     *
     * <p>PENDING かつ {@code stripe_subscription_id} 未連結のため、認可を通過した操作は
     * 状態制約（409）で止まり Stripe を呼ばない。認可拒否（403）と明確に区別できる。</p>
     */
    private UUID insertPendingSubscription() {
        MembershipSubscriptionEntity subscription = MembershipSubscriptionEntity.builder()
                .paymentItemId(oneTimeItemId)
                .beneficiaryUserId(beneficiaryId)
                .payerUserId(payerId)
                .scopeKind(ScopeKind.TEAM)
                .scopeId(teamId)
                .payeeConnectAccountId(UUID.randomUUID())
                .billingInterval(BillingInterval.MONTHLY)
                .faceAmount(1000)
                .build();
        UUID id = membershipSubscriptionRepository.save(subscription).getId();
        em.flush();
        em.clear();
        return id;
    }

    private MembershipSubscriptionEntity findSubscription(UUID id) {
        em.flush();
        em.clear();
        return membershipSubscriptionRepository.findByIdAndDeletedAtIsNull(id).orElseThrow();
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
                                + "VALUES (:email, '金銭契約', 'テスト', '金銭契約テスト', 'ACTIVE', "
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

    private Long insertPaymentItem(String name, Long teamIdParam, String type, boolean isRecurring) {
        em.createNativeQuery(
                        "INSERT INTO payment_items ("
                                + "team_id, organization_id, name, type, amount, currency, "
                                + "is_active, display_order, grace_period_days, is_recurring, created_at, updated_at) "
                                + "VALUES (:tid, NULL, :name, :type, 1000.00, 'JPY', "
                                + "1, 0, 0, :rec, NOW(), NOW())")
                .setParameter("tid", teamIdParam)
                .setParameter("name", name)
                .setParameter("type", type)
                .setParameter("rec", isRecurring ? 1 : 0)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM payment_items WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }
}
