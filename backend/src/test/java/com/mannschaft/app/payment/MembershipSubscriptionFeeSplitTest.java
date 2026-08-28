package com.mannschaft.app.payment;

import com.mannschaft.app.payment.connect.ConnectAccountEntity;
import com.mannschaft.app.payment.connect.ConnectAccountRepository;
import com.mannschaft.app.payment.connect.ScopeKind;
import com.mannschaft.app.payment.entity.MembershipSubscriptionEntity;
import com.mannschaft.app.payment.entity.PaymentItemEntity;
import com.mannschaft.app.payment.entity.StripeCustomerEntity;
import com.mannschaft.app.payment.escrow.ConnectChargeService;
import com.mannschaft.app.payment.escrow.EscrowSourceKind;
import com.mannschaft.app.payment.escrow.EscrowStatus;
import com.mannschaft.app.payment.escrow.MembershipChargeCommand;
import com.mannschaft.app.payment.escrow.MembershipChargeResult;
import com.mannschaft.app.payment.repository.MembershipSubscriptionRepository;
import com.mannschaft.app.payment.repository.PaymentItemRepository;
import com.mannschaft.app.payment.repository.StripeCustomerRepository;
import com.mannschaft.app.payment.service.MemberPaymentService;
import com.mannschaft.app.payment.service.MembershipSubscriptionService;
import com.mannschaft.app.payment.service.PaymentAuthorizationService;
import com.mannschaft.app.payment.service.PaymentItemService;
import com.mannschaft.app.payment.stripe.StripePaymentProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * F08.9 P5 継続課金: <b>手数料折半の 2 サイクル目以降破綻</b>の根治を検証する試練（テスト先行）。
 *
 * <p><b>何が壊れていたか:</b> 初回サイクルは {@code ConnectChargeService.charge} が
 * {@link PaymentFeeCalculator} の {@code chargeAmount}（額面＋支払側折半）で PaymentIntent を作るため正しい。
 * ところが 2 サイクル目以降の Stripe Subscription は recurring Price を<b>額面のまま</b>作っており、
 * {@code invoice.created} で {@code application_fee_amount} を総手数料へ上書きするだけだったため、
 * 支払側への折半上乗せが invoice に加算されず<b>受取側が毎月 額面2.5% を余分に負担</b>していた。</p>
 *
 * <p><b>どう直すか（案C・2明細サブスク）:</b> Subscription に「会費 Price＝額面」と
 * 「手数料 Price＝{@code FeeBreakdown.payerFee}」の 2 明細を持たせ、invoice 合計を初回 PI と一致させる。
 * {@code payerFee == 0} のときは手数料 Price を作らない。</p>
 *
 * <p><b>{@link PaymentFeeCalculator} はモックせず実インスタンスを使う</b>（固定値モックでは金額一致の
 * 検証にならないため・正典の数式をそのまま突き合わせる）。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("継続課金 手数料折半（2サイクル目以降）の根治")
class MembershipSubscriptionFeeSplitTest {

    @Mock private MembershipSubscriptionRepository membershipSubscriptionRepository;
    @Mock private PaymentItemService paymentItemService;
    @Mock private PaymentItemRepository paymentItemRepository;
    @Mock private PaymentAuthorizationService paymentAuthorizationService;
    @Mock private ConnectAccountRepository connectAccountRepository;
    @Mock private ConnectChargeService connectChargeService;
    @Mock private FeePolicyResolver feePolicyResolver;
    @Mock private StripeCustomerRepository stripeCustomerRepository;
    @Mock private StripePaymentProvider stripePaymentProvider;
    @Mock private MemberPaymentService memberPaymentService;
    @Mock private com.mannschaft.app.auth.repository.UserRepository userRepository;

    /** 純粋関数なので実物を使う（モックで固定値を返すと「金額一致」の検証にならない）。 */
    private final PaymentFeeCalculator paymentFeeCalculator = new PaymentFeeCalculator();

    private MembershipSubscriptionService service;

    private static final Long ITEM_ID = 1L;
    private static final Long TEAM_ID = 50L;
    private static final Long ORG_ID = 7L;
    private static final Long BENEFICIARY = 100L;
    private static final Long PAYER = 100L;
    private static final String IDEMPOTENCY_KEY = "idem-sub-fee-001";
    private static final UUID ESCROW_ID = UUID.randomUUID();
    private static final UUID ACCOUNT_ID = UUID.randomUUID();
    private static final UUID SUB_ID = UUID.fromString("019607a0-0000-7000-8000-0000000000cc");

