package com.mannschaft.app.payment.event;

import com.mannschaft.app.auth.UserConstants;
import com.mannschaft.app.gdpr.event.AccountPurgedEvent;
import com.mannschaft.app.payment.entity.StripeCustomerEntity;
import com.mannschaft.app.payment.repository.MemberPaymentRepository;
import com.mannschaft.app.payment.repository.StripeCustomerRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentPurgeEventListener 単体テスト")
class PaymentPurgeEventListenerTest {

    @Mock
    private MemberPaymentRepository memberPaymentRepository;

    @Mock
    private StripeCustomerRepository stripeCustomerRepository;

    @InjectMocks
    private PaymentPurgeEventListener listener;

    private static final Long USER_ID = 100L;

    @Test
    @DisplayName("正常系: センチネル差替と Stripe 顧客削除が両方呼ばれる")
    void 正常_両操作呼ばれる() {
        StripeCustomerEntity stripeCustomer = StripeCustomerEntity.builder()
                .id(1L)
                .userId(USER_ID)
                .stripeCustomerId("cus_test_123")
                .build();
        given(memberPaymentRepository.anonymizeUserId(USER_ID, UserConstants.SENTINEL_USER_ID))
                .willReturn(3);
        given(stripeCustomerRepository.findByUserId(USER_ID))
                .willReturn(Optional.of(stripeCustomer));

        listener.on(new AccountPurgedEvent(USER_ID, "hash"));

        verify(memberPaymentRepository).anonymizeUserId(USER_ID, UserConstants.SENTINEL_USER_ID);
        verify(stripeCustomerRepository).findByUserId(USER_ID);
        verify(stripeCustomerRepository).delete(stripeCustomer);
    }

    @Test
    @DisplayName("正常系: センチネル対象 0 件 + Stripe 顧客不在でも例外なく完了する")
    void 正常_両方0件() {
        given(memberPaymentRepository.anonymizeUserId(USER_ID, UserConstants.SENTINEL_USER_ID))
                .willReturn(0);
        given(stripeCustomerRepository.findByUserId(USER_ID))
                .willReturn(Optional.empty());

        listener.on(new AccountPurgedEvent(USER_ID, "hash"));

        verify(memberPaymentRepository).anonymizeUserId(USER_ID, UserConstants.SENTINEL_USER_ID);
        verify(stripeCustomerRepository).findByUserId(USER_ID);
        verify(stripeCustomerRepository, never()).delete(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("異常系: センチネル差替が失敗しても Stripe 顧客削除は継続実行され例外伝播しない")
    void 異常_センチネル失敗_Stripe削除継続() {
        StripeCustomerEntity stripeCustomer = StripeCustomerEntity.builder()
                .id(1L)
                .userId(USER_ID)
                .stripeCustomerId("cus_test_123")
                .build();
        willThrow(new RuntimeException("DB error"))
                .given(memberPaymentRepository).anonymizeUserId(USER_ID, UserConstants.SENTINEL_USER_ID);
        given(stripeCustomerRepository.findByUserId(USER_ID))
                .willReturn(Optional.of(stripeCustomer));

        assertThatCode(() -> listener.on(new AccountPurgedEvent(USER_ID, "hash")))
                .doesNotThrowAnyException();

        verify(memberPaymentRepository).anonymizeUserId(USER_ID, UserConstants.SENTINEL_USER_ID);
        verify(stripeCustomerRepository).findByUserId(USER_ID);
        verify(stripeCustomerRepository).delete(stripeCustomer);
    }

    @Test
    @DisplayName("異常系: Stripe 顧客 findByUserId が空の場合 delete は呼ばれない")
    void 異常_Stripe顧客不在_delete未呼出() {
        given(memberPaymentRepository.anonymizeUserId(USER_ID, UserConstants.SENTINEL_USER_ID))
                .willReturn(5);
        given(stripeCustomerRepository.findByUserId(USER_ID))
                .willReturn(Optional.empty());

        listener.on(new AccountPurgedEvent(USER_ID, "hash"));

        verify(stripeCustomerRepository).findByUserId(USER_ID);
        verify(stripeCustomerRepository, never()).delete(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("異常系: Stripe 顧客削除中の例外も伝播しない（センチネル化成功後に Stripe 削除が失敗するケース）")
    void 異常_Stripe削除失敗_例外伝播なし() {
        StripeCustomerEntity stripeCustomer = StripeCustomerEntity.builder()
                .id(1L)
                .userId(USER_ID)
                .stripeCustomerId("cus_test_123")
                .build();
        given(memberPaymentRepository.anonymizeUserId(USER_ID, UserConstants.SENTINEL_USER_ID))
                .willReturn(2);
        given(stripeCustomerRepository.findByUserId(USER_ID))
                .willReturn(Optional.of(stripeCustomer));
        willThrow(new RuntimeException("Stripe DB error"))
                .given(stripeCustomerRepository).delete(stripeCustomer);

        assertThatCode(() -> listener.on(new AccountPurgedEvent(USER_ID, "hash")))
                .doesNotThrowAnyException();

        verify(memberPaymentRepository).anonymizeUserId(USER_ID, UserConstants.SENTINEL_USER_ID);
        verify(stripeCustomerRepository).delete(stripeCustomer);
    }
}
