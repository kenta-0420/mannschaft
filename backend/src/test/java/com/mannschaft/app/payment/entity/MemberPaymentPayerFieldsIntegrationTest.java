package com.mannschaft.app.payment.entity;

import com.mannschaft.app.payment.PayerRelationship;
import com.mannschaft.app.payment.PaymentItemType;
import com.mannschaft.app.payment.PaymentMethod;
import com.mannschaft.app.payment.PaymentStatus;
import com.mannschaft.app.payment.repository.MemberPaymentRepository;
import com.mannschaft.app.payment.repository.PaymentItemRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F08.9 P1 Wave2: {@link MemberPaymentEntity} 払い手分離フィールド（V74.001 追加列）の
 * 永続化・取得テスト（実 MySQL / Testcontainers）。
 *
 * <p>テスト方針:</p>
 * <ul>
 *   <li>T3: 追加 5 フィールドの永続化・取得（UUID 列の BINARY(16)往復含む）</li>
 *   <li>T4: {@link MemberPaymentRepository#existsValidPaidPayment(Long, Long)} の回帰保証
 *       （受益者キー userId/paymentItemId が不変であることを確認）</li>
 *   <li>T4: {@link MemberPaymentRepository#findByEscrowTransactionId(UUID)} の動作確認</li>
 * </ul>
 *
 * <p>設計書: docs/features/F08.9_membership_billing_paywall/01_data_model.md §1.1</p>
 */
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("MemberPaymentEntity 払い手分離フィールド 永続化テスト（Testcontainers）")
class MemberPaymentPayerFieldsIntegrationTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private MemberPaymentRepository memberPaymentRepository;

    @Autowired
    private PaymentItemRepository paymentItemRepository;

    @PersistenceContext
    private EntityManager em;

    // ===== ヘルパー =====

    private MemberPaymentEntity buildBase(Long userId, Long paymentItemId) {
        return MemberPaymentEntity.builder()
                .userId(userId)
                .paymentItemId(paymentItemId)
                .amountPaid(new BigDecimal("5000.00"))
                .paymentMethod(PaymentMethod.STRIPE)
                .status(PaymentStatus.PAID)
                .validFrom(LocalDate.of(2026, 4, 1))
                .validUntil(LocalDate.of(2027, 3, 31))
                .build();
    }

    private PaymentItemEntity createPaymentItem() {
        return paymentItemRepository.save(PaymentItemEntity.builder()
                .name("payer-fields-test-" + System.nanoTime())
                .type(PaymentItemType.MONTHLY_FEE)
                .amount(new BigDecimal("5000.00"))
                .currency("JPY")
                .gracePeriodDays((short) 0)
                .build());
    }

    // ===== T3: 追加フィールドの永続化・取得 =====

    @Nested
    @DisplayName("T3: V74.001 追加フィールドの永続化・取得")
    class T3_PayerFieldsPersistence {

        @Test
        @DisplayName("payerUserId/payerRelationship のみ設定した場合に正しく保存・取得できる")
        void persist_selfPaymentFields_roundTrip() {
            MemberPaymentEntity entity = buildBase(100L, 1L).toBuilder()
                    .payerUserId(100L)
                    .payerRelationship(PayerRelationship.SELF)
                    .build();

            memberPaymentRepository.save(entity);
            em.flush();
            em.clear();

            MemberPaymentEntity found = memberPaymentRepository.findById(entity.getId()).orElseThrow();
            assertThat(found.getPayerUserId()).isEqualTo(100L);
            assertThat(found.getPayerRelationship()).isEqualTo(PayerRelationship.SELF);
            assertThat(found.getPaymentProxyGrantId()).isNull();
            assertThat(found.getEscrowTransactionId()).isNull();
            assertThat(found.getMembershipSubscriptionId()).isNull();
        }

        @Test
        @DisplayName("UUID 列（paymentProxyGrantId/escrowTransactionId/membershipSubscriptionId）の BINARY(16) 往復が正しい")
        void persist_uuidFields_roundTrip() {
            UUID proxyGrantId = UUID.randomUUID();
            UUID escrowTxId = UUID.randomUUID();
            UUID subscriptionId = UUID.randomUUID();

            MemberPaymentEntity entity = buildBase(200L, 2L).toBuilder()
                    .payerUserId(300L)
                    .payerRelationship(PayerRelationship.PROXY_GRANT)
                    .paymentProxyGrantId(proxyGrantId)
                    .escrowTransactionId(escrowTxId)
                    .membershipSubscriptionId(subscriptionId)
                    .build();

            memberPaymentRepository.save(entity);
            em.flush();
            em.clear();

            MemberPaymentEntity found = memberPaymentRepository.findById(entity.getId()).orElseThrow();
            assertThat(found.getPayerUserId()).isEqualTo(300L);
            assertThat(found.getPayerRelationship()).isEqualTo(PayerRelationship.PROXY_GRANT);
            assertThat(found.getPaymentProxyGrantId()).isEqualTo(proxyGrantId);
            assertThat(found.getEscrowTransactionId()).isEqualTo(escrowTxId);
            assertThat(found.getMembershipSubscriptionId()).isEqualTo(subscriptionId);
        }

        @Test
        @DisplayName("すべての PayerRelationship 値を永続化できる")
        void persist_allPayerRelationshipValues() {
            Long paymentItemId = createPaymentItem().getId();
            for (PayerRelationship rel : PayerRelationship.values()) {
                long userId = 1000L + rel.ordinal();
                MemberPaymentEntity entity = buildBase(userId, paymentItemId).toBuilder()
                        .payerUserId(userId)
                        .payerRelationship(rel)
                        .build();
                memberPaymentRepository.save(entity);
            }
            em.flush();
            em.clear();

            for (PayerRelationship rel : PayerRelationship.values()) {
                long userId = 1000L + rel.ordinal();
                boolean found = memberPaymentRepository.existsValidPaidPayment(userId, paymentItemId);
                assertThat(found).isTrue();
            }
        }

        @Test
        @DisplayName("既存フィールドは PayerRelationship 追加後も保存・取得できる（非破壊確認）")
        void persist_existingFields_unaffected() {
            MemberPaymentEntity entity = buildBase(500L, 5L)
                    .toBuilder()
                    .stripeCheckoutSessionId("cs_test_001")
                    .note("非破壊テスト")
                    .build();

            memberPaymentRepository.save(entity);
            em.flush();
            em.clear();

            MemberPaymentEntity found = memberPaymentRepository.findById(entity.getId()).orElseThrow();
            assertThat(found.getUserId()).isEqualTo(500L);
            assertThat(found.getPaymentItemId()).isEqualTo(5L);
            assertThat(found.getStripeCheckoutSessionId()).isEqualTo("cs_test_001");
            assertThat(found.getNote()).isEqualTo("非破壊テスト");
            assertThat(found.getStatus()).isEqualTo(PaymentStatus.PAID);
            // 払い手フィールドは NULL のまま
            assertThat(found.getPayerUserId()).isNull();
            assertThat(found.getPayerRelationship()).isNull();
        }
    }

    // ===== T4: existsValidPaidPayment の回帰保証 =====

    @Nested
    @DisplayName("T4: existsValidPaidPayment 受益者キー不変の回帰保証")
    class T4_ExistsValidPaidPaymentRegression {

        @Test
        @DisplayName("受益者（userId/paymentItemId）で PAID を検出できる（払い手フィールド追加後も不変）")
        void existsValidPaidPayment_beneficiaryKey_works() {
            Long beneficiaryId = 700L;
            Long paymentItemId = createPaymentItem().getId();

            MemberPaymentEntity entity = buildBase(beneficiaryId, paymentItemId).toBuilder()
                    .payerUserId(800L)
                    .payerRelationship(PayerRelationship.GUARDIAN)
                    .build();
            memberPaymentRepository.save(entity);
            em.flush();
            em.clear();

            // 受益者キーで PAID を検出できる
            assertThat(memberPaymentRepository.existsValidPaidPayment(beneficiaryId, paymentItemId))
                    .isTrue();
            // 払い手キーでは検出しない（受益者キーのみ）
            assertThat(memberPaymentRepository.existsValidPaidPayment(800L, paymentItemId))
                    .isFalse();
        }

        @Test
        @DisplayName("validUntil が過去の場合は false を返す（有効期限チェックが機能する）")
        void existsValidPaidPayment_expiredRecord_returnsFalse() {
            Long beneficiaryId = 710L;
            Long paymentItemId = createPaymentItem().getId();

            MemberPaymentEntity entity = MemberPaymentEntity.builder()
                    .userId(beneficiaryId)
                    .paymentItemId(paymentItemId)
                    .amountPaid(new BigDecimal("3000.00"))
                    .paymentMethod(PaymentMethod.STRIPE)
                    .status(PaymentStatus.PAID)
                    .validFrom(LocalDate.of(2025, 1, 1))
                    .validUntil(LocalDate.of(2025, 12, 31))  // 過去
                    .build();
            memberPaymentRepository.save(entity);
            em.flush();
            em.clear();

            assertThat(memberPaymentRepository.existsValidPaidPayment(beneficiaryId, paymentItemId))
                    .isFalse();
        }

        @Test
        @DisplayName("PENDING レコードは existsValidPaidPayment で検出しない")
        void existsValidPaidPayment_pendingRecord_returnsFalse() {
            Long beneficiaryId = 720L;
            Long paymentItemId = createPaymentItem().getId();

            MemberPaymentEntity entity = MemberPaymentEntity.builder()
                    .userId(beneficiaryId)
                    .paymentItemId(paymentItemId)
                    .amountPaid(new BigDecimal("3000.00"))
                    .paymentMethod(PaymentMethod.STRIPE)
                    .status(PaymentStatus.PENDING)
                    .build();
            memberPaymentRepository.save(entity);
            em.flush();
            em.clear();

            assertThat(memberPaymentRepository.existsValidPaidPayment(beneficiaryId, paymentItemId))
                    .isFalse();
        }
    }

    // ===== T4: findByEscrowTransactionId =====

    @Nested
    @DisplayName("T4: findByEscrowTransactionId の動作確認")
    class T4_FindByEscrowTransactionId {

        @Test
        @DisplayName("escrowTransactionId が一致するレコードを取得できる")
        void findByEscrowTransactionId_found() {
            UUID escrowTxId = UUID.randomUUID();

            MemberPaymentEntity entity = buildBase(900L, 20L).toBuilder()
                    .payerUserId(900L)
                    .payerRelationship(PayerRelationship.SELF)
                    .escrowTransactionId(escrowTxId)
                    .build();
            memberPaymentRepository.save(entity);
            em.flush();
            em.clear();

            Optional<MemberPaymentEntity> result =
                    memberPaymentRepository.findByEscrowTransactionId(escrowTxId);

            assertThat(result).isPresent();
            assertThat(result.get().getEscrowTransactionId()).isEqualTo(escrowTxId);
            assertThat(result.get().getUserId()).isEqualTo(900L);
        }

        @Test
        @DisplayName("存在しない escrowTransactionId は empty を返す")
        void findByEscrowTransactionId_notFound() {
            Optional<MemberPaymentEntity> result =
                    memberPaymentRepository.findByEscrowTransactionId(UUID.randomUUID());

            assertThat(result).isEmpty();
        }
    }
}
