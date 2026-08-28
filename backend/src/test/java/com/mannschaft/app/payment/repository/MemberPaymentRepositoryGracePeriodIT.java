package com.mannschaft.app.payment.repository;

import com.mannschaft.app.payment.PaymentItemType;
import com.mannschaft.app.payment.PaymentMethod;
import com.mannschaft.app.payment.PaymentStatus;
import com.mannschaft.app.payment.entity.MemberPaymentEntity;
import com.mannschaft.app.payment.entity.PaymentItemEntity;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F08.2 会費ゲートの valid_until / grace_period_days 境界を native SQL で検証する。
 */
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("MemberPaymentRepository grace期間境界IT")
class MemberPaymentRepositoryGracePeriodIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MemberPaymentRepository paymentRepository;

    @Autowired
    private com.mannschaft.app.payment.repository.PaymentItemRepository itemRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private PaymentItemEntity item(short grace) {
        return itemRepository.save(PaymentItemEntity.builder()
                .name("grace-test-" + grace + "-" + System.nanoTime())
                .type(PaymentItemType.MONTHLY_FEE)
                .amount(new BigDecimal("100.00"))
                .currency("JPY")
                .gracePeriodDays(grace)
                .build());
    }

    private void payment(long userId, PaymentItemEntity item, PaymentStatus status, LocalDate until) {
        paymentRepository.save(MemberPaymentEntity.builder()
                .userId(userId)
                .paymentItemId(item.getId())
                .amountPaid(new BigDecimal("100.00"))
                .paymentMethod(PaymentMethod.STRIPE)
                .status(status)
                .validFrom(LocalDate.now().minusDays(30))
                .validUntil(until)
                .build());
    }

    @Test
    @DisplayName("null、grace0当日、grace3最終日は有効、翌日と無効status・削除itemは無効")
    void validUntilAndGraceBoundaries() {
        LocalDate today = jdbcTemplate.queryForObject("SELECT CURRENT_DATE", LocalDate.class);
        PaymentItemEntity forever = item((short) 0);
        PaymentItemEntity grace0 = item((short) 0);
        PaymentItemEntity grace3 = item((short) 3);
        PaymentItemEntity grace3Expired = item((short) 3);
        PaymentItemEntity pending = item((short) 3);
        PaymentItemEntity cancelled = item((short) 3);
        PaymentItemEntity refunded = item((short) 3);
        PaymentItemEntity deleted = item((short) 3);
        deleted.softDelete();
        itemRepository.save(deleted);

        payment(91001L, forever, PaymentStatus.PAID, null);
        payment(91002L, grace0, PaymentStatus.PAID, today);
        payment(91003L, grace3, PaymentStatus.PAID, today.minusDays(3));
        payment(91004L, grace3Expired, PaymentStatus.PAID, today.minusDays(4));
        payment(91005L, pending, PaymentStatus.PENDING, today);
        payment(91006L, cancelled, PaymentStatus.CANCELLED, today);
        payment(91007L, refunded, PaymentStatus.REFUNDED, today);
        payment(91008L, deleted, PaymentStatus.PAID, today);

        assertThat(paymentRepository.existsValidPaidPayment(91001L, forever.getId())).isTrue();
        assertThat(paymentRepository.existsValidPaidPayment(91002L, grace0.getId())).isTrue();
        assertThat(paymentRepository.existsValidPaidPayment(91003L, grace3.getId())).isTrue();
        assertThat(paymentRepository.existsValidPaidPayment(91004L, grace3Expired.getId())).isFalse();
        assertThat(paymentRepository.existsValidPaidPayment(91005L, pending.getId())).isFalse();
        assertThat(paymentRepository.existsValidPaidPayment(91006L, cancelled.getId())).isFalse();
        assertThat(paymentRepository.existsValidPaidPayment(91007L, refunded.getId())).isFalse();
        assertThat(paymentRepository.existsValidPaidPayment(91008L, deleted.getId())).isFalse();

        assertThat(paymentRepository.findValidPaidPaymentItemIds(91001L,
                List.of(forever.getId(), grace0.getId(), grace3.getId(), grace3Expired.getId())))
                .containsExactlyInAnyOrder(forever.getId());
    }
}