    /** 額面 10,000 円（設計書の代表例）。 */
    private static final long FACE = 10_000L;

    /** DEFAULT 相当（率5%＋固定0）。face=10,000 → totalFee=500 / payerFee=250 / charge=10,250 / transfer=9,750。 */
    private static final FeePolicy DEFAULT_POLICY = new FeePolicy("DEFAULT", new BigDecimal("0.05"), 0L);

    @BeforeEach
    void setUp() {
        service = new MembershipSubscriptionService(
                membershipSubscriptionRepository, paymentItemService, paymentItemRepository,
                paymentAuthorizationService, connectAccountRepository, connectChargeService,
                feePolicyResolver, stripeCustomerRepository, stripePaymentProvider,
                memberPaymentService, userRepository, paymentFeeCalculator);
    }

    private PaymentItemEntity recurringItem(long faceAmount) {
        return PaymentItemEntity.builder()
                .teamId(TEAM_ID)
                .organizationId(ORG_ID)
                .name("月会費")
                .type(PaymentItemType.MONTHLY_FEE)
                .amount(BigDecimal.valueOf(faceAmount))
                .currency("JPY")
                .isRecurring(true)
                .billingInterval(BillingInterval.MONTHLY)
                .build();
    }

    /** subscribe が最後まで通るようモックを揃える（手数料パターンは引数で差し替え）。 */
    private void stubSubscribeFlow(long faceAmount, FeePolicy policy) {
        given(paymentItemService.findByIdOrThrow(ITEM_ID)).willReturn(recurringItem(faceAmount));
        given(paymentAuthorizationService.authorizePayment(PAYER, BENEFICIARY, ITEM_ID, false))
                .willReturn(PayerRelationship.SELF);
        given(membershipSubscriptionRepository
                .existsByBeneficiaryUserIdAndPaymentItemIdAndStatusInAndDeletedAtIsNull(eq(BENEFICIARY), eq(ITEM_ID), any()))
                .willReturn(false);
        ConnectAccountEntity acc = ConnectAccountEntity.builder()
                .scopeKind(ScopeKind.TEAM).scopeId(TEAM_ID)
                .stripeAccountId("acct_ready").payoutsEnabled(true).chargesEnabled(true)
                .build();
        acc.setId(ACCOUNT_ID);
        given(connectAccountRepository.findByScopeKindAndScopeIdAndDeletedAtIsNull(ScopeKind.TEAM, TEAM_ID))
                .willReturn(Optional.of(acc));
        given(stripeCustomerRepository.findByUserId(PAYER)).willReturn(Optional.of(
                StripeCustomerEntity.builder()
                        .userId(PAYER).stripeCustomerId("cus_payer").defaultPaymentMethod("pm_saved").build()));
        given(feePolicyResolver.resolve(EscrowSourceKind.MEMBERSHIP, null)).willReturn(policy);
        given(connectChargeService.charge(any(MembershipChargeCommand.class)))
                .willReturn(new MembershipChargeResult(ESCROW_ID, "cs_secret", "pi_123", EscrowStatus.AUTHORIZED));
        given(stripePaymentProvider.createProduct(anyString(), any())).willReturn("prod_x");
        // 会費 Price → price_fee / 手数料 Price → price_surcharge を金額で振り分ける。
        given(stripePaymentProvider.createRecurringPrice(eq("prod_x"), any(), eq("JPY"), eq(BillingInterval.MONTHLY)))
                .willAnswer(inv -> {
                    BigDecimal amount = inv.getArgument(1);
                    return amount.longValueExact() == faceAmount ? "price_fee" : "price_surcharge";
                });
        given(membershipSubscriptionRepository.save(any())).willAnswer(inv -> {
            MembershipSubscriptionEntity e = inv.getArgument(0);
            if (e.getId() == null) {
                e.setId(SUB_ID);
            }
            return e;
        });
        given(stripePaymentProvider.createSubscription(
                anyString(), any(), anyString(), anyString(), any(), anyLong(), anyString()))
                .willReturn(new StripePaymentProvider.SubscriptionInfo("sub_abc", "active", null));
    }

    /** createRecurringPrice に渡った金額を作成順に取り出す。 */
    private List<BigDecimal> capturedRecurringPriceAmounts() {
        ArgumentCaptor<BigDecimal> captor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(stripePaymentProvider, org.mockito.Mockito.atLeastOnce())
                .createRecurringPrice(anyString(), captor.capture(), anyString(), any());
        return captor.getAllValues();
    }

