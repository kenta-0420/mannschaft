package com.mannschaft.app.payment.recovery;

import com.mannschaft.app.payment.connect.ConnectAccountEntity;
import com.mannschaft.app.payment.connect.ConnectAccountRepository;
import com.mannschaft.app.payment.connect.OnboardingStatus;
import com.mannschaft.app.payment.connect.ScopeKind;
import com.mannschaft.app.payment.escrow.ConnectChargeService;
import com.mannschaft.app.payment.escrow.EscrowCaptureMode;
import com.mannschaft.app.payment.escrow.EscrowSourceKind;
import com.mannschaft.app.payment.escrow.EscrowStatus;
import com.mannschaft.app.payment.escrow.EscrowTransactionEntity;
import com.mannschaft.app.payment.escrow.EscrowTransactionRepository;
import com.mannschaft.app.payment.escrow.FeeBearer;
import com.mannschaft.app.payment.escrow.LedgerAccount;
import com.mannschaft.app.payment.escrow.LedgerDirection;
import com.mannschaft.app.payment.escrow.LedgerEntryEntity;
import com.mannschaft.app.payment.escrow.LedgerEntryRepository;
import com.mannschaft.app.payment.escrow.LedgerEntryType;
import com.mannschaft.app.payment.escrow.MembershipChargeCommand;
import com.mannschaft.app.payment.escrow.MembershipChargeResult;
import com.mannschaft.app.payment.stripe.CaptureMethod;
import com.mannschaft.app.payment.stripe.StripePaymentProvider;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;

/**
 * F22.1 §6.3 第五陣: 相殺回収フロー <b>統合テスト</b>（実 DB・Testcontainers MySQL）。
 *
 * <p>純 Mockito UT（C1/C2/A 各陣の単体）では出ない <b>実 DB 挙動</b>を実証する。具体的には:</p>
 * <ul>
 *   <li>{@code fee_recovery_balances} の UNIQUE 突合・upsert・{@code outstanding_amount} の実加減算が実スキーマで成立すること。</li>
 *   <li>{@code ledger_entries}(RECOVERY) の追記と {@link LedgerEntryRepository#sumAppliedRecoveryNetOnEscrow} の純額導出
 *       （{@code D − C}）が実 MySQL で正しく評価されること。</li>
 *   <li>C2 の {@link LedgerEntryRepository#findModeBRefundEscrowsWithoutRecovery} の <b>NOT IN サブクエリ</b>が
 *       実 MySQL で正しく動くこと（C2 足軽が懸念した点）。</li>
 *   <li>返金→計上(C1)→次回 charge で回収(A)→繰越→再返金で再計上(A 逆仕訳)→C2 補完→複式検算が一気通貫で破綻しないこと。</li>
 * </ul>
 *
 * <p><b>Stripe 境界の差し替え:</b> {@link StripePaymentProvider} は {@link MockitoBean} で決定的にスタブする
 * （実 Stripe 不要・{@code retrieveChargeProcessingFee} や PI 作成/返金/送金解決をテスト内で制御）。実 DB に対して
 * 実サービス（{@link ConnectChargeService} / {@link FeeReconciliationService}）を駆動する。</p>
 *
 * <p><b>非トランザクション:</b> {@link ConnectChargeService#refund}/{@code capture} 等は
 * {@code @Transactional(REQUIRES_NEW)} で独立コミットするため、本テストは外側を {@code @Transactional} にせず、
 * {@link BeforeEach}/{@link AfterEach} で対象テーブルを物理クリーンアップする。</p>
 *
 * <p>Docker 不在環境では {@link EnabledIf} によりスキップされる（実 green は CI 権威）。</p>
 *
 * <p>設計書: docs/features/F22.1_market/payment/02_api_design.md §6.3</p>
 */
