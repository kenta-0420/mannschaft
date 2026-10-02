package com.mannschaft.app.advertising.service;

import com.mannschaft.app.advertising.AdvertisingMapper;
import com.mannschaft.app.advertising.BillingMethod;
import com.mannschaft.app.advertising.dto.RegisterAdvertiserRequest;
import com.mannschaft.app.advertising.entity.AdvertiserAccountEntity;
import com.mannschaft.app.advertising.repository.AdvertiserAccountRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.payment.stripe.StripePaymentProvider;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F08.12 §5.0「広告費の後払い（請求書方式）を廃止する」の試練（red）。
 *
 * <p>後払いを廃止しても既存の {@code INVOICE} 行を壊さないことが要件であるため、
 * enum 値そのものは残したうえで<strong>新規登録の入口だけを閉じる</strong>。
 *
 * <p>対応する受け入れ条件: AC-59 / AC-60 / AC-61。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("F08.12 広告主請求 billingMethod=INVOICE 廃止契約テスト")
class AdvertiserInvoiceBillingAbolitionTest {

    @Mock private AdvertiserAccountRepository advertiserAccountRepository;
    @Mock private AdvertisingMapper advertisingMapper;
    @Mock private OrganizationRepository organizationRepository;
    @Mock private StripePaymentProvider stripePaymentProvider;

    @InjectMocks private AdvertiserAccountService service;

    @Test
    @DisplayName("AC-59: billingMethod = INVOICE の登録は拒否され、アカウントが作られない")
    void ac59_invoiceBillingMethodIsRejected() {
        when(advertiserAccountRepository.existsByScopeTypeAndScopeIdAndDeletedAtIsNull(any(), any()))
                .thenReturn(false);

        RegisterAdvertiserRequest request = new RegisterAdvertiserRequest(
                "株式会社テスト広告主", "adv@example.com", BillingMethod.INVOICE);

        assertThatThrownBy(() -> service.register(ScopeType.TEAM, 1L, request))
                .as("後払いは廃止されたため、新規登録で INVOICE を受け付けてはならない")
                .isInstanceOf(BusinessException.class);

        verify(advertiserAccountRepository, never()).save(any(AdvertiserAccountEntity.class));
    }

    @Test
    @DisplayName("AC-60: billingMethod を省略した登録は STRIPE で作られる（既定値）")
    void ac60_omittedBillingMethodDefaultsToStripe() {
        when(advertiserAccountRepository.existsByScopeTypeAndScopeIdAndDeletedAtIsNull(any(), any()))
                .thenReturn(false);
        when(advertiserAccountRepository.save(any(AdvertiserAccountEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        RegisterAdvertiserRequest request = new RegisterAdvertiserRequest(
                "株式会社テスト広告主", "adv@example.com", null);

        // DTO のバリデーションで落ちないこと（現状 billingMethod は @NotNull）
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            assertThat(validator.validate(request))
                    .as("後払い廃止後は決済方式の選択自体が無くなるため、billingMethod は省略可能でなければならない")
                    .isEmpty();
        }

        service.register(ScopeType.TEAM, 1L, request);

        ArgumentCaptor<AdvertiserAccountEntity> captor =
                ArgumentCaptor.forClass(AdvertiserAccountEntity.class);
        verify(advertiserAccountRepository).save(captor.capture());
        assertThat(captor.getValue().getBillingMethod())
                .as("省略時は STRIPE（既定値）で作られること。null のまま保存してはならない")
                .isEqualTo(BillingMethod.STRIPE);
    }

    @Test
    @DisplayName("AC-61: 既存の INVOICE アカウントは enum として生き続け、読み出しで壊れない")
    void ac61_existingInvoiceAccountsStillReadable() {
        assertThat(BillingMethod.valueOf("INVOICE"))
                .as("既存データを壊さないため enum 値そのものは残す")
                .isEqualTo(BillingMethod.INVOICE);

        AdvertiserAccountEntity existing = AdvertiserAccountEntity.builder()
                .scopeType(ScopeType.TEAM)
                .scopeId(1L)
                .companyName("既存の後払い広告主")
                .contactEmail("legacy@example.com")
                .billingMethod(BillingMethod.INVOICE)
                .build();

        assertThat(existing.getBillingMethod()).isEqualTo(BillingMethod.INVOICE);
    }
}