    @SuppressWarnings("unchecked")
    private List<String> capturedSubscriptionPriceIds() {
        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(stripePaymentProvider).createSubscription(
                anyString(), captor.capture(), anyString(), anyString(), any(), anyLong(), anyString());
        return captor.getValue();
    }

    @Nested
    @DisplayName("AC-1: subscribe の Price 2 本化（会費＝額面 / 手数料＝payerFee）")
    class PriceComposition {

        @Test
        @DisplayName("AC-1: 会費 Price=10,000・手数料 Price=250（= PaymentFeeCalculator.payerFee）の 2 本を作る")
        void 会費と手数料の2本のPriceを作る() {
            stubSubscribeFlow(FACE, DEFAULT_POLICY);
            long expectedPayerFee = paymentFeeCalculator.calculate(FACE, DEFAULT_POLICY).payerFee();
            assertThat(expectedPayerFee).isEqualTo(250L); // 正典の裏取り（10,000 × 5% ÷ 2）

            service.subscribe(ITEM_ID, PAYER, BENEFICIARY, (short) 15, IDEMPOTENCY_KEY);

            List<BigDecimal> amounts = capturedRecurringPriceAmounts();
            assertThat(amounts).hasSize(2);
            assertThat(amounts.get(0).longValueExact()).isEqualTo(FACE);
            assertThat(amounts.get(1).longValueExact()).isEqualTo(expectedPayerFee);

            // Subscription には 2 明細（会費＋手数料）が渡る。
            assertThat(capturedSubscriptionPriceIds()).containsExactly("price_fee", "price_surcharge");
        }

        @Test
        @DisplayName("AC-1: 生成した recurring Price は membership_subscriptions に保持し fee_model_version=2 で起票する")
        void 新カラムに焼き付ける() {
            stubSubscribeFlow(FACE, DEFAULT_POLICY);

            MembershipSubscriptionEntity saved =
                    service.subscribe(ITEM_ID, PAYER, BENEFICIARY, (short) 15, IDEMPOTENCY_KEY);

            assertThat(saved.getStripePriceId()).isEqualTo("price_fee");
            assertThat(saved.getStripeSurchargePriceId()).isEqualTo("price_surcharge");
            assertThat(saved.getFeeModelVersion()).isEqualTo((short) 2);
        }
    }

    @Nested
    @DisplayName("AC-2 ★最重要: 初回サイクルと 2 サイクル目以降の請求額が一致する")
    class InitialAndRecurringParity {

        @Test
        @DisplayName("AC-2: 初回 PI 金額（chargeAmount=10,250）== Subscription 全明細の合計（10,000+250）")
        void 初回と2サイクル目の請求額が一致する() {
            stubSubscribeFlow(FACE, DEFAULT_POLICY);

            // 初回サイクル: ConnectChargeService.charge は同一 policy で PaymentFeeCalculator を通し
            // PI amount = fee.chargeAmount() で PaymentIntent を作る（ConnectChargeService の :562/:595）。
            long initialCycleCharged = paymentFeeCalculator.calculate(FACE, DEFAULT_POLICY).chargeAmount();
            assertThat(initialCycleCharged).isEqualTo(10_250L);

            service.subscribe(ITEM_ID, PAYER, BENEFICIARY, (short) 15, IDEMPOTENCY_KEY);

            // 2 サイクル目以降: Subscription の全明細合計が invoice 合計になる。
            long recurringCycleTotal = capturedRecurringPriceAmounts().stream()
                    .mapToLong(BigDecimal::longValueExact)
                    .sum();

            assertThat(recurringCycleTotal)
                    .as("初回サイクルと 2 サイクル目以降で支払側の請求額は完全に一致しなければならない")
                    .isEqualTo(initialCycleCharged);
        }

        /**
         * 額面を変えても一致し続けることを検証する（端数が出る 7,777 円＝折半 195 を含む）。
         *
         * <p>ループではなく {@code @ParameterizedTest} にしているのは、同一テストメソッド内でモックを使い回すと
         * {@code ArgumentCaptor} が前の反復の呼び出しまで拾ってしまい検証が壊れるため（1 ケース 1 モック文脈）。</p>
         */
        @org.junit.jupiter.params.ParameterizedTest(name = "face={0} で初回と2サイクル目の請求額が一致する")
        @org.junit.jupiter.params.provider.ValueSource(longs = {3_000L, 7_777L, 100L})
        @DisplayName("AC-2: 額面を変えても初回と 2 サイクル目の請求額は一致し続ける")
        void 額面を変えても一致し続ける(long face) {
            stubSubscribeFlow(face, DEFAULT_POLICY);
            long expected = paymentFeeCalculator.calculate(face, DEFAULT_POLICY).chargeAmount();

            service.subscribe(ITEM_ID, PAYER, BENEFICIARY, (short) 15, IDEMPOTENCY_KEY);

            long total = capturedRecurringPriceAmounts().stream()
                    .mapToLong(BigDecimal::longValueExact).sum();
            assertThat(total).as("face=%s の請求額一致", face).isEqualTo(expected);
        }
    }

