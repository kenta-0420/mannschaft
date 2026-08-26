package com.mannschaft.app.payment.repository;

import com.mannschaft.app.payment.PaymentProxyGrantStatus;
import com.mannschaft.app.payment.PaymentProxyGrantedVia;
import com.mannschaft.app.payment.entity.PaymentProxyGrantEntity;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F08.9 P1 Wave2: {@link PaymentProxyGrantRepository} 統合テスト（実 MySQL / Testcontainers）。
 *
 * <p>テスト方針（T5）:</p>
 * <ul>
 *   <li>UUIDv7 主キーの永続化・取得（{@link UuidV7Entity} 継承）</li>
 *   <li>{@link PaymentProxyGrantRepository#findActiveGrant(Long, Long, Long, PaymentProxyGrantStatus, LocalDateTime)}
 *       の ACTIVE × 有効期間ゲート引き当て</li>
 *   <li>包括 grant（paymentItemId=NULL）が特定 item にもマッチすること</li>
 *   <li>有効期間外・REVOKED な grant は引き当てないこと</li>
 * </ul>
 *
 * <p>設計書: docs/features/F08.9_membership_billing_paywall/01_data_model.md §2.3</p>
 */
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("PaymentProxyGrantRepository 統合テスト（ACTIVE×期間ゲート）")
class PaymentProxyGrantRepositoryIntegrationTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private PaymentProxyGrantRepository proxyGrantRepository;

    @PersistenceContext
    private EntityManager em;

    private static final Long ORG_ID = 1L;
    private static final Long BENEFICIARY_ID = 100L;
    private static final Long PAYER_ID = 200L;
    private static final Long ITEM_ID = 10L;

    // 「今」を固定して判定する
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 6, 4, 12, 0, 0);

    // ===== ヘルパー =====

    private PaymentProxyGrantEntity.PaymentProxyGrantEntityBuilder activeGrantBuilder() {
        return PaymentProxyGrantEntity.builder()
                .organizationId(ORG_ID)
                .beneficiaryUserId(BENEFICIARY_ID)
                .payerUserId(PAYER_ID)
                .paymentItemId(ITEM_ID)
                .status(PaymentProxyGrantStatus.ACTIVE)
                .effectiveFrom(NOW.minusDays(30))
                .effectiveUntil(NOW.plusDays(30))
                .grantedVia(PaymentProxyGrantedVia.IN_APP);
    }

    // ===== T5: UUIDv7 主キーの永続化・取得 =====

    @Nested
    @DisplayName("T5-a: UUIDv7 主キーと全フィールドの永続化・取得")
    class T5a_Persistence {

        @Test
        @DisplayName("save して flush/clear 後に findById で取得できる（UUIDv7 主キー往復）")
        void persist_andFindById_roundTrip() {
            PaymentProxyGrantEntity entity = activeGrantBuilder().build();

            proxyGrantRepository.save(entity);
            em.flush();
            em.clear();

            assertThat(entity.getId()).isNotNull();

            Optional<PaymentProxyGrantEntity> found = proxyGrantRepository.findById(entity.getId());
            assertThat(found).isPresent();
            assertThat(found.get().getBeneficiaryUserId()).isEqualTo(BENEFICIARY_ID);
            assertThat(found.get().getPayerUserId()).isEqualTo(PAYER_ID);
            assertThat(found.get().getPaymentItemId()).isEqualTo(ITEM_ID);
            assertThat(found.get().getStatus()).isEqualTo(PaymentProxyGrantStatus.ACTIVE);
            assertThat(found.get().getGrantedVia()).isEqualTo(PaymentProxyGrantedVia.IN_APP);
        }

        @Test
        @DisplayName("revoke() を呼ぶと status が REVOKED・revokedAt・revokedBy が設定される")
        void revoke_setsRevokedFields() {
            PaymentProxyGrantEntity entity = activeGrantBuilder().build();
            proxyGrantRepository.save(entity);
            em.flush();

            entity.revoke(999L);
            proxyGrantRepository.save(entity);
            em.flush();
            em.clear();

            PaymentProxyGrantEntity found = proxyGrantRepository.findById(entity.getId()).orElseThrow();
            assertThat(found.getStatus()).isEqualTo(PaymentProxyGrantStatus.REVOKED);
            assertThat(found.getRevokedBy()).isEqualTo(999L);
            assertThat(found.getRevokedAt()).isNotNull();
        }
    }

    // ===== T5: findActiveGrant の ACTIVE×有効期間ゲート =====

    @Nested
    @DisplayName("T5-b: findActiveGrant — ACTIVE×有効期間ゲート引き当て")
    class T5b_FindActiveGrant {

        @Test
        @DisplayName("ACTIVE かつ有効期間内の grant が存在する場合は取得できる")
        void findActiveGrant_activeInPeriod_found() {
            proxyGrantRepository.save(activeGrantBuilder().build());
            em.flush();
            em.clear();

            Optional<PaymentProxyGrantEntity> result = proxyGrantRepository.findActiveGrant(
                    BENEFICIARY_ID, PAYER_ID, ITEM_ID, PaymentProxyGrantStatus.ACTIVE, NOW);

            assertThat(result).isPresent();
            assertThat(result.get().getBeneficiaryUserId()).isEqualTo(BENEFICIARY_ID);
        }

        @Test
        @DisplayName("REVOKED な grant は取得しない")
        void findActiveGrant_revokedGrant_notFound() {
            PaymentProxyGrantEntity entity = activeGrantBuilder()
                    .status(PaymentProxyGrantStatus.REVOKED)
                    .build();
            proxyGrantRepository.save(entity);
            em.flush();
            em.clear();

            Optional<PaymentProxyGrantEntity> result = proxyGrantRepository.findActiveGrant(
                    BENEFICIARY_ID, PAYER_ID, ITEM_ID, PaymentProxyGrantStatus.ACTIVE, NOW);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("effectiveUntil が過去（期限切れ）の grant は取得しない")
        void findActiveGrant_expiredGrant_notFound() {
            PaymentProxyGrantEntity entity = activeGrantBuilder()
                    .effectiveFrom(NOW.minusDays(60))
                    .effectiveUntil(NOW.minusDays(1))  // 昨日で期限切れ
                    .build();
            proxyGrantRepository.save(entity);
            em.flush();
            em.clear();

            Optional<PaymentProxyGrantEntity> result = proxyGrantRepository.findActiveGrant(
                    BENEFICIARY_ID, PAYER_ID, ITEM_ID, PaymentProxyGrantStatus.ACTIVE, NOW);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("effectiveFrom が未来（まだ有効でない）の grant は取得しない")
        void findActiveGrant_futureGrant_notFound() {
            PaymentProxyGrantEntity entity = activeGrantBuilder()
                    .effectiveFrom(NOW.plusDays(1))  // 明日から有効
                    .effectiveUntil(NOW.plusDays(60))
                    .build();
            proxyGrantRepository.save(entity);
            em.flush();
            em.clear();

            Optional<PaymentProxyGrantEntity> result = proxyGrantRepository.findActiveGrant(
                    BENEFICIARY_ID, PAYER_ID, ITEM_ID, PaymentProxyGrantStatus.ACTIVE, NOW);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("effectiveUntil=NULL の無期限 grant も取得できる")
        void findActiveGrant_noExpiry_found() {
            // payment_item_id が指定されている特定 grant の場合は effectiveUntil=NULL 可
            PaymentProxyGrantEntity entity = activeGrantBuilder()
                    .effectiveUntil(null)  // 無期限
                    .build();
            proxyGrantRepository.save(entity);
            em.flush();
            em.clear();

            Optional<PaymentProxyGrantEntity> result = proxyGrantRepository.findActiveGrant(
                    BENEFICIARY_ID, PAYER_ID, ITEM_ID, PaymentProxyGrantStatus.ACTIVE, NOW);

            assertThat(result).isPresent();
        }

        @Test
        @DisplayName("包括 grant（paymentItemId=NULL）は任意の項目に対してマッチする")
        void findActiveGrant_blanketGrant_matchesAnyItem() {
            Long otherItemId = 999L;

            // 包括 grant（paymentItemId=NULL）：包括 grant は effectiveUntil が必須（DDL CHECK）
            PaymentProxyGrantEntity blanketGrant = PaymentProxyGrantEntity.builder()
                    .organizationId(ORG_ID)
                    .beneficiaryUserId(BENEFICIARY_ID)
                    .payerUserId(PAYER_ID)
                    .paymentItemId(null)  // 包括 grant
                    .status(PaymentProxyGrantStatus.ACTIVE)
                    .effectiveFrom(NOW.minusDays(10))
                    .effectiveUntil(NOW.plusDays(10))  // 包括 grant は必須
                    .grantedVia(PaymentProxyGrantedVia.INVITE_TOKEN)
                    .build();
            proxyGrantRepository.save(blanketGrant);
            em.flush();
            em.clear();

            // 別の item ID で検索しても包括 grant がヒットする
            Optional<PaymentProxyGrantEntity> result = proxyGrantRepository.findActiveGrant(
                    BENEFICIARY_ID, PAYER_ID, otherItemId, PaymentProxyGrantStatus.ACTIVE, NOW);

            assertThat(result).isPresent();
            assertThat(result.get().getPaymentItemId()).isNull();
        }

        @Test
        @DisplayName("受益者・払い手・項目が一致しなければ取得しない")
        void findActiveGrant_mismatchedKeys_notFound() {
            proxyGrantRepository.save(activeGrantBuilder().build());
            em.flush();
            em.clear();

            // 別の受益者
            assertThat(proxyGrantRepository.findActiveGrant(
                    999L, PAYER_ID, ITEM_ID, PaymentProxyGrantStatus.ACTIVE, NOW)).isEmpty();
            // 別の払い手
            assertThat(proxyGrantRepository.findActiveGrant(
                    BENEFICIARY_ID, 999L, ITEM_ID, PaymentProxyGrantStatus.ACTIVE, NOW)).isEmpty();
            // 別の項目（包括 grant がない場合）
            assertThat(proxyGrantRepository.findActiveGrant(
                    BENEFICIARY_ID, PAYER_ID, 999L, PaymentProxyGrantStatus.ACTIVE, NOW)).isEmpty();
        }
    }

    // ===== T5: findByStatusAndEffectiveUntilBefore（日次バッチ用）=====

    @Nested
    @DisplayName("T5-c: findByStatusAndEffectiveUntilBefore — 期限切れ掃き当て")
    class T5c_FindExpired {

        @Test
        @DisplayName("effective_until が閾値より前の ACTIVE grant を取得できる")
        void findByStatusAndEffectiveUntilBefore_expiredActive_found() {
            PaymentProxyGrantEntity expired = activeGrantBuilder()
                    .effectiveUntil(NOW.minusHours(1))  // 1時間前に期限切れ
                    .build();
            PaymentProxyGrantEntity valid = activeGrantBuilder()
                    .beneficiaryUserId(BENEFICIARY_ID + 1)
                    .effectiveUntil(NOW.plusDays(10))
                    .build();
            proxyGrantRepository.save(expired);
            proxyGrantRepository.save(valid);
            em.flush();
            em.clear();

            List<PaymentProxyGrantEntity> results = proxyGrantRepository
                    .findByStatusAndEffectiveUntilBefore(PaymentProxyGrantStatus.ACTIVE, NOW);

            assertThat(results).hasSize(1);
            assertThat(results.get(0).getBeneficiaryUserId()).isEqualTo(BENEFICIARY_ID);
        }
    }
}
