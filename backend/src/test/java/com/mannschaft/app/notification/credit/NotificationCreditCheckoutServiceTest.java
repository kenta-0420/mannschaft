package com.mannschaft.app.notification.credit;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.notification.credit.dto.NotificationCreditCheckoutResponse;
import com.mannschaft.app.notification.credit.entity.NotificationCreditPackageEntity;
import com.mannschaft.app.notification.credit.entity.NotificationCreditPurchaseEntity;
import com.mannschaft.app.notification.credit.repository.NotificationCreditPackageRepository;
import com.mannschaft.app.notification.credit.repository.NotificationCreditPurchaseRepository;
import com.mannschaft.app.notification.credit.service.NotificationCreditCheckoutService;
import com.mannschaft.app.notification.credit.service.NotificationCreditService;
import com.mannschaft.app.payment.entity.StripeCustomerEntity;
import com.mannschaft.app.payment.repository.StripeCustomerRepository;
import com.mannschaft.app.payment.stripe.StripePaymentProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link NotificationCreditCheckoutService} の単体テスト。
 *
 * <p>主眼は「Checkout Session ID 後付け保存」が同一行 UPDATE になることの回帰防止。
 * 旧実装は save 済み（id 採番済み）の purchase を toBuilder().build() で作り直して再 save しており、
 * 継承フィールド id が引き継がれず id=null の新インスタンスを INSERT → idempotency_key の
 * UNIQUE 制約違反で 500 になる二重 save 構造だった。直接ミューテートに直したことを検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("NotificationCreditCheckoutService 単体テスト")
class NotificationCreditCheckoutServiceTest {

    @Mock
    private NotificationCreditPackageRepository packageRepository;

    @Mock
    private NotificationCreditPurchaseRepository purchaseRepository;

    @Mock
    private StripeCustomerRepository stripeCustomerRepository;

    @Mock
    private StripePaymentProvider stripePaymentProvider;

    @Mock
    private NotificationCreditService creditService;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private NotificationCreditCheckoutService service;

    @Test
    @DisplayName("回帰: Checkout作成時のsession id後付けが同一purchaseのUPDATEになる（toBuilderで二重INSERTしない）")
    void createCheckout_sessionId後付け_同一インスタンスUPDATE() {
        // given
        Long orgId = 1L;
        Long packageId = 10L;
        Long userId = 100L;

        NotificationCreditPackageEntity pkg = NotificationCreditPackageEntity.builder()
                .name("スタンダード 10万通")
                .credits(100_000L)
                .priceJpy(BigDecimal.valueOf(100_000))
                .stripePriceId("price_existing") // 既に Price 採番済み → 遅延生成をスキップ
                .build();
        given(packageRepository.findById(packageId)).willReturn(Optional.of(pkg));

        UserEntity user = UserEntity.builder().email("admin@example.com").build();
        given(userRepository.findById(userId)).willReturn(Optional.of(user));

        StripeCustomerEntity customer = StripeCustomerEntity.builder()
                .userId(userId)
                .stripeCustomerId("cus_123")
                .build();
        given(stripeCustomerRepository.findByUserId(userId)).willReturn(Optional.of(customer));

        // purchaseRepository.save は渡されたインスタンスをそのまま返す（id 採番済みの managed entity を模す）
        given(purchaseRepository.save(any(NotificationCreditPurchaseEntity.class)))
                .willAnswer(inv -> inv.getArgument(0));

        StripePaymentProvider.CheckoutSessionInfo sessionInfo =
                new StripePaymentProvider.CheckoutSessionInfo(
                        "cs_test_123", "https://checkout.stripe.test/cs_test_123",
                        LocalDateTime.now().plusHours(1));
        given(stripePaymentProvider.createNotificationCreditCheckoutSession(
                anyString(), anyString(), any(), anyString(), anyString()))
                .willReturn(sessionInfo);

        // when
        NotificationCreditCheckoutResponse response = service.createCheckout(orgId, packageId, userId);

        // then: 戻り値が Stripe Session 情報を反映している
        assertThat(response.checkoutUrl()).isEqualTo("https://checkout.stripe.test/cs_test_123");
        assertThat(response.sessionId()).isEqualTo("cs_test_123");

        // save は2回（1回目: PENDING 作成、2回目: session id 後付け）呼ばれるが、
        // 2回とも「同一インスタンス」でなければならない。toBuilder().build() なら別インスタンス（id=null）
        // が2回目に渡り、idempotency_key の UNIQUE 制約違反で二重 INSERT → 500 になる。
        ArgumentCaptor<NotificationCreditPurchaseEntity> captor =
                ArgumentCaptor.forClass(NotificationCreditPurchaseEntity.class);
        verify(purchaseRepository, times(2)).save(captor.capture());

        NotificationCreditPurchaseEntity firstSaved = captor.getAllValues().get(0);
        NotificationCreditPurchaseEntity secondSaved = captor.getAllValues().get(1);
        // 2回とも同一インスタンス（=同一行 UPDATE）
        assertThat(secondSaved).isSameAs(firstSaved);
        // session id が in-place で書き込まれている
        assertThat(secondSaved.getStripeCheckoutSessionId()).isEqualTo("cs_test_123");
        // idempotencyKey は PENDING 作成時に採番されており、書き換わっていない（UNIQUE 維持）
        assertThat(secondSaved.getIdempotencyKey()).isNotNull();
        // 業務フィールドが維持されている（packageId は pkg.getId() 由来＝UT では未採番のため検証しない）
        assertThat(secondSaved.getOrganizationId()).isEqualTo(orgId);
        assertThat(secondSaved.getCreditsGranted()).isEqualTo(100_000L);
        assertThat(secondSaved.getPurchasedByUserId()).isEqualTo(userId);

        // クレジット加算は Webhook 完了時に行われるため、Checkout 作成時には呼ばれない
        verify(creditService, times(0)).addCredits(anyLong());
    }
}