    @Nested
    @DisplayName("AC-3: payerFee=0 のとき手数料 Price を作らない")
    class ZeroSurcharge {

        @Test
        @DisplayName("AC-3: 極小額面で payerFee=0 なら Price は 1 本のみ・明細も 1 つだけ")
        void 手数料ゼロなら明細を追加しない() {
            // 率 0%＋固定 0 → totalFee=0 → payerFee=0（額面のみを請求）。
            FeePolicy zeroPolicy = new FeePolicy("ZERO", BigDecimal.ZERO, 0L);
            stubSubscribeFlow(1_000L, zeroPolicy);
            assertThat(paymentFeeCalculator.calculate(1_000L, zeroPolicy).payerFee()).isZero();

            service.subscribe(ITEM_ID, PAYER, BENEFICIARY, (short) 15, IDEMPOTENCY_KEY);

            verify(stripePaymentProvider, times(1))
                    .createRecurringPrice(anyString(), any(), anyString(), any());
            assertThat(capturedSubscriptionPriceIds()).containsExactly("price_fee");
        }

        @Test
        @DisplayName("AC-3: payerFee=0 のとき surcharge Price ID は NULL で起票する")
        void 手数料ゼロならsurchargeはNULL() {
            FeePolicy zeroPolicy = new FeePolicy("ZERO", BigDecimal.ZERO, 0L);
            stubSubscribeFlow(1_000L, zeroPolicy);

            MembershipSubscriptionEntity saved =
                    service.subscribe(ITEM_ID, PAYER, BENEFICIARY, (short) 15, IDEMPOTENCY_KEY);

            assertThat(saved.getStripeSurchargePriceId()).isNull();
            assertThat(saved.getStripePriceId()).isEqualTo("price_fee");
        }
    }

    @Nested
    @DisplayName("AC-6 / AC-7: fee_policies 変更の反映と遡及防止")
    class PolicyChange {

        @Test
        @DisplayName("AC-6: percent_rate を 5%→8% に変更後、新規 subscribe の手数料 Price は 400（=10,000×8%÷2）")
        void 料率変更は新規契約に反映される() {
            FeePolicy eightPercent = new FeePolicy("DEFAULT", new BigDecimal("0.08"), 0L);
            stubSubscribeFlow(FACE, eightPercent);

            service.subscribe(ITEM_ID, PAYER, BENEFICIARY, (short) 15, IDEMPOTENCY_KEY);

            List<BigDecimal> amounts = capturedRecurringPriceAmounts();
            assertThat(amounts).hasSize(2);
            assertThat(amounts.get(1).longValueExact()).isEqualTo(400L);
            // 請求額一致は料率が変わっても崩れない。
            assertThat(amounts.stream().mapToLong(BigDecimal::longValueExact).sum())
                    .isEqualTo(paymentFeeCalculator.calculate(FACE, eightPercent).chargeAmount());
        }

        @Test
        @DisplayName("AC-7 ★: subscribe は解決時点の policy_key を焼き付ける（後の料率変更は既存契約へ遡及しない）")
        void 焼付キーは解決時点のものになる() {
            FeePolicy rankA = new FeePolicy("MEMBERSHIP_RANK_A", new BigDecimal("0.03"), 0L);
            stubSubscribeFlow(FACE, rankA);

            MembershipSubscriptionEntity saved =
                    service.subscribe(ITEM_ID, PAYER, BENEFICIARY, (short) 15, IDEMPOTENCY_KEY);

            // 焼付キーは加入時点の policy。以後 fee_policies を書き換えても本行は変わらない
            //（webhook 側は本キーで復元するため上書き額も変わらない＝AC-7 の webhook 側は
            //  MembershipSubscriptionWebhookServiceTest の「焼き付け fee_policy_key で算出」で担保）。
            assertThat(saved.getFeePolicyKey()).isEqualTo("MEMBERSHIP_RANK_A");
            // 焼き付けた Price（Stripe 上は不変オブジェクト）も契約に固定される。
            assertThat(saved.getStripePriceId()).isEqualTo("price_fee");
            assertThat(saved.getStripeSurchargePriceId()).isEqualTo("price_surcharge");
        }
    }

