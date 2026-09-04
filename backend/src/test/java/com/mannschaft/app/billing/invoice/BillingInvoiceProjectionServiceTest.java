package com.mannschaft.app.billing.invoice;

import com.mannschaft.app.billing.BillingContractEntity;
import com.mannschaft.app.billing.BillingContractRepository;
import com.mannschaft.app.billing.ContractKind;
import com.mannschaft.app.billing.ContractStatus;
import com.mannschaft.app.billing.EntitlementScopeKind;
import com.mannschaft.app.billing.api.BillingCustomerEntity;
import com.mannschaft.app.billing.api.BillingCustomerJpaRepository;
import com.mannschaft.app.billing.api.BillingInvoiceJpaRepository;
import com.mannschaft.app.billing.api.BillingInvoiceLineJpaRepository;
import com.mannschaft.app.billing.invoice.StripeBillingObjectView.InvoiceLineView;
import com.mannschaft.app.billing.invoice.StripeBillingObjectView.InvoiceView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * F20.1 PR5: 投影サービスの契約テスト。
 *
 * <p>実 DB を使う IT では測れない 2 点をここで固定する。</p>
 * <ul>
 *   <li><b>AC-8</b>: {@code psp_subscription_ref} の DB 逆引きが外れたとき、Stripe Subscription の
 *       metadata を厳密照合してから紐付けること。実 Stripe API が要るためオフライン IT では書けない。</li>
 *   <li><b>AC-34</b>: 非 JPY を<b>永続化を試みる前に</b>弾くこと。DB の {@code CHECK (currency='JPY')} に
 *       任せると「投影されていない」という結果だけは同じでも、fail-closed を測ったことにならない。
 *       repository に一切触れないことを検証する。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("F20.1 PR5: BillingInvoiceProjectionService")
class BillingInvoiceProjectionServiceTest {

    private static final String CUSTOMER_REF = "cus_unit";
    private static final String SUBSCRIPTION_REF = "sub_unit";
    private static final long SCOPE_ID = 4_242L;

    @Mock
    private StripeBillingPayloadParser parser;
    @Mock
    private BillingCustomerJpaRepository billingCustomerRepository;
    @Mock
    private BillingContractRepository billingContractRepository;
    @Mock
    private BillingInvoiceJpaRepository invoiceRepository;
    @Mock
    private BillingInvoiceLineJpaRepository invoiceLineRepository;
    @Mock
    private StripeSubscriptionMetadataVerifier subscriptionMetadataVerifier;

    @InjectMocks
    private BillingInvoiceProjectionService service;

    private BillingCustomerEntity customer(UUID id) {
        BillingCustomerEntity c = BillingCustomerEntity.builder()
                .scopeKind(EntitlementScopeKind.USER)
                .scopeId(SCOPE_ID)
                .pspCustomerRef(CUSTOMER_REF)
                .status("ACTIVE")
                .build();
        c.setId(id);
        return c;
    }

    private BillingContractEntity contract(UUID id, long scopeId) {
        BillingContractEntity ct = BillingContractEntity.builder()
                .scopeKind(EntitlementScopeKind.USER)
                .scopeId(scopeId)
                .contractKind(ContractKind.PLAN)
                .planKey("BASIC")
                .status(ContractStatus.ACTIVE)
                .version(0L)
                .contractedAt(LocalDateTime.of(2026, 1, 1, 0, 0))
                .createdAt(LocalDateTime.of(2026, 1, 1, 0, 0))
                .updatedAt(LocalDateTime.of(2026, 1, 1, 0, 0))
                .build();
        ct.setId(id);
        return ct;
    }

    private InvoiceView invoice(String currency, long subtotal, long discount, long tax, long total,
                                List<InvoiceLineView> lines) {
        return new InvoiceView("in_unit", CUSTOMER_REF, SUBSCRIPTION_REF, "open", "subscription_cycle",
                currency, subtotal, discount, tax, total, 1_767_225_600L, 1_769_904_000L,
                "請求先 太郎", "taro@example.com", "{\"country\":\"JP\"}", lines);
    }

