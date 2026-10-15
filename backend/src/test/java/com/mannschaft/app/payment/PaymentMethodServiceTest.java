package com.mannschaft.app.payment;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.payment.entity.StripeCustomerEntity;
import com.mannschaft.app.payment.repository.StripeCustomerRepository;
import com.mannschaft.app.payment.service.MembershipSubscriptionService;
import com.mannschaft.app.payment.service.PaymentMethodService;
import com.mannschaft.app.payment.stripe.StripePaymentProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * F08.9 P5 第二波: {@link PaymentMethodService} の単体テスト。
 *
 * <p>get-or-create Customer・SetupIntent 作成・attach＋default 焼付を検証する。
 * 残債2（Stripe Customer email 実メール化）の分岐（実メール解決・退会済み拒否）も本テストで検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentMethodService 単体テスト")
class PaymentMethodServiceTest {

    @Mock private StripeCustomerRepository stripeCustomerRepository;
    @Mock private StripePaymentProvider stripePaymentProvider;
    @Mock private MembershipSubscriptionService membershipSubscriptionService;

    @InjectMocks
    private PaymentMethodService service;

    private static final Long USER_ID = 100L;

    @Test
    @DisplayName("createSetupIntent: 既存 Customer を使い SetupIntent を作成（Customer 新規作成しない）")
    void 既存Customerで作成() {
        given(stripeCustomerRepository.findByUserId(USER_ID))
                .willReturn(Optional.of(StripeCustomerEntity.builder()
                        .userId(USER_ID).stripeCustomerId("cus_x").build()));
        given(stripePaymentProvider.createSetupIntent("cus_x"))
                .willReturn(new StripePaymentProvider.SetupIntentInfo("seti_1", "seti_secret", "requires_payment_method"));

        StripePaymentProvider.SetupIntentInfo info = service.createSetupIntent(USER_ID);

        assertThat(info.setupIntentId()).isEqualTo("seti_1");
        assertThat(info.clientSecret()).isEqualTo("seti_secret");
        verify(stripePaymentProvider, never()).createCustomer(any(), any());
        // 既存 Customer 経路ではユーザーメール解決は不要（get-or-create の get 経路）。
        verifyNoInteractions(membershipSubscriptionService);
    }

    @Test
    @DisplayName("残債2: createSetupIntent: Customer 不在なら実メールで get-or-create 新規作成する（プレースホルダ廃止）")
    void Customer新規作成_実メール() {
        given(stripeCustomerRepository.findByUserId(USER_ID)).willReturn(Optional.empty());
        given(membershipSubscriptionService.resolveEmailForStripeCustomer(USER_ID))
                .willReturn(Optional.of("real-user@example.co.jp"));
        given(stripePaymentProvider.createCustomer("real-user@example.co.jp", USER_ID)).willReturn("cus_new");
        given(stripeCustomerRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(stripePaymentProvider.createSetupIntent("cus_new"))
                .willReturn(new StripePaymentProvider.SetupIntentInfo("seti_2", "secret2", "requires_payment_method"));

        StripePaymentProvider.SetupIntentInfo info = service.createSetupIntent(USER_ID);

        assertThat(info.setupIntentId()).isEqualTo("seti_2");
        verify(stripePaymentProvider).createCustomer("real-user@example.co.jp", USER_ID);
        verify(stripePaymentProvider, never()).createCustomer(eq("user@example.com"), any());
    }

    @Test
    @DisplayName("残債2: 退会済み（メール解決不可）ユーザーは Customer 新規作成を拒否する（プレースホルダで通さない）")
    void 退会済みユーザーはCustomer作成拒否() {
        given(stripeCustomerRepository.findByUserId(USER_ID)).willReturn(Optional.empty());
        given(membershipSubscriptionService.resolveEmailForStripeCustomer(USER_ID))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> service.createSetupIntent(USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(PaymentErrorCode.STRIPE_CUSTOMER_TARGET_USER_WITHDRAWN);

        verify(stripePaymentProvider, never()).createCustomer(any(), any());
        verify(stripeCustomerRepository, never()).save(any());
    }

    @Test
    @DisplayName("confirmPaymentMethod: attach＋default 設定し default_payment_method を焼付")
    void confirmで焼付() {
        StripeCustomerEntity customer = StripeCustomerEntity.builder()
                .userId(USER_ID).stripeCustomerId("cus_x").build();
        given(stripeCustomerRepository.findByUserId(USER_ID)).willReturn(Optional.of(customer));
        given(stripeCustomerRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        StripeCustomerEntity result = service.confirmPaymentMethod(USER_ID, "pm_123");

        verify(stripePaymentProvider).attachPaymentMethodAndSetDefault("cus_x", "pm_123");
        ArgumentCaptor<StripeCustomerEntity> captor = ArgumentCaptor.forClass(StripeCustomerEntity.class);
        verify(stripeCustomerRepository).save(captor.capture());
        assertThat(captor.getValue().getDefaultPaymentMethod()).isEqualTo("pm_123");
        assertThat(result.getDefaultPaymentMethod()).isEqualTo("pm_123");
    }
}