    @Nested
    @DisplayName("AC-14 ★: payment_items.stripe_price_id を recurring Price で汚染しない")
    class NoPaymentItemPollution {

        @Test
        @DisplayName("AC-14: subscribe 後も payment_items.stripe_price_id は recurring Price で上書きされない")
        void 一回払いPriceを汚染しない() {
            PaymentItemEntity item = recurringItem(FACE);
            item.updateStripeIds("prod_x", "price_onetime"); // 一回払い用 Price が既に焼き付いている
            given(paymentItemService.findByIdOrThrow(ITEM_ID)).willReturn(item);
            stubSubscribeFlow(FACE, DEFAULT_POLICY);
            given(paymentItemService.findByIdOrThrow(ITEM_ID)).willReturn(item);

            service.subscribe(ITEM_ID, PAYER, BENEFICIARY, (short) 15, IDEMPOTENCY_KEY);

            assertThat(item.getStripePriceId())
                    .as("recurring Price を一回払い Price 欄へ焼き付けてはならない（金額が別物のため誤課金源）")
                    .isEqualTo("price_onetime");
            assertThat(item.getStripeProductId()).isEqualTo("prod_x");
        }

        @Test
        @DisplayName("AC-14: Product が未作成なら作って項目へ焼き付ける（Price 欄は触らない）")
        void Productのみ焼き付ける() {
            PaymentItemEntity item = recurringItem(FACE);
            stubSubscribeFlow(FACE, DEFAULT_POLICY);
            given(paymentItemService.findByIdOrThrow(ITEM_ID)).willReturn(item);

            service.subscribe(ITEM_ID, PAYER, BENEFICIARY, (short) 15, IDEMPOTENCY_KEY);

            assertThat(item.getStripeProductId()).isEqualTo("prod_x");
            assertThat(item.getStripePriceId()).isNull();
        }

        @Test
        @DisplayName("AC-14: Product が既にあれば再作成しない（Product は再利用する）")
        void Productを再利用する() {
            PaymentItemEntity item = recurringItem(FACE);
            item.updateStripeIds("prod_existing", "price_onetime");
            stubSubscribeFlow(FACE, DEFAULT_POLICY);
            given(paymentItemService.findByIdOrThrow(ITEM_ID)).willReturn(item);
            given(stripePaymentProvider.createRecurringPrice(eq("prod_existing"), any(), eq("JPY"), any()))
                    .willAnswer(inv -> {
                        BigDecimal amount = inv.getArgument(1);
                        return amount.longValueExact() == FACE ? "price_fee" : "price_surcharge";
                    });

            service.subscribe(ITEM_ID, PAYER, BENEFICIARY, (short) 15, IDEMPOTENCY_KEY);

            verify(stripePaymentProvider, never()).createProduct(anyString(), any());
            assertThat(item.getStripePriceId()).isEqualTo("price_onetime");
        }
    }

    @Nested
    @DisplayName("安全側 application_fee_percent（invoice 上書き失敗時のフォールバック精度）")
    class SafeDefaultFeePercent {

        @Test
        @DisplayName("上書き失敗時に取り過ぎないよう totalFee/chargeAmount 基準（500/10,250≒4.88%）で渡す")
        void 安全側既定は請求額基準で算出する() {
            stubSubscribeFlow(FACE, DEFAULT_POLICY);

            service.subscribe(ITEM_ID, PAYER, BENEFICIARY, (short) 15, IDEMPOTENCY_KEY);

            ArgumentCaptor<BigDecimal> pctCaptor = ArgumentCaptor.forClass(BigDecimal.class);
            verify(stripePaymentProvider).createSubscription(
                    anyString(), any(), anyString(), anyString(), pctCaptor.capture(), anyLong(), anyString());

            // 額面基準の 5% では invoice 総額 10,250 の 5%＝512 となり総手数料 500 を超えて取り過ぎる。
            BigDecimal pct = pctCaptor.getValue();
            long fallbackFee = pct.multiply(BigDecimal.valueOf(10_250L))
                    .divide(BigDecimal.valueOf(100L), 0, java.math.RoundingMode.HALF_UP)
                    .longValueExact();
            assertThat(fallbackFee)
                    .as("フォールバックでも総手数料 500 から大きく外れない")
                    .isEqualTo(500L);
        }
    }
}
