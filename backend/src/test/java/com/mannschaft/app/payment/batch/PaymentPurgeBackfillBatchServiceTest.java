package com.mannschaft.app.payment.batch;

import com.mannschaft.app.auth.UserConstants;
import com.mannschaft.app.payment.entity.StripeCustomerEntity;
import com.mannschaft.app.payment.repository.MemberPaymentRepository;
import com.mannschaft.app.payment.repository.StripeCustomerRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link PaymentPurgeBackfillBatchService} 単体テスト（Phase D-4）。
 *
 * <p>Repository 呼び出しの委譲・継続実行・例外ハンドリングを Mockito で検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentPurgeBackfillBatchService 単体テスト")
class PaymentPurgeBackfillBatchServiceTest {

    @Mock
    private MemberPaymentRepository memberPaymentRepository;

    @Mock
    private StripeCustomerRepository stripeCustomerRepository;

    @InjectMocks
    private PaymentPurgeBackfillBatchService batch;

    private static final Long SENTINEL = UserConstants.SENTINEL_USER_ID;

    // ─── 正常系 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("正常系: member_payments N 件補正・stripe_customers M 件削除")
    void backfill_正常_両方補正される() {
        StripeCustomerEntity sc1 = StripeCustomerEntity.builder()
                .id(1L).userId(10L).stripeCustomerId("cus_A").build();
        StripeCustomerEntity sc2 = StripeCustomerEntity.builder()
                .id(2L).userId(20L).stripeCustomerId("cus_B").build();

        given(memberPaymentRepository.anonymizeOrphanUserId(SENTINEL)).willReturn(5);
        given(stripeCustomerRepository.findOrphanStripeCustomers()).willReturn(List.of(sc1, sc2));

        batch.backfill();

        verify(memberPaymentRepository, times(1)).anonymizeOrphanUserId(SENTINEL);
        verify(stripeCustomerRepository, times(1)).findOrphanStripeCustomers();
        verify(stripeCustomerRepository, times(1)).delete(sc1);
        verify(stripeCustomerRepository, times(1)).delete(sc2);
    }

    @Test
    @DisplayName("正常系: 孤児 0 件のとき delete は呼ばれない")
    void backfill_正常_孤児0件() {
        given(memberPaymentRepository.anonymizeOrphanUserId(SENTINEL)).willReturn(0);
        given(stripeCustomerRepository.findOrphanStripeCustomers()).willReturn(List.of());

        batch.backfill();

        verify(memberPaymentRepository, times(1)).anonymizeOrphanUserId(SENTINEL);
        verify(stripeCustomerRepository, times(1)).findOrphanStripeCustomers();
        verify(stripeCustomerRepository, never()).delete(org.mockito.ArgumentMatchers.any());
    }

    // ─── 異常系 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("異常系: stripe_customers 削除が 1 件失敗しても他の件は継続削除される")
    void backfill_異常_1件削除失敗_継続する() {
        StripeCustomerEntity sc1 = StripeCustomerEntity.builder()
                .id(1L).userId(10L).stripeCustomerId("cus_A").build();
        StripeCustomerEntity sc2 = StripeCustomerEntity.builder()
                .id(2L).userId(20L).stripeCustomerId("cus_B").build();
        StripeCustomerEntity sc3 = StripeCustomerEntity.builder()
                .id(3L).userId(30L).stripeCustomerId("cus_C").build();

        given(memberPaymentRepository.anonymizeOrphanUserId(SENTINEL)).willReturn(2);
        given(stripeCustomerRepository.findOrphanStripeCustomers()).willReturn(List.of(sc1, sc2, sc3));
        willThrow(new RuntimeException("一時的なDB障害"))
                .given(stripeCustomerRepository).delete(sc2);

        assertThatCode(() -> batch.backfill()).doesNotThrowAnyException();

        verify(stripeCustomerRepository, times(1)).delete(sc1);
        verify(stripeCustomerRepository, times(1)).delete(sc2);
        verify(stripeCustomerRepository, times(1)).delete(sc3);
    }

    @Test
    @DisplayName("異常系: member_payments anonymize が失敗しても stripe_customers 補正は継続する")
    void backfill_異常_anonymize失敗_stripe補正継続() {
        StripeCustomerEntity sc1 = StripeCustomerEntity.builder()
                .id(1L).userId(10L).stripeCustomerId("cus_A").build();

        willThrow(new RuntimeException("DB error"))
                .given(memberPaymentRepository).anonymizeOrphanUserId(SENTINEL);
        given(stripeCustomerRepository.findOrphanStripeCustomers()).willReturn(List.of(sc1));

        assertThatCode(() -> batch.backfill()).doesNotThrowAnyException();

        verify(memberPaymentRepository, times(1)).anonymizeOrphanUserId(SENTINEL);
        verify(stripeCustomerRepository, times(1)).findOrphanStripeCustomers();
        verify(stripeCustomerRepository, times(1)).delete(sc1);
    }

    @Test
    @DisplayName("異常系: findOrphanStripeCustomers が失敗した場合は早期リターンし例外伝播しない")
    void backfill_異常_findOrphan失敗_早期リターン() {
        given(memberPaymentRepository.anonymizeOrphanUserId(SENTINEL)).willReturn(3);
        willThrow(new RuntimeException("クエリ失敗"))
                .given(stripeCustomerRepository).findOrphanStripeCustomers();

        assertThatCode(() -> batch.backfill()).doesNotThrowAnyException();

        verify(stripeCustomerRepository, times(1)).findOrphanStripeCustomers();
        verify(stripeCustomerRepository, never()).delete(org.mockito.ArgumentMatchers.any());
    }
}