@MockitoBean(types = {StripePaymentProvider.class, com.mannschaft.app.common.AccessControlService.class})
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F22.1 §6.3 相殺回収フロー 統合テスト（実DB・返金→計上→回収→繰越→再計上→C2補完→複式検算）")
class FeeRecoveryFlowIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private ConnectChargeService connectChargeService;
    @Autowired
    private FeeReconciliationService feeReconciliationService;
    @Autowired
    private EscrowTransactionRepository escrowRepository;
    @Autowired
    private LedgerEntryRepository ledgerRepository;
    @Autowired
    private FeeRecoveryBalanceRepository balanceRepository;
    @Autowired
    private ConnectAccountRepository connectAccountRepository;

    @Autowired
    private StripePaymentProvider stripePaymentProvider; // MockitoBean（差し替え済み）

    /**
     * 受取側 ADMIN 認可（{@code refund()} の payee ADMIN 検証）は MockitoBean で no-op 化する。
     * {@code checkPermission} は void であり、mock の既定挙動（何もしない＝例外を投げない）で TEAM payee の返金を通す。
     */
    @Autowired
    private com.mannschaft.app.common.AccessControlService accessControlService;

    private UUID payeeAccountId;
    private static final long ORG_ID = 7700L;
    private final AtomicInteger sourceSeq = new AtomicInteger(1);
    private final AtomicInteger piSeq = new AtomicInteger(1);

    @BeforeEach
    void setUp() {
        cleanData();
        // 受領側 Connect 口座（payouts_enabled=true・TEAM）を 1 件作る。回収はこの payee×currency 残高で行う。
        ConnectAccountEntity payee = connectAccountRepository.save(ConnectAccountEntity.builder()
                .scopeKind(ScopeKind.TEAM)
                .scopeId(4242L)
                .organizationId(ORG_ID)
                .stripeAccountId("acct_recovery_it")
                .onboardingStatus(OnboardingStatus.READY)
                .chargesEnabled(true)
                .payoutsEnabled(true)
                .country("JP")
                .defaultCurrency("jpy")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build());
        payeeAccountId = payee.getId();

        // Stripe スタブ: PI 作成は毎回ユニークな pi_xxx を返す。application_fee は ArgumentCaptor で検証する。
        willAnswer(inv -> new StripePaymentProvider.PaymentIntentInfo(
                "pi_it_" + piSeq.getAndIncrement(), "secret", "requires_confirmation"))
                .given(stripePaymentProvider).createDestinationPaymentIntent(
                        anyLong(), anyString(), anyString(), anyLong(), anyString(),
                        any(CaptureMethod.class), anyString());

        // 返金（ModeB は createConnectRefund・refund_application_fee=true）は毎回ユニークな re_xxx を返す。
        AtomicInteger refundSeq = new AtomicInteger(1);
        willAnswer(inv -> new StripePaymentProvider.ConnectRefundInfo(
                "re_it_" + refundSeq.getAndIncrement(), "pending"))
                .given(stripePaymentProvider).createConnectRefund(
                        anyString(), anyLong(), anyString(), anyBoolean(), anyBoolean(), anyString());

        // 受取側 ADMIN 認可（TEAM 経路）は AccessControlService の MockitoBean により no-op で通る（checkPermission は void）。
    }

    @AfterEach
    void tearDown() {
        cleanData();
    }

    private void cleanData() {
        ledgerRepository.deleteAll();
        escrowRepository.deleteAll();
        balanceRepository.deleteAll();
        connectAccountRepository.deleteAll();
    }

    // ============================================================
    // ヘルパ
    // ============================================================

    /** ModeB 返金時に C1 が引く実 Stripe 手数料をスタブする（pending を表現するなら PROCESSING_FEE_PENDING）。 */
    private void stubProcessingFee(long fee) {
        given(stripePaymentProvider.retrieveChargeProcessingFee(anyString())).willReturn(fee);
    }

    /**
     * payee 向けの会費 charge を実行する（MEMBERSHIP/AUTOMATIC）。回収上乗せ＋RECOVERY 記帳＋outstanding 減算が走る。
     *
     * @return 作成された escrow（AUTHORIZED）
     */
    private EscrowTransactionEntity doCharge(long faceAmount) {
        long src = sourceSeq.getAndIncrement();
        MembershipChargeCommand cmd = new MembershipChargeCommand(
                faceAmount, payeeAccountId, "cus_it", 999L, src, ORG_ID, "idem-it-" + src);
        MembershipChargeResult result = connectChargeService.charge(cmd);
        return escrowRepository.findById(result.escrowTransactionId()).orElseThrow();
    }

    /**
     * CAPTURED 済みの escrow を直接作る（succeeded webhook 相当）。capture() の複式記帳
     * （CAPTURE/TRANSFER_OUT/FEE）も実額で起票し、複式検算が成立する母体を用意する。
     * その後の ModeB 返金（C1）・再計上（A）を実サービスで駆動する。
     */
    private EscrowTransactionEntity captureBaseEscrow(long faceAmount) {
        // 手数料: payerFee=round(face*0.025), charge=face+payerFee, appFee=round(face*0.05)。
        long payerFee = Math.round(faceAmount * 0.025);
        long charge = faceAmount + payerFee;
        long appFee = Math.round(faceAmount * 0.05);
        long transferOut = charge - appFee;
        long src = sourceSeq.getAndIncrement();
        String piId = "pi_cap_" + piSeq.getAndIncrement();
        EscrowTransactionEntity escrow = escrowRepository.save(EscrowTransactionEntity.builder()
                .sourceKind(EscrowSourceKind.MEMBERSHIP)
                .captureMode(EscrowCaptureMode.AUTOMATIC)
                .sourceId(src)
                .payerScopeKind(ScopeKind.USER)
                .payerScopeId(999L)
                .payerStripeCustomerId("cus_it")
                .payeeKind(ScopeKind.TEAM)
                .payeeConnectAccountId(payeeAccountId)
                .organizationId(ORG_ID)
                .faceAmount(faceAmount)
                .amount(charge)
                .currency("JPY")
                .applicationFeeAmount(appFee)
                .feePolicyKey("DEFAULT")
                .status(EscrowStatus.CAPTURED)
                .stripePaymentIntentId(piId)
                .stripeIdempotencyKey("idem-cap-" + src)
                .authorizedAt(LocalDateTime.now())
                .capturedAt(LocalDateTime.now())
                .build());
        // capture() と同じ複式記帳（D ESCROW = C PAYEE + C PLATFORM_FEE）を起票し、検算の母体にする。
        ledgerRepository.saveAll(com.mannschaft.app.payment.escrow.LedgerEntryBuilder
                .forTransaction(escrow.getId(), "JPY")
                .debit(LedgerEntryType.CAPTURE, LedgerAccount.ESCROW, charge, piId)
                .credit(LedgerEntryType.TRANSFER_OUT, LedgerAccount.PAYEE, transferOut, piId)
                .credit(LedgerEntryType.FEE, LedgerAccount.PLATFORM_FEE, appFee, piId)
                .build());
        return escrow;
    }

    private long outstanding() {
        return balanceRepository
                .findByConnectAccountIdAndCurrencyAndDeletedAtIsNull(payeeAccountId, "jpy")
                .map(b -> b.getOutstandingAmount() != null ? b.getOutstandingAmount() : 0L)
                .orElse(0L);
    }

    /** 1 escrow の借方合計＝貸方合計を検算する（複式整合）。 */
    private void assertBalanced(UUID escrowId) {
        List<LedgerEntryEntity> entries = ledgerRepository.findByEscrowTransactionIdOrderByCreatedAtAsc(escrowId);
        long debit = entries.stream().filter(e -> e.getDirection() == LedgerDirection.D)
                .mapToLong(LedgerEntryEntity::getAmount).sum();
        long credit = entries.stream().filter(e -> e.getDirection() == LedgerDirection.C)
                .mapToLong(LedgerEntryEntity::getAmount).sum();
        assertThat(debit).as("escrow %s の借方合計=貸方合計", escrowId).isEqualTo(credit);
        assertThat(feeReconciliationService.verifyDoubleEntry(escrowId))
                .as("FeeReconciliationService 検算が一致を返す").isTrue();
    }

    /** capture/charge の PI に渡された application_fee を捕捉する（直近呼び出し）。 */
    private long lastPiApplicationFee() {
        ArgumentCaptor<Long> feeCaptor = ArgumentCaptor.forClass(Long.class);
        org.mockito.Mockito.verify(stripePaymentProvider, org.mockito.Mockito.atLeastOnce())
                .createDestinationPaymentIntent(anyLong(), anyString(), anyString(),
                        feeCaptor.capture(), anyString(), any(CaptureMethod.class), anyString());
        List<Long> all = feeCaptor.getAllValues();
        return all.get(all.size() - 1);
    }

    // ============================================================
    // シナリオ 1: 回収フル一気通貫
    // ============================================================

    @Test
    @DisplayName("1: ModeB全額返金→outstanding に実手数料計上→次回 charge で回収実行→outstanding=0")
    void fullRecoveryRoundTrip() {
        // 元 charge（face=10,000・charge=10,250・appFee=500）を CAPTURED で用意。
        EscrowTransactionEntity base = captureBaseEscrow(10_000L);
        long stripeFee = 360L;
        stubProcessingFee(stripeFee);

        // ModeB 全額返金 → C1 が実手数料 360 を outstanding に計上（D PLATFORM_FEE = C PAYEE）。
        connectChargeService.refund(base.getId(), null, FeeBearer.PAYEE, "cancellation", null, 1L);
        assertThat(outstanding()).as("ModeB 返金で実手数料が outstanding に計上").isEqualTo(stripeFee);
        assertBalanced(base.getId());

        // 次回 charge（face=10,000・headroom=9,750 ≥ 360）→ 全額回収。PI application_fee = selfFee(500)+recovery(360)。
        EscrowTransactionEntity next = doCharge(10_000L);
        assertThat(lastPiApplicationFee()).as("PI に回収分が上乗せされる").isEqualTo(500L + stripeFee);

        // outstanding=0・回収実行 RECOVERY(D PAYEE) 純額が recovery 額。
        assertThat(outstanding()).as("回収実行で outstanding=0").isZero();
        assertThat(ledgerRepository.sumAppliedRecoveryNetOnEscrow(next.getId()))
                .as("次回 escrow に回収実行 RECOVERY 純額").isEqualTo(stripeFee);
        assertBalanced(next.getId());
    }

    // ============================================================
    // シナリオ 2: 部分回収＋繰越
    // ============================================================

    @Test
    @DisplayName("2: outstanding > headroom のとき headroom 分のみ回収し残りを繰越→次々回で完済")
    void partialRecoveryThenCarryOver() {
        // 小額 charge を回し outstanding を大きく積む。face=1,000 なら headroom=1,000+25-50=975。
        // outstanding を 1,500 まで積み上げて、次回 face=1,000 の charge で 975 のみ回収・525 繰越を作る。
        EscrowTransactionEntity b1 = captureBaseEscrow(20_000L); // charge=20,500, appFee=1,000
        stubProcessingFee(800L);
        connectChargeService.refund(b1.getId(), null, FeeBearer.PAYEE, "cancellation", null, 1L);
        EscrowTransactionEntity b2 = captureBaseEscrow(20_000L);
        stubProcessingFee(700L);
        connectChargeService.refund(b2.getId(), null, FeeBearer.PAYEE, "cancellation", null, 1L);
        long total = outstanding();
        assertThat(total).isEqualTo(1_500L); // 800 + 700

        // 次回 face=1,000（headroom=975 < 1,500）→ 975 のみ回収・繰越 525。
        EscrowTransactionEntity n1 = doCharge(1_000L);
        long headroom1 = 975L;
        assertThat(ledgerRepository.sumAppliedRecoveryNetOnEscrow(n1.getId())).isEqualTo(headroom1);
        assertThat(outstanding()).as("繰越残").isEqualTo(total - headroom1); // 525
        assertBalanced(n1.getId());

        // そのまた次回 face=1,000（headroom=975 ≥ 525）→ 残り 525 を回収・完済。
        EscrowTransactionEntity n2 = doCharge(1_000L);
        assertThat(ledgerRepository.sumAppliedRecoveryNetOnEscrow(n2.getId())).isEqualTo(525L);
        assertThat(outstanding()).as("完済").isZero();
        assertBalanced(n2.getId());
    }

    // ============================================================
    // シナリオ 3: 再返金時再計上（ModeB は戻す・ModeA は戻さない）
    // ============================================================

    @Test
    @DisplayName("3: 回収を上乗せした charge を ModeB 返金→回収分が outstanding へ戻る／ModeA では戻らない")
    void recapitalizeOnModeBRefundOnly() {
        // outstanding=360 を作る。
        EscrowTransactionEntity base = captureBaseEscrow(10_000L);
        stubProcessingFee(360L);
        connectChargeService.refund(base.getId(), null, FeeBearer.PAYEE, "cancellation", null, 1L);
        assertThat(outstanding()).isEqualTo(360L);

        // 次回 charge で全額回収（outstanding=0・recovery=360 が next に乗る）。
        EscrowTransactionEntity next = doCharge(10_000L);
        assertThat(outstanding()).isZero();
        assertThat(ledgerRepository.sumAppliedRecoveryNetOnEscrow(next.getId())).isEqualTo(360L);

        // next を CAPTURED にし（succeeded 相当）、ModeB 返金 → 回収分 360 が outstanding へ再計上され、純額が 0 に戻る。
        next.setStatus(EscrowStatus.CAPTURED);
        next.setCapturedAt(LocalDateTime.now());
        escrowRepository.save(next);
        stubProcessingFee(StripePaymentProvider.PROCESSING_FEE_PENDING); // 自身の C1 計上は pending でスキップさせ、A 再計上だけを観測。
        connectChargeService.refund(next.getId(), null, FeeBearer.PAYEE, "cancellation", null, 1L);

        assertThat(ledgerRepository.sumAppliedRecoveryNetOnEscrow(next.getId()))
                .as("ModeB 再返金で回収実行の純額が 0 に戻る（再計上の逆仕訳）").isZero();
        assertThat(outstanding()).as("回収分 360 が outstanding へ再計上").isEqualTo(360L);
        assertBalanced(next.getId());
    }

    @Test
    @DisplayName("3b: 回収を上乗せした charge を ModeA 返金→回収は維持（outstanding に戻らない）")
    void modeARefundKeepsRecovery() {
        EscrowTransactionEntity base = captureBaseEscrow(10_000L);
        stubProcessingFee(360L);
        connectChargeService.refund(base.getId(), null, FeeBearer.PAYEE, "cancellation", null, 1L);
        EscrowTransactionEntity next = doCharge(10_000L);
        assertThat(outstanding()).isZero();

        // ModeA 返金は reverseTransfer→resolveTransferId が必要。スタブする。
        given(stripePaymentProvider.resolveTransferIdFromPaymentIntent(anyString())).willReturn("tr_it_1");
        next.setStatus(EscrowStatus.CAPTURED);
        next.setCapturedAt(LocalDateTime.now());
        escrowRepository.save(next);
        connectChargeService.refund(next.getId(), null, FeeBearer.PAYER, "requested_by_customer", null, 1L);

        // ModeA では再計上しない（回収実行の純額は 360 のまま・outstanding は 0 のまま）。
        assertThat(ledgerRepository.sumAppliedRecoveryNetOnEscrow(next.getId()))
                .as("ModeA 返金では回収は維持").isEqualTo(360L);
        assertThat(outstanding()).as("ModeA では outstanding に戻らない").isZero();
        assertBalanced(next.getId());
    }

    // ============================================================
    // シナリオ 4: C1（自身の手数料計上）＋A 再計上の合成
    // ============================================================

    @Test
    @DisplayName("4: 同一 payee の ModeB 返金で『自身の手数料計上(C1)』と『他者債務回収の戻し(A再計上)』が同一残高に加算合成")
    void c1AndRecapitalizeCompose() {
        // 先行 charge X1 を ModeB 返金して outstanding=360 を作る。
        EscrowTransactionEntity x1 = captureBaseEscrow(10_000L);
        stubProcessingFee(360L);
        connectChargeService.refund(x1.getId(), null, FeeBearer.PAYEE, "cancellation", null, 1L);
        assertThat(outstanding()).isEqualTo(360L);

        // X2 charge で 360 を回収（outstanding=0・recovery=360 が X2 に乗る）。
        EscrowTransactionEntity x2 = doCharge(10_000L);
        assertThat(outstanding()).isZero();
        assertThat(ledgerRepository.sumAppliedRecoveryNetOnEscrow(x2.getId())).isEqualTo(360L);

        // X2 を CAPTURED にし、ModeB 返金。これで同一返金処理内で:
        //   ・C1: X2 自身の実手数料 400 を outstanding へ計上
        //   ・A 再計上: X2 に乗っていた回収 360 を outstanding へ戻す
        // → outstanding = 400 + 360 = 760 に加算合成される。
        x2.setStatus(EscrowStatus.CAPTURED);
        x2.setCapturedAt(LocalDateTime.now());
        escrowRepository.save(x2);
        stubProcessingFee(400L);
        connectChargeService.refund(x2.getId(), null, FeeBearer.PAYEE, "cancellation", null, 1L);

        assertThat(outstanding()).as("C1(400)＋A再計上(360)が同一残高に合成").isEqualTo(760L);
        assertThat(ledgerRepository.sumAppliedRecoveryNetOnEscrow(x2.getId()))
                .as("A 再計上で回収純額は 0 に戻る").isZero();
        assertBalanced(x2.getId());
    }

    // ============================================================
    // シナリオ 5: C2 pending 補完（NOT IN サブクエリの実 MySQL 動作）
    // ============================================================

    @Test
    @DisplayName("5: C1 で pending だった返金を completePendingRecovery が確定後に補完計上（findModeBRefundEscrowsWithoutRecovery が実 MySQL で動く）")
    void c2CompletePendingRecovery() {
        // ModeB 返金時に C1 は pending で計上スキップ → RECOVERY 行なし・outstanding=0。
        EscrowTransactionEntity base = captureBaseEscrow(10_000L);
        stubProcessingFee(StripePaymentProvider.PROCESSING_FEE_PENDING);
        connectChargeService.refund(base.getId(), null, FeeBearer.PAYEE, "cancellation", null, 1L);
        assertThat(outstanding()).as("pending のため C1 は計上スキップ").isZero();

        // 補完候補抽出（NOT IN サブクエリ）が当該 escrow を拾う。
        List<UUID> candidates = feeReconciliationService.findPendingRecoveryCandidates();
        assertThat(candidates).as("ModeB 返金記帳ありかつ RECOVERY なしを補完候補に抽出").contains(base.getId());

        // balance_transaction 確定後（実手数料 360）→ 補完計上。
        stubProcessingFee(360L);
        FeeReconciliationService.CompletionOutcome outcome =
                feeReconciliationService.completePendingRecovery(base.getId());
        assertThat(outcome).isEqualTo(FeeReconciliationService.CompletionOutcome.COMPLETED);
        assertThat(outstanding()).as("補完で実手数料が outstanding へ計上").isEqualTo(360L);
        assertBalanced(base.getId());

        // 補完後は候補から外れる（冪等・二重補完しない）。
        assertThat(feeReconciliationService.findPendingRecoveryCandidates())
                .as("補完後は候補から外れる").doesNotContain(base.getId());
        FeeReconciliationService.CompletionOutcome again =
                feeReconciliationService.completePendingRecovery(base.getId());
        assertThat(again).as("二重補完は SKIPPED（候補外）").isEqualTo(FeeReconciliationService.CompletionOutcome.SKIPPED);
        assertThat(outstanding()).as("二重補完で残高は増えない").isEqualTo(360L);
    }

    @Test
    @DisplayName("5b: 依然 pending のままなら STILL_PENDING（滞留）で計上しない")
    void c2StillPending() {
        EscrowTransactionEntity base = captureBaseEscrow(10_000L);
        stubProcessingFee(StripePaymentProvider.PROCESSING_FEE_PENDING);
        connectChargeService.refund(base.getId(), null, FeeBearer.PAYEE, "cancellation", null, 1L);

        // 依然 pending → 補完できず STILL_PENDING。
        FeeReconciliationService.CompletionOutcome outcome =
                feeReconciliationService.completePendingRecovery(base.getId());
        assertThat(outcome).isEqualTo(FeeReconciliationService.CompletionOutcome.STILL_PENDING);
        assertThat(outstanding()).isZero();
    }

    // ============================================================
    // シナリオ 7: 冪等（二重回収/二重補完しない）
    // ============================================================

    @Test
    @DisplayName("7: 同一 escrow への回収実行の二重起動で二重回収しない（sumAppliedRecoveryNetOnEscrow の純額ガード）")
    void idempotentRecoveryExecution() {
        EscrowTransactionEntity base = captureBaseEscrow(10_000L);
        stubProcessingFee(360L);
        connectChargeService.refund(base.getId(), null, FeeBearer.PAYEE, "cancellation", null, 1L);
        EscrowTransactionEntity next = doCharge(10_000L);

        long netAfterFirst = ledgerRepository.sumAppliedRecoveryNetOnEscrow(next.getId());
        long outstandingAfterFirst = outstanding();
        assertThat(netAfterFirst).isEqualTo(360L);
        assertThat(outstandingAfterFirst).isZero();

        // 同一 charge の冪等再実行（同一 idempotencyKey）→ 既存 escrow を返し、新規回収を起こさない。
        long src = next.getSourceId();
        MembershipChargeCommand dup = new MembershipChargeCommand(
                10_000L, payeeAccountId, "cus_it", 999L, src, ORG_ID, "idem-it-" + src);
        connectChargeService.charge(dup);
        assertThat(ledgerRepository.sumAppliedRecoveryNetOnEscrow(next.getId()))
                .as("二重 charge で回収純額は不変").isEqualTo(netAfterFirst);
        assertThat(outstanding()).as("二重 charge で outstanding 不変").isEqualTo(outstandingAfterFirst);
    }

    // ============================================================
    // シナリオ 8: 後方互換（outstanding=0 の通常 charge は不変）
    // ============================================================

    @Test
    @DisplayName("8: outstanding=0 の通常 charge は application_fee=totalFee で完全不変（回収上乗せなし・RECOVERY 行なし）")
    void backwardCompatibleWhenNoOutstanding() {
        // 残高行なし（outstanding=0）。
        assertThat(balanceRepository.findByConnectAccountIdAndCurrencyAndDeletedAtIsNull(payeeAccountId, "jpy"))
                .isEmpty();

        EscrowTransactionEntity escrow = doCharge(10_000L); // face=10,000・selfFee=500
        // PI application_fee は selfFee のまま（上乗せ 0）。
        assertThat(lastPiApplicationFee()).as("回収上乗せなし＝selfFee のまま").isEqualTo(500L);
        // escrow の application_fee_amount も selfFee のまま。
        assertThat(escrow.getApplicationFeeAmount()).isEqualTo(500L);
        // RECOVERY 行は 1 件も立たない。
        assertThat(ledgerRepository.sumAppliedRecoveryNetOnEscrow(escrow.getId())).isZero();
        // 残高行は依然作られない（0 円 upsert もしない）。
        assertThat(balanceRepository.findByConnectAccountIdAndCurrencyAndDeletedAtIsNull(payeeAccountId, "jpy"))
                .as("回収なしでは残高行を作らない").isEmpty();
    }
}