    private InvoiceLineView line(long amount, long discount, long tax, Integer basisPoints, String taxName) {
        return new InvoiceLineView("il_unit", "BASIC プラン", new BigDecimal("1.000"),
                amount, discount, tax, false, basisPoints, taxName, null,
                1_767_225_600L, 1_769_904_000L);
    }

    @Nested
    @DisplayName("AC-8: subscription ref の DB 逆引きが外れたときの metadata 厳密照合")
    class SubscriptionMetadataBinding {

        @Test
        @DisplayName("metadata.billingContractId が同一 scope の契約を指すときだけ contractId を紐付ける")
        void metadataが同一scopeの契約を指すなら紐付ける() {
            UUID customerId = UUID.randomUUID();
            UUID contractId = UUID.randomUUID();
            when(billingCustomerRepository.findByPspCustomerRefAndDeletedAtIsNull(CUSTOMER_REF))
                    .thenReturn(Optional.of(customer(customerId)));
            when(billingContractRepository.findByPspSubscriptionRefAndDeletedAtIsNull(SUBSCRIPTION_REF))
                    .thenReturn(Optional.empty());
            when(subscriptionMetadataVerifier.resolveBillingContractId(SUBSCRIPTION_REF))
                    .thenReturn(Optional.of(contractId));
            when(billingContractRepository.findByIdAndDeletedAtIsNull(contractId))
                    .thenReturn(Optional.of(contract(contractId, SCOPE_ID)));

            Optional<BillingInvoiceOwner> owner = service.resolveOwner(
                    invoice("jpy", 1_000L, 0L, 100L, 1_100L, List.of(line(1_000L, 0L, 100L, 1000, "消費税"))));

            assertThat(owner).isPresent();
            assertThat(owner.get().contractId())
                    .as("metadata 厳密照合が通ったので契約に紐付く").isEqualTo(contractId);
        }

        @Test
        @DisplayName("metadata が無ければ契約に紐付けない（緩い一致で取り込まない）")
        void metadataが無ければ紐付けない() {
            UUID customerId = UUID.randomUUID();
            when(billingCustomerRepository.findByPspCustomerRefAndDeletedAtIsNull(CUSTOMER_REF))
                    .thenReturn(Optional.of(customer(customerId)));
            when(billingContractRepository.findByPspSubscriptionRefAndDeletedAtIsNull(SUBSCRIPTION_REF))
                    .thenReturn(Optional.empty());
            when(subscriptionMetadataVerifier.resolveBillingContractId(SUBSCRIPTION_REF))
                    .thenReturn(Optional.empty());

            Optional<BillingInvoiceOwner> owner = service.resolveOwner(
                    invoice("jpy", 1_000L, 0L, 100L, 1_100L, List.of(line(1_000L, 0L, 100L, 1000, "消費税"))));

            assertThat(owner).as("Customer は自 scope のものなので所有ではある").isPresent();
            assertThat(owner.get().contractId()).as("契約には紐付けない").isNull();
        }

        @Test
        @DisplayName("metadata が別 scope の契約を指していたら紐付けない（IDOR 防止）")
        void metadataが別scopeの契約を指すなら紐付けない() {
            UUID customerId = UUID.randomUUID();
            UUID foreignContractId = UUID.randomUUID();
            when(billingCustomerRepository.findByPspCustomerRefAndDeletedAtIsNull(CUSTOMER_REF))
                    .thenReturn(Optional.of(customer(customerId)));
            when(billingContractRepository.findByPspSubscriptionRefAndDeletedAtIsNull(SUBSCRIPTION_REF))
                    .thenReturn(Optional.empty());
            when(subscriptionMetadataVerifier.resolveBillingContractId(SUBSCRIPTION_REF))
                    .thenReturn(Optional.of(foreignContractId));
            when(billingContractRepository.findByIdAndDeletedAtIsNull(foreignContractId))
                    .thenReturn(Optional.of(contract(foreignContractId, SCOPE_ID + 1)));

            Optional<BillingInvoiceOwner> owner = service.resolveOwner(
                    invoice("jpy", 1_000L, 0L, 100L, 1_100L, List.of(line(1_000L, 0L, 100L, 1000, "消費税"))));

            assertThat(owner.orElseThrow().contractId())
                    .as("別 scope の契約には紐付けない").isNull();
        }

