package com.mannschaft.app.advertising.service;

import com.mannschaft.app.advertising.entity.AdCampaignEntity;
import com.mannschaft.app.advertising.entity.AdDailyStatsEntity;
import com.mannschaft.app.advertising.entity.AdInvoiceEntity;
import com.mannschaft.app.advertising.entity.AdvertiserAccountEntity;
import com.mannschaft.app.advertising.repository.AdCampaignRepository;
import com.mannschaft.app.advertising.repository.AdDailyStatsRepository;
import com.mannschaft.app.advertising.repository.AdInvoiceItemRepository;
import com.mannschaft.app.advertising.repository.AdInvoiceRepository;
import com.mannschaft.app.advertising.repository.AdvertiserAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * F08.12 §5.0「後払い（請求書方式）の廃止」に伴う、広告費請求書の発行契機の試練（red）。
 *
 * <p><strong>設計段階で摘出した実バグ:</strong>
 * {@code MonthlyInvoiceBatchService} は {@code savedInvoice.issue()} を
 * {@code BillingMethod.INVOICE} の分岐でしか呼んでおらず、STRIPE 方式の請求書は
 * DRAFT のまま残る。そこへ {@code invoice.paid} webhook 由来の {@code markPaid()} が来ると
 * {@link IllegalStateException} になる。運営領収書の発行契機がこの {@code markPaid()} で
 * あるため、これを直さない限り領収書は 1 通も発行されない。
 *
 * <p>外部境界である Stripe SDK のみモック化し、請求書エンティティの状態遷移は実物で観測する。
 *
 * <p>対応する受け入れ条件: AC-62 / AC-63 / AC-64 / AC-65 / AC-10。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("F08.12 月次請求書発行（後払い廃止に伴う発行契機）契約テスト")
class MonthlyInvoiceIssueContractTest {

    private static final YearMonth TARGET_MONTH = YearMonth.of(2026, 8);

    @Mock private AdvertiserAccountRepository advertiserAccountRepository;
    @Mock private AdCampaignRepository adCampaignRepository;
    @Mock private AdDailyStatsRepository adDailyStatsRepository;
    @Mock private AdInvoiceRepository adInvoiceRepository;
    @Mock private AdInvoiceItemRepository adInvoiceItemRepository;

    @InjectMocks private MonthlyInvoiceBatchService service;

