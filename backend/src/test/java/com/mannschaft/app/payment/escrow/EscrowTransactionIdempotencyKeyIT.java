package com.mannschaft.app.payment.escrow;

import com.mannschaft.app.payment.connect.ScopeKind;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F08.9 R2-2 結合テスト: {@code escrow_transactions.stripe_idempotency_key} 列（Flyway 適用）と
 * {@link EscrowTransactionRepository#findByStripeIdempotencyKey} を実スキーマ（Testcontainers MySQL）で検証する。
 *
 * <p>実機テスト(logs/f089-real-test-20260606.md R2-2)で、P5 継続課金（source_id=payment_item_id）と
 * P7 協会請求（source_id=team_id）が同じ MEMBERSHIP × source_id 値で衝突し、P7 が P5 の escrow を誤再利用していた。
 * 本 IT は「source_id 値が一致しても idempotencyKey が別なら別 escrow 行になる」ことと、idempotencyKey による
 * dedup（同一キーは 1 行）を実 DB の WHERE 句評価で保証する。</p>
 */
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("escrow_transactions idempotencyKey 名前空間分離 結合テスト（R2-2）")
class EscrowTransactionIdempotencyKeyIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private EscrowTransactionRepository repository;

    private static final UUID PAYEE_ACCOUNT_ID = UUID.fromString("019607a0-0000-7000-8000-0000000000aa");

    /** 即時 charge（会費 MEMBERSHIP/AUTOMATIC）相当の escrow を 1 行作る。 */
    private EscrowTransactionEntity membershipEscrow(long sourceId, long faceAmount, String idempotencyKey,
                                                     String piId) {
        return EscrowTransactionEntity.builder()
                .sourceKind(EscrowSourceKind.MEMBERSHIP)
                .captureMode(EscrowCaptureMode.AUTOMATIC)
                .sourceId(sourceId)
                .sourceParticipantId(null)
                .payerScopeKind(ScopeKind.USER)
                .payerScopeId(999L)
                .payerStripeCustomerId("cus_test")
                .payeeKind(ScopeKind.TEAM)
                .payeeConnectAccountId(PAYEE_ACCOUNT_ID)
                .organizationId(55L)
                .faceAmount(faceAmount)
                .amount(faceAmount + Math.round(faceAmount * 0.025))
                .currency("JPY")
                .applicationFeeAmount(Math.round(faceAmount * 0.05))
                .feePolicyKey("DEFAULT")
                .status(EscrowStatus.AUTHORIZED)
                .stripePaymentIntentId(piId)
                .stripeIdempotencyKey(idempotencyKey)
                .build();
    }

    @Test
    @DisplayName("source_id 値が一致(P5 item=1 / P7 team=1)でも idempotencyKey が別なら別 escrow 行になる")
    void sameSourceIdDifferentKey_distinctRows() {
        // P5 会費（source_id=payment_item_id=1・1,000 円）と P7 協会請求（source_id=team_id=1・5,000 円）。
        EscrowTransactionEntity p5 = repository.save(
                membershipEscrow(1L, 1_000L, "idem-P5-membership", "pi_p5_test"));
        EscrowTransactionEntity p7 = repository.save(
                membershipEscrow(1L, 5_000L, "idem-P7-billing", "pi_p7_test"));

        // 別 ID（別行）として永続化される（誤再利用しない）。
        assertThat(p5.getId()).isNotEqualTo(p7.getId());

        // idempotencyKey で正しく逆引きでき、金額（=取引）が混線しない。
        Optional<EscrowTransactionEntity> foundP5 = repository.findByStripeIdempotencyKey("idem-P5-membership");
        Optional<EscrowTransactionEntity> foundP7 = repository.findByStripeIdempotencyKey("idem-P7-billing");
        assertThat(foundP5).isPresent();
        assertThat(foundP7).isPresent();
        assertThat(foundP5.get().getId()).isEqualTo(p5.getId());
        assertThat(foundP5.get().getFaceAmount()).isEqualTo(1_000L);
        assertThat(foundP7.get().getId()).isEqualTo(p7.getId());
        assertThat(foundP7.get().getFaceAmount()).isEqualTo(5_000L);

        // 旧キー（source_kind × source_id）では両者が混ざる（衝突の証跡）が、idempotencyKey 経路は分離されている。
        assertThat(repository.findBySourceKindAndSourceId(EscrowSourceKind.MEMBERSHIP, 1L)).hasSize(2);
    }

    @Test
    @DisplayName("同一 idempotencyKey の事前チェックは 1 行を返す（二重送信 dedup）")
    void sameKey_dedup() {
        repository.save(membershipEscrow(1L, 1_000L, "idem-dedup-001", "pi_dedup_test"));

        Optional<EscrowTransactionEntity> found = repository.findByStripeIdempotencyKey("idem-dedup-001");
        assertThat(found).isPresent();
        assertThat(found.get().getStripeIdempotencyKey()).isEqualTo("idem-dedup-001");
    }
}