        @Test
        @DisplayName("DB 逆引きがヒットしたら Stripe へ問い合わせない（不要な API 呼び出しをしない）")
        void DB逆引きが当たれば問い合わせない() {
            UUID customerId = UUID.randomUUID();
            UUID contractId = UUID.randomUUID();
            when(billingCustomerRepository.findByPspCustomerRefAndDeletedAtIsNull(CUSTOMER_REF))
                    .thenReturn(Optional.of(customer(customerId)));
            when(billingContractRepository.findByPspSubscriptionRefAndDeletedAtIsNull(SUBSCRIPTION_REF))
                    .thenReturn(Optional.of(contract(contractId, SCOPE_ID)));

            Optional<BillingInvoiceOwner> owner = service.resolveOwner(
                    invoice("jpy", 1_000L, 0L, 100L, 1_100L, List.of(line(1_000L, 0L, 100L, 1000, "消費税"))));

            assertThat(owner.orElseThrow().contractId()).isEqualTo(contractId);
            verifyNoInteractions(subscriptionMetadataVerifier);
        }
    }

    @Nested
    @DisplayName("AC-34/5/39: fail-closed は永続化を試みる前に効く")
    class FailClosedBeforePersist {

        private final BillingInvoiceOwner owner =
                new BillingInvoiceOwner(UUID.randomUUID(), UUID.randomUUID(),
                        EntitlementScopeKind.USER, SCOPE_ID, null);

        @Test
        @DisplayName("AC-34: 非 JPY は DB 制約ではなく投影前の検証で拒否され、repository に一切触れない")
        void 非JPYは永続化を試みる前に拒否される() {
            InvoiceView usd = invoice("usd", 1_000L, 0L, 100L, 1_100L,
                    List.of(line(1_000L, 0L, 100L, 1000, "消費税")));

            assertThatThrownBy(() -> service.project(usd, owner, "invoice.finalized", 1_769_904_000L))
                    .isInstanceOf(BillingInvoiceProjectionRejectedException.class)
                    .hasMessageContaining("非 JPY");

            // DB の CHECK 制約に落とさせていたら、ここで findByPspInvoiceRef / save が呼ばれてしまう。
            verify(invoiceRepository, never()).findByPspInvoiceRef(anyString());
            verify(invoiceRepository, never()).saveAndFlush(any());
            verifyNoInteractions(invoiceLineRepository);
        }

        @Test
        @DisplayName("AC-5: 金額恒等式が破れていたら投影前に拒否される")
        void 恒等式の破れは投影前に拒否される() {
            InvoiceView broken = invoice("jpy", 10_000L, 500L, 950L, 99_999L,
                    List.of(line(10_000L, 500L, 950L, 1000, "消費税")));

            assertThatThrownBy(() -> service.project(broken, owner, "invoice.finalized", 1_769_904_000L))
                    .isInstanceOf(BillingInvoiceProjectionRejectedException.class);
            verify(invoiceRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("AC-39: 税額があるのに税率/税名の裏付けが無ければ投影前に拒否される")
        void 税の裏付け不在は投影前に拒否される() {
            InvoiceView noTaxRate = invoice("jpy", 1_000L, 0L, 100L, 1_100L,
                    List.of(line(1_000L, 0L, 100L, null, null)));

            assertThatThrownBy(() -> service.project(noTaxRate, owner, "invoice.finalized", 1_769_904_000L))
                    .isInstanceOf(BillingInvoiceProjectionRejectedException.class);
            verify(invoiceRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("陽性対照: 同じ形で値だけ正当な検体は検証を通過する（空虚な緑にしない）")
        void 正当な検体は検証を通過する() {
            InvoiceView valid = invoice("jpy", 1_000L, 0L, 100L, 1_100L,
                    List.of(line(1_000L, 0L, 100L, 1000, "消費税")));

            service.validate(valid);
        }
    }
}