    private AdvertiserAccountEntity stripeAccount;
    private AdInvoiceEntity savedInvoice;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "taxRate", new BigDecimal("10.00"));

        stripeAccount = AdvertiserAccountEntity.builder()
                .companyName("株式会社テスト広告主")
                .contactEmail("adv@example.com")
                .billingMethod(com.mannschaft.app.advertising.BillingMethod.STRIPE)
                .stripeCustomerId("cus_test_0001")
                .build();
        ReflectionTestUtils.setField(stripeAccount, "id", 100L);

        AdCampaignEntity campaign = AdCampaignEntity.builder()
                .advertiserAccountId(100L)
                .name("日本語キャンペーン名")
                .build();
        ReflectionTestUtils.setField(campaign, "id", 200L);

        AdDailyStatsEntity stats = AdDailyStatsEntity.builder()
                .campaignId(200L)
                .date(LocalDate.of(2026, 8, 10))
                .impressions(1000L)
                .clicks(10L)
                .cost(new BigDecimal("10005"))
                .build();
        ReflectionTestUtils.setField(stats, "id", 300L);

        when(advertiserAccountRepository.findByStatus(any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(stripeAccount)));
        when(adCampaignRepository.findByAdvertiserAccountId(100L)).thenReturn(List.of(campaign));
        when(adDailyStatsRepository.findByCampaignIdsAndDateBetween(anyList(), any(), any()))
                .thenReturn(List.of(stats));
        when(adInvoiceRepository.findByAdvertiserAccountIdAndInvoiceMonth(any(), any()))
                .thenReturn(Optional.empty());
        when(adInvoiceRepository.count()).thenReturn(0L);
        when(adInvoiceRepository.save(any(AdInvoiceEntity.class))).thenAnswer(inv -> {
            savedInvoice = inv.getArgument(0);
            if (ReflectionTestUtils.getField(savedInvoice, "id") == null) {
                ReflectionTestUtils.setField(savedInvoice, "id", 400L);
            }
            return savedInvoice;
        });
    }

    /**
     * 外部境界（Stripe SDK）だけをスタブし、finalize が成功する状況でバッチを走らせる。
     */
    private void runBatchWithStripeSucceeding() {
        com.stripe.model.Invoice stripeInvoice = mock(com.stripe.model.Invoice.class);
        try (var invoiceStatic = mockStatic(com.stripe.model.Invoice.class);
             var itemStatic = mockStatic(com.stripe.model.InvoiceItem.class)) {
            when(stripeInvoice.getId()).thenReturn("in_test_0001");
            try {
                when(stripeInvoice.finalizeInvoice()).thenReturn(stripeInvoice);
            } catch (Exception ignored) {
                // モック定義時に実際の例外は発生しない
            }
            invoiceStatic.when(() -> com.stripe.model.Invoice.create(any(com.stripe.param.InvoiceCreateParams.class)))
                    .thenReturn(stripeInvoice);
            itemStatic.when(() -> com.stripe.model.InvoiceItem.create(any(com.stripe.param.InvoiceItemCreateParams.class)))
                    .thenReturn(mock(com.stripe.model.InvoiceItem.class));

            service.generateMonthlyInvoices(TARGET_MONTH);
        }
    }

    /**
     * 外部境界（Stripe SDK）が失敗する状況でバッチを走らせる。
     */
    private void runBatchWithStripeFailing() {
        try (var invoiceStatic = mockStatic(com.stripe.model.Invoice.class)) {
            invoiceStatic.when(() -> com.stripe.model.Invoice.create(any(com.stripe.param.InvoiceCreateParams.class)))
                    .thenThrow(new RuntimeException("Stripe unavailable"));
            service.generateMonthlyInvoices(TARGET_MONTH);
        }
    }

    @Test
    @DisplayName("AC-62: STRIPE 方式でも finalize 成功後に status = ISSUED になる（現状 DRAFT のまま）")
    void ac62_stripeInvoiceBecomesIssued() {
        runBatchWithStripeSucceeding();

        assertThat(savedInvoice).as("請求書が保存されていること").isNotNull();
        assertThat(savedInvoice.getStatus())
                .as("issue() が INVOICE 分岐でしか呼ばれていないため、STRIPE 方式は DRAFT のまま残る")
                .isEqualTo(com.mannschaft.app.advertising.InvoiceStatus.ISSUED);
    }

    @Test
    @DisplayName("AC-63: 上記の請求書に markPaid() が来ても IllegalStateException にならない（領収書の発行契機）")
    void ac63_markPaidSucceedsAfterBatch() {
        runBatchWithStripeSucceeding();

        assertThat(savedInvoice).isNotNull();
        assertThatCode(() -> savedInvoice.markPaid(LocalDateTime.now(), "invoice.paid webhook"))
                .as("DRAFT のままだと markPaid() が IllegalStateException を投げ、"
                        + "運営領収書が 1 通も発行されない")
                .doesNotThrowAnyException();
        assertThat(savedInvoice.getStatus())
                .isEqualTo(com.mannschaft.app.advertising.InvoiceStatus.PAID);
    }

    @Test
    @DisplayName("AC-64: Stripe への送信が失敗したら請求書は DRAFT のまま残り、ISSUED に進めない")
    void ac64_stripeFailureKeepsDraft() {
        runBatchWithStripeFailing();

        assertThat(savedInvoice).isNotNull();
        assertThat(savedInvoice.getStatus())
                .as("送信失敗時に ISSUED へ進めてしまうと、入金の来ない請求書が発行済みとして残る")
                .isEqualTo(com.mannschaft.app.advertising.InvoiceStatus.DRAFT);
    }

    @Test
    @DisplayName("AC-65: いずれの方式でも due_date が新規に設定されない（後払い廃止）")
    void ac65_dueDateIsNotSet() {
        runBatchWithStripeSucceeding();

        assertThat(savedInvoice).isNotNull();
        assertThat(savedInvoice.getDueDate())
                .as("due_date を設定していた唯一の経路が INVOICE 分岐だったため、廃止後は設定されない")
                .isNull();
    }

    @Test
    @DisplayName("AC-10: 自社 DB の税額計算が HALF_UP であり、Stripe 送信額と 1 円ズレない")
    void ac10_taxRoundingIsHalfUpAndMatchesStripe() {
        runBatchWithStripeSucceeding();

        assertThat(savedInvoice).isNotNull();

        BigDecimal subtotal = new BigDecimal("10005");
        BigDecimal expectedTax = subtotal.multiply(new BigDecimal("10.00"))
                .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP);

        assertThat(savedInvoice.getTaxAmount())
                .as("現状は RoundingMode.FLOOR で計算しており、Stripe へ送る HALF_UP と 1 円ズレる（§5.3）")
                .isEqualByComparingTo(expectedTax);

        // Stripe へ送る金額（HALF_UP・円単位）と自社 DB の税抜合計が一致すること
        BigDecimal stripeAmount = subtotal.setScale(0, RoundingMode.HALF_UP);
        assertThat(savedInvoice.getTotalAmount()).isEqualByComparingTo(stripeAmount);
        assertThat(savedInvoice.getTotalWithTax())
                .isEqualByComparingTo(stripeAmount.add(expectedTax));
    }
}
