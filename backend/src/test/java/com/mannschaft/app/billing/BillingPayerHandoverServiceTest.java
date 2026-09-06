package com.mannschaft.app.billing;

import com.mannschaft.app.billing.BillingPayerHandoverService.HandoverAcceptResult;
import com.mannschaft.app.billing.BillingPayerHandoverService.HandoverRequestResult;
import com.mannschaft.app.billing.BillingPayerHandoverTxService.AcceptTransition;
import com.mannschaft.app.billing.BillingPayerHandoverTxService.AcceptValidation;
import com.mannschaft.app.billing.BillingPayerHandoverTxService.CheckoutCompletion;
import com.mannschaft.app.billing.BillingPayerHandoverTxService.SwitchContext;
import com.mannschaft.app.billing.BillingPaymentGateway.CheckoutSessionInfo;
import com.mannschaft.app.billing.BillingPaymentGateway.SubscriptionSnapshot;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.ErrorCode;
import com.mannschaft.app.role.service.RoleService;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * 柱③-B 請求担当引継（CMP-260901-1538）: {@link BillingPayerHandoverService} 単体テスト（試練先行）。
 *
 * <p>設計書 {@code docs/architecture/billing_payer_handover_design.md} §8 の AC を1つずつ検証する。
 * 対象 AC: AC-5 / AC-6 / AC-7 / AC-10 / AC-11 / AC-12 / AC-16 / AC-25 / AC-27 / AC-29 / AC-30 /
 * AC-31 / AC-32 / AC-35、および IDOR（別スコープの handoverRequestId は 404 で畳む）。</p>
 *
 * <p>時刻は {@link Clock#fixed} を注入する。{@code billing_contracts} 側は {@link LocalDateTime}、
 * handover 側は {@link Instant} であり、変換は同じ Clock の zone（UTC）で対称に行われる前提。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("BillingPayerHandoverService 単体テスト（請求担当の引継）")
class BillingPayerHandoverServiceTest {

    /** 2026-09-06T00:00:00Z を「現在」とする。 */
    private static final Instant NOW = Instant.parse("2026-09-06T00:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    /** 旧契約の期末（現在から 20 日後・未来）。AC-5 の unix 秒一致の基準値。 */
    private static final LocalDateTime OLD_PERIOD_END = LocalDateTime.ofInstant(
            Instant.parse("2026-09-26T15:30:00Z"), ZoneOffset.UTC);
    private static final Instant OLD_PERIOD_END_INSTANT = Instant.parse("2026-09-26T15:30:00Z");

    private static final Long TEAM_ID = 10L;
    private static final Long OTHER_TEAM_ID = 11L;
    private static final Long OLD_PAYER = 7L;
    private static final Long NEW_PAYER = 8L;
    private static final String OLD_SUB = "sub_old";
    private static final String NEW_SUB = "sub_new";

    @Mock private BillingPayerHandoverRequestRepository handoverRequestRepository;
    @Mock private BillingContractRepository billingContractRepository;
    @Mock private BillingOperationAuthorizer billingOperationAuthorizer;
    @Mock private BillingPaymentGateway billingPaymentGateway;
    @Mock private BillingPayerHandoverTxService handoverTxService;
    @Mock private RoleService roleService;

    private BillingPayerHandoverService service;

    private final UUID oldContractId = UUID.randomUUID();
    private final UUID newContractId = UUID.randomUUID();
    private final UUID handoverId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new BillingPayerHandoverService(
                handoverRequestRepository, billingContractRepository, billingOperationAuthorizer,
                billingPaymentGateway, handoverTxService, roleService, FIXED_CLOCK);
        ReflectionTestUtils.setField(service, "appBaseUrl", "http://localhost:3000");
    }

    // ============================================================
    // フィクスチャ
    // ============================================================

    /** 引継可能な TEAM 有償契約（PSP 紐付あり・期末は未来・ACTIVE）。 */
    private BillingContractEntity eligibleContract() {
        BillingContractEntity c = BillingContractEntity.builder()
                .scopeKind(EntitlementScopeKind.TEAM)
                .scopeId(TEAM_ID)
                .organizationId(99L)
                .contractKind(ContractKind.PLAN)
                .planKey("FULL")
                .status(ContractStatus.ACTIVE)
                .priceJpySnapshot(2000)
                .contractedAt(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC))
                .createdBy(OLD_PAYER)
                .payerUserId(OLD_PAYER)
                .pspCustomerRef("cus_old")
                .pspSubscriptionRef(OLD_SUB)
                .currentPeriodEnd(OLD_PERIOD_END)
                .build();
        c.setId(oldContractId);
        return c;
    }

    private void givenEligibleContractAndCandidates() {
        given(billingContractRepository.findByIdAndDeletedAtIsNull(oldContractId))
                .willReturn(Optional.of(eligibleContract()));
        given(roleService.getUserIdsByTeamIdAndRoleName(TEAM_ID, "ADMIN"))
                .willReturn(List.of(OLD_PAYER, NEW_PAYER));
        given(handoverRequestRepository.findByOldContractIdAndStatusNotIn(eq(oldContractId), any()))
                .willReturn(List.of());
        given(handoverRequestRepository.save(any(BillingPayerHandoverRequestEntity.class)))
                .willAnswer(inv -> {
                    BillingPayerHandoverRequestEntity e = inv.getArgument(0);
                    if (e.getId() == null) {
                        e.setId(handoverId);
                    }
                    return e;
                });
    }

    private static ErrorCode errorCodeOf(Throwable t) {
        return ((BusinessException) t).getErrorCode();
    }

    // ============================================================
    // requestHandover
    // ============================================================

    @Nested
    @DisplayName("requestHandover（引継要求の作成）")
    class RequestHandover {

        @Test
        @DisplayName("正常系: REQUESTED で作成され、期限は requested_at + 14 日・旧 payer が焼き付く")
        void createsRequestedRow() {
            givenEligibleContractAndCandidates();

            HandoverRequestResult result = service.requestHandover(
                    EntitlementScopeKind.TEAM, TEAM_ID, oldContractId, OLD_PAYER);

            assertThat(result.status()).isEqualTo(PayerHandoverStatus.REQUESTED);
            assertThat(result.requestedAt()).isEqualTo(NOW);
            assertThat(result.expiresAt()).isEqualTo(NOW.plus(java.time.Duration.ofDays(14)));
            assertThat(result.oldContractId()).isEqualTo(oldContractId);

            ArgumentCaptor<BillingPayerHandoverRequestEntity> captor =
                    ArgumentCaptor.forClass(BillingPayerHandoverRequestEntity.class);
            verify(handoverRequestRepository).save(captor.capture());
            BillingPayerHandoverRequestEntity saved = captor.getValue();
            assertThat(saved.getStatus()).isEqualTo(PayerHandoverStatus.REQUESTED);
            assertThat(saved.getOldPayerUserId()).isEqualTo(OLD_PAYER);
            assertThat(saved.getScopeKind()).isEqualTo(EntitlementScopeKind.TEAM);
            assertThat(saved.getScopeId()).isEqualTo(TEAM_ID);
            assertThat(saved.getNewContractId()).isNull();
            assertThat(saved.getNewPayerUserId()).isNull();
        }

        @Test
        @DisplayName("USER スコープは HANDOVER_SCOPE_NOT_SUPPORTED（認可も契約参照も行わない）")
        void userScopeRejected() {
            assertThatThrownBy(() -> service.requestHandover(
                    EntitlementScopeKind.USER, 3L, oldContractId, 3L))
                    .isInstanceOf(BusinessException.class)
                    .extracting(BillingPayerHandoverServiceTest::errorCodeOf)
                    .isEqualTo(EntitlementErrorCode.HANDOVER_SCOPE_NOT_SUPPORTED);

            verifyNoInteractions(billingOperationAuthorizer, billingContractRepository);
        }

        @Test
        @DisplayName("スコープ越境の contractId は HANDOVER_NOT_FOUND（404 で畳み存在オラクルを残さない）")
        void crossScopeContractIsHiddenAs404() {
            given(billingContractRepository.findByIdAndDeletedAtIsNull(oldContractId))
                    .willReturn(Optional.of(eligibleContract())); // scopeId=TEAM_ID の契約

            assertThatThrownBy(() -> service.requestHandover(
                    EntitlementScopeKind.TEAM, OTHER_TEAM_ID, oldContractId, OLD_PAYER))
                    .isInstanceOf(BusinessException.class)
                    .extracting(BillingPayerHandoverServiceTest::errorCodeOf)
                    .isEqualTo(EntitlementErrorCode.HANDOVER_NOT_FOUND);

            verify(handoverRequestRepository, never()).save(any());
        }

        @Test
        @DisplayName("§5.1: psp_subscription_ref が NULL なら HANDOVER_CONTRACT_NOT_ELIGIBLE")
        void noPspSubscriptionRefRejected() {
            BillingContractEntity c = eligibleContract();
            c.setPspSubscriptionRef(null);
            given(billingContractRepository.findByIdAndDeletedAtIsNull(oldContractId)).willReturn(Optional.of(c));

            assertThatThrownBy(() -> service.requestHandover(
                    EntitlementScopeKind.TEAM, TEAM_ID, oldContractId, OLD_PAYER))
                    .isInstanceOf(BusinessException.class)
                    .extracting(BillingPayerHandoverServiceTest::errorCodeOf)
                    .isEqualTo(EntitlementErrorCode.HANDOVER_CONTRACT_NOT_ELIGIBLE);
            verify(handoverRequestRepository, never()).save(any());
        }

        @Test
        @DisplayName("§5.1: current_period_end が NULL なら HANDOVER_CONTRACT_NOT_ELIGIBLE")
        void noCurrentPeriodEndRejected() {
            BillingContractEntity c = eligibleContract();
            c.setCurrentPeriodEnd(null);
            given(billingContractRepository.findByIdAndDeletedAtIsNull(oldContractId)).willReturn(Optional.of(c));

            assertThatThrownBy(() -> service.requestHandover(
                    EntitlementScopeKind.TEAM, TEAM_ID, oldContractId, OLD_PAYER))
                    .isInstanceOf(BusinessException.class)
                    .extracting(BillingPayerHandoverServiceTest::errorCodeOf)
                    .isEqualTo(EntitlementErrorCode.HANDOVER_CONTRACT_NOT_ELIGIBLE);
        }

        @Test
        @DisplayName("AC-29①: 旧契約が PAST_DUE なら HANDOVER_CONTRACT_NOT_ELIGIBLE（trial_end 方式が使えない）")
        void ac29_pastDueContractRejected() {
            BillingContractEntity c = eligibleContract();
            c.setStatus(ContractStatus.PAST_DUE);
            given(billingContractRepository.findByIdAndDeletedAtIsNull(oldContractId)).willReturn(Optional.of(c));

            assertThatThrownBy(() -> service.requestHandover(
                    EntitlementScopeKind.TEAM, TEAM_ID, oldContractId, OLD_PAYER))
                    .isInstanceOf(BusinessException.class)
                    .extracting(BillingPayerHandoverServiceTest::errorCodeOf)
                    .isEqualTo(EntitlementErrorCode.HANDOVER_CONTRACT_NOT_ELIGIBLE);
            verify(handoverRequestRepository, never()).save(any());
        }

        @Test
        @DisplayName("AC-29②: current_period_end が過去なら HANDOVER_CONTRACT_NOT_ELIGIBLE（trial_end に過去は不可）")
        void ac29_pastPeriodEndRejected() {
            BillingContractEntity c = eligibleContract();
            c.setCurrentPeriodEnd(LocalDateTime.ofInstant(NOW.minusSeconds(1), ZoneOffset.UTC));
            given(billingContractRepository.findByIdAndDeletedAtIsNull(oldContractId)).willReturn(Optional.of(c));

            assertThatThrownBy(() -> service.requestHandover(
                    EntitlementScopeKind.TEAM, TEAM_ID, oldContractId, OLD_PAYER))
                    .isInstanceOf(BusinessException.class)
                    .extracting(BillingPayerHandoverServiceTest::errorCodeOf)
                    .isEqualTo(EntitlementErrorCode.HANDOVER_CONTRACT_NOT_ELIGIBLE);
            verify(handoverRequestRepository, never()).save(any());
        }

        @Test
        @DisplayName("AC-10/17: ADMIN が旧 payer 1 人だけ（引継先候補 0 人）なら HANDOVER_NO_CANDIDATE")
        void ac10_noCandidateAdmin() {
            given(billingContractRepository.findByIdAndDeletedAtIsNull(oldContractId))
                    .willReturn(Optional.of(eligibleContract()));
            given(roleService.getUserIdsByTeamIdAndRoleName(TEAM_ID, "ADMIN"))
                    .willReturn(List.of(OLD_PAYER));

            assertThatThrownBy(() -> service.requestHandover(
                    EntitlementScopeKind.TEAM, TEAM_ID, oldContractId, OLD_PAYER))
                    .isInstanceOf(BusinessException.class)
                    .extracting(BillingPayerHandoverServiceTest::errorCodeOf)
                    .isEqualTo(EntitlementErrorCode.HANDOVER_NO_CANDIDATE);
            verify(handoverRequestRepository, never()).save(any());
        }

        @Test
        @DisplayName("AC-18: 他 ADMIN が全員退会予定（deleted_at 済で候補一覧に現れない）なら HANDOVER_NO_CANDIDATE")
        void ac18_allOtherAdminsWithdrawing() {
            given(billingContractRepository.findByIdAndDeletedAtIsNull(oldContractId))
                    .willReturn(Optional.of(eligibleContract()));
            // RoleService の候補クエリは deleted_at IS NULL / status='ACTIVE' で絞るため、
            // 退会予定の ADMIN はそもそも一覧に現れない（＝空になる）。
            given(roleService.getUserIdsByTeamIdAndRoleName(TEAM_ID, "ADMIN")).willReturn(List.of());

            assertThatThrownBy(() -> service.requestHandover(
                    EntitlementScopeKind.TEAM, TEAM_ID, oldContractId, OLD_PAYER))
                    .isInstanceOf(BusinessException.class)
                    .extracting(BillingPayerHandoverServiceTest::errorCodeOf)
                    .isEqualTo(EntitlementErrorCode.HANDOVER_NO_CANDIDATE);
        }

        @Test
        @DisplayName("進行中（非終端）の要求が既にあれば HANDOVER_ALREADY_IN_PROGRESS")
        void alreadyInProgress() {
            given(billingContractRepository.findByIdAndDeletedAtIsNull(oldContractId))
                    .willReturn(Optional.of(eligibleContract()));
            given(roleService.getUserIdsByTeamIdAndRoleName(TEAM_ID, "ADMIN"))
                    .willReturn(List.of(OLD_PAYER, NEW_PAYER));
            given(handoverRequestRepository.findByOldContractIdAndStatusNotIn(eq(oldContractId), any()))
                    .willReturn(List.of(new BillingPayerHandoverRequestEntity()));

            assertThatThrownBy(() -> service.requestHandover(
                    EntitlementScopeKind.TEAM, TEAM_ID, oldContractId, OLD_PAYER))
                    .isInstanceOf(BusinessException.class)
                    .extracting(BillingPayerHandoverServiceTest::errorCodeOf)
                    .isEqualTo(EntitlementErrorCode.HANDOVER_ALREADY_IN_PROGRESS);
            verify(handoverRequestRepository, never()).save(any());
        }

        @Test
        @DisplayName("AC-11: 認可（requireCanManage）は要求作成の前に必ず呼ばれる")
        void ac11_authorizerInvoked() {
            givenEligibleContractAndCandidates();
            service.requestHandover(EntitlementScopeKind.TEAM, TEAM_ID, oldContractId, OLD_PAYER);
            verify(billingOperationAuthorizer)
                    .requireCanManage(OLD_PAYER, EntitlementScopeKind.TEAM, TEAM_ID);
        }
    }

    // ============================================================
    // acceptHandover
    // ============================================================

    @Nested
    @DisplayName("acceptHandover（承諾・二段検証の1段目と回復経路）")
    class AcceptHandover {

        private AcceptValidation validation(String existingRef) {
            return new AcceptValidation(handoverId, EntitlementScopeKind.TEAM, TEAM_ID,
                    oldContractId, PayerHandoverStatus.REQUESTED, existingRef, null);
        }

        private AcceptTransition transition(String existingRef) {
            return new AcceptTransition(handoverId, newContractId, NEW_PAYER, oldContractId,
                    2000, "Mannschaft プラン: FULL", OLD_PERIOD_END_INSTANT, existingRef);
        }

        @Test
        @DisplayName("AC-12: 対象行を FOR UPDATE でロックする経路（validateAcceptable）を必ず通る")
        void ac12_locksRowBeforeStateDecision() {
            given(handoverTxService.validateAcceptable(
                    EntitlementScopeKind.TEAM, TEAM_ID, handoverId, NEW_PAYER))
                    .willReturn(validation(null));
            given(billingPaymentGateway.hasUsablePaymentMethod(NEW_PAYER)).willReturn(true);
            given(handoverTxService.transitionToAccepted(
                    EntitlementScopeKind.TEAM, TEAM_ID, handoverId, NEW_PAYER))
                    .willReturn(transition(null));
            given(billingPaymentGateway.findHandoverSubscriptionRef(NEW_PAYER, handoverId))
                    .willReturn(Optional.empty());
            given(billingPaymentGateway.createHandoverSubscriptionCheckout(
                    any(), anyInt(), anyString(), any(), any(), any(), any(), any(), any()))
                    .willReturn(new CheckoutSessionInfo("cs_1", "https://checkout.stripe.com/c/cs_1"));

            service.acceptHandover(EntitlementScopeKind.TEAM, TEAM_ID, handoverId, NEW_PAYER);

            // 行ロック＋認可＋期限＋状態判定は validateAcceptable（FOR UPDATE 取得）に集約されている。
            verify(handoverTxService).validateAcceptable(
                    EntitlementScopeKind.TEAM, TEAM_ID, handoverId, NEW_PAYER);
        }

        @Test
        @DisplayName("IDOR: 別スコープの handoverRequestId は HANDOVER_NOT_FOUND（404）で、状態は一切変化しない")
        void crossScopeHandoverIdIsHiddenAs404() {
            given(handoverTxService.validateAcceptable(
                    EntitlementScopeKind.TEAM, OTHER_TEAM_ID, handoverId, NEW_PAYER))
                    .willThrow(new BusinessException(EntitlementErrorCode.HANDOVER_NOT_FOUND));

            assertThatThrownBy(() -> service.acceptHandover(
                    EntitlementScopeKind.TEAM, OTHER_TEAM_ID, handoverId, NEW_PAYER))
                    .isInstanceOf(BusinessException.class)
                    .extracting(BillingPayerHandoverServiceTest::errorCodeOf)
                    .isEqualTo(EntitlementErrorCode.HANDOVER_NOT_FOUND);

            verify(handoverTxService, never()).transitionToAccepted(any(), any(), any(), any());
            verify(handoverTxService, never()).transitionToRequiresPaymentMethod(any(), any(), any(), any());
            verifyNoInteractions(billingPaymentGateway);
        }

        @Test
        @DisplayName("AC-16: 支払い手段が無ければ REQUIRES_PAYMENT_METHOD を返し、旧契約に一切触れない")
        void ac16_noPaymentMethod_fallsBackWithoutTouchingOldContract() {
            given(handoverTxService.validateAcceptable(
                    EntitlementScopeKind.TEAM, TEAM_ID, handoverId, NEW_PAYER))
                    .willReturn(validation(null));
            given(billingPaymentGateway.hasUsablePaymentMethod(NEW_PAYER)).willReturn(false);

            HandoverAcceptResult result = service.acceptHandover(
                    EntitlementScopeKind.TEAM, TEAM_ID, handoverId, NEW_PAYER);

            // 例外ではなく結果の status で返す。
            assertThat(result.status()).isEqualTo(PayerHandoverStatus.REQUIRES_PAYMENT_METHOD);
            assertThat(result.newContractId()).isNull();
            assertThat(result.checkoutUrl()).isNull();
            verify(handoverTxService).transitionToRequiresPaymentMethod(
                    EntitlementScopeKind.TEAM, TEAM_ID, handoverId, NEW_PAYER);

            // ★旧契約は無傷: 新契約も作らず、旧サブスクの期末解約予約も差し戻しも行わない。
            verify(handoverTxService, never()).transitionToAccepted(any(), any(), any(), any());
            verify(billingPaymentGateway, never()).scheduleCancelAtPeriodEndForHandover(any(), any());
            verify(billingPaymentGateway, never()).revertCancelAtPeriodEndForHandover(any(), any());
            verify(billingPaymentGateway, never()).createHandoverSubscriptionCheckout(
                    any(), anyInt(), anyString(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("AC-7①: DB に psp_new_subscription_ref があれば新サブスクを作成しない（List 照会も不要）")
        void ac7_dbRefPresent_skipsCreation() {
            given(handoverTxService.validateAcceptable(
                    EntitlementScopeKind.TEAM, TEAM_ID, handoverId, NEW_PAYER))
                    .willReturn(validation(NEW_SUB));
            given(billingPaymentGateway.hasUsablePaymentMethod(NEW_PAYER)).willReturn(true);
            given(handoverTxService.transitionToAccepted(
                    EntitlementScopeKind.TEAM, TEAM_ID, handoverId, NEW_PAYER))
                    .willReturn(transition(NEW_SUB));

            HandoverAcceptResult result = service.acceptHandover(
                    EntitlementScopeKind.TEAM, TEAM_ID, handoverId, NEW_PAYER);

            assertThat(result.status()).isEqualTo(PayerHandoverStatus.ACCEPTED);
            assertThat(result.newContractId()).isEqualTo(newContractId);
            verify(billingPaymentGateway, never()).createHandoverSubscriptionCheckout(
                    any(), anyInt(), anyString(), any(), any(), any(), any(), any(), any());
            verify(billingPaymentGateway, never()).findHandoverSubscriptionRef(anyLong(), any());
        }

        @Test
        @DisplayName("AC-7②/AC-25: DB は空でも List 照会でヒットすれば作成せず、その ID を DB へ書き戻す")
        void ac7_listRecoveryHit_skipsCreationAndPersists() {
            given(handoverTxService.validateAcceptable(
                    EntitlementScopeKind.TEAM, TEAM_ID, handoverId, NEW_PAYER))
                    .willReturn(validation(null));
            given(billingPaymentGateway.hasUsablePaymentMethod(NEW_PAYER)).willReturn(true);
            given(handoverTxService.transitionToAccepted(
                    EntitlementScopeKind.TEAM, TEAM_ID, handoverId, NEW_PAYER))
                    .willReturn(transition(null));
            given(billingPaymentGateway.findHandoverSubscriptionRef(NEW_PAYER, handoverId))
                    .willReturn(Optional.of(NEW_SUB));

            HandoverAcceptResult result = service.acceptHandover(
                    EntitlementScopeKind.TEAM, TEAM_ID, handoverId, NEW_PAYER);

            assertThat(result.status()).isEqualTo(PayerHandoverStatus.ACCEPTED);
            verify(handoverTxService).persistNewSubscriptionRef(handoverId, NEW_SUB);
            verify(billingPaymentGateway, never()).createHandoverSubscriptionCheckout(
                    any(), anyInt(), anyString(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("AC-7③/AC-5: DB も List も空のときだけ作成し、trial_end は旧 current_period_end と同一 unix 秒")
        void ac7_bothEmpty_createsWithTrialEndEqualToOldPeriodEnd() {
            given(handoverTxService.validateAcceptable(
                    EntitlementScopeKind.TEAM, TEAM_ID, handoverId, NEW_PAYER))
                    .willReturn(validation(null));
            given(billingPaymentGateway.hasUsablePaymentMethod(NEW_PAYER)).willReturn(true);
            given(handoverTxService.transitionToAccepted(
                    EntitlementScopeKind.TEAM, TEAM_ID, handoverId, NEW_PAYER))
                    .willReturn(transition(null));
            given(billingPaymentGateway.findHandoverSubscriptionRef(NEW_PAYER, handoverId))
                    .willReturn(Optional.empty());
            given(billingPaymentGateway.createHandoverSubscriptionCheckout(
                    any(), anyInt(), anyString(), any(), any(), any(), any(), any(), any()))
                    .willReturn(new CheckoutSessionInfo("cs_1", "https://checkout.stripe.com/c/cs_1"));

            HandoverAcceptResult result = service.acceptHandover(
                    EntitlementScopeKind.TEAM, TEAM_ID, handoverId, NEW_PAYER);

            assertThat(result.checkoutUrl()).isEqualTo("https://checkout.stripe.com/c/cs_1");

            ArgumentCaptor<Instant> trialEnd = ArgumentCaptor.forClass(Instant.class);
            verify(billingPaymentGateway).createHandoverSubscriptionCheckout(
                    eq(NEW_PAYER), eq(2000), anyString(), eq(newContractId), eq(oldContractId),
                    eq(handoverId), trialEnd.capture(), anyString(), anyString());

            // ★AC-5: 旧期末と新 trial 終了が同一 unix 秒（隙間も重複も生じない）。
            assertThat(trialEnd.getValue().getEpochSecond())
                    .isEqualTo(OLD_PERIOD_END_INSTANT.getEpochSecond());
            assertThat(trialEnd.getValue()).isEqualTo(OLD_PERIOD_END_INSTANT);
            // trial_end は未来であること（過去だと Stripe が 400 を返す）。
            assertThat(trialEnd.getValue()).isAfter(NOW);
        }
    }

    // ============================================================
    // onHandoverCheckoutCompleted
    // ============================================================

    @Nested
    @DisplayName("onHandoverCheckoutCompleted（(a) 引継確定条件）")
    class CheckoutCompleted {

        @Test
        @DisplayName("AC-6/AC-31: SWITCHING 遷移と同時に旧サブスクへ cancel_at_period_end を予約し、成功時刻を永続化する")
        void ac6_schedulesCancelOnOldSubscription() {
            given(handoverTxService.markSwitching(handoverId, NEW_SUB))
                    .willReturn(new CheckoutCompletion(handoverId, OLD_SUB, NEW_SUB));
            given(billingPaymentGateway.retrieveSubscription(NEW_SUB))
                    .willReturn(new SubscriptionSnapshot(NEW_SUB, "trialing", false,
                            NOW, OLD_PERIOD_END_INSTANT, null));

            service.onHandoverCheckoutCompleted(handoverId, NEW_SUB);

            verify(handoverTxService).markSwitching(handoverId, NEW_SUB);
            verify(billingPaymentGateway).scheduleCancelAtPeriodEndForHandover(OLD_SUB, handoverId);
            verify(handoverTxService).persistOldCancelScheduledAt(handoverId, NOW);
            // 旧サブスクへの即時解約は本方式では使わない（R3-P1-3）。
            verify(billingPaymentGateway, never()).cancelImmediately(any());
        }

        @Test
        @DisplayName("冪等: 既に SWITCHING 以降なら旧サブスクへ再設定しない")
        void idempotentWhenAlreadySwitching() {
            given(handoverTxService.markSwitching(handoverId, NEW_SUB)).willReturn(null);

            service.onHandoverCheckoutCompleted(handoverId, NEW_SUB);

            verify(billingPaymentGateway, never()).scheduleCancelAtPeriodEndForHandover(any(), any());
            verify(handoverTxService, never()).persistOldCancelScheduledAt(any(), any());
        }

        @Test
        @DisplayName("AC-30 1段目: pending_setup_intent が非 NULL なら通知のみで、状態遷移はさせない")
        void ac30_firstStage_notifiesOnly() {
            given(handoverTxService.markSwitching(handoverId, NEW_SUB))
                    .willReturn(new CheckoutCompletion(handoverId, OLD_SUB, NEW_SUB));
            given(billingPaymentGateway.retrieveSubscription(NEW_SUB))
                    .willReturn(new SubscriptionSnapshot(NEW_SUB, "trialing", false,
                            NOW, OLD_PERIOD_END_INSTANT, "seti_123"));

            service.onHandoverCheckoutCompleted(handoverId, NEW_SUB);

            verify(handoverTxService).publishAdditionalAuthRequired(handoverId);
            // 1段目では FAILED にしない（旧の cancel_at_period_end は設定済みで引継は進行中扱い）。
            verify(handoverTxService, never()).markFailedAndClearCancelSchedule(any());
            verify(billingPaymentGateway, never()).cancelHandoverNewSubscription(any(), any());
        }

        @Test
        @DisplayName("pending_setup_intent が NULL なら追加認証通知は送らない")
        void noPendingSetupIntent_noNotification() {
            given(handoverTxService.markSwitching(handoverId, NEW_SUB))
                    .willReturn(new CheckoutCompletion(handoverId, OLD_SUB, NEW_SUB));
            given(billingPaymentGateway.retrieveSubscription(NEW_SUB))
                    .willReturn(new SubscriptionSnapshot(NEW_SUB, "trialing", false,
                            NOW, OLD_PERIOD_END_INSTANT, null));

            service.onHandoverCheckoutCompleted(handoverId, NEW_SUB);

            verify(handoverTxService, never()).publishAdditionalAuthRequired(any());
        }
    }

    // ============================================================
    // executeSwitch
    // ============================================================

    @Nested
    @DisplayName("executeSwitch（(b) pointer 切替条件）")
    class ExecuteSwitch {

        private SwitchContext ctx() {
            return new SwitchContext(handoverId, OLD_SUB, NEW_SUB, OLD_PERIOD_END_INSTANT);
        }

        @Test
        @DisplayName("AC-6/AC-27: 旧が cancel_at_period_end=true なら、Stripe を追加で呼ばずローカル切替TXのみ実行する")
        void ac6_switchTxCallsNoStripe() {
            given(handoverTxService.loadSwitchContext(handoverId)).willReturn(ctx());
            given(billingPaymentGateway.retrieveSubscription(NEW_SUB))
                    .willReturn(new SubscriptionSnapshot(NEW_SUB, "trialing", false,
                            NOW, OLD_PERIOD_END_INSTANT, null));
            given(billingPaymentGateway.retrieveSubscription(OLD_SUB))
                    .willReturn(new SubscriptionSnapshot(OLD_SUB, "active", true,
                            NOW.minusSeconds(86400), OLD_PERIOD_END_INSTANT, null));

            service.executeSwitch(handoverId);

            verify(handoverTxService).executeSwitchTx(handoverId);
            // ★切替TXの前後で Stripe に行うのは実物確認（retrieve）2 件のみ。
            //   変更系（解約予約・差し戻し・取消・Checkout 生成）は一切呼ばない。
            verify(billingPaymentGateway).retrieveSubscription(NEW_SUB);
            verify(billingPaymentGateway).retrieveSubscription(OLD_SUB);
            verifyNoMoreInteractions(billingPaymentGateway);
        }

        @Test
        @DisplayName("AC-30 2段目/AC-32: pending_setup_intent 未解決なら切替せず FAILED＋新取消＋旧差し戻し＋予約時刻 NULL クリア")
        void ac30_secondStage_failsAndReverts() {
            given(handoverTxService.loadSwitchContext(handoverId)).willReturn(ctx());
            given(billingPaymentGateway.retrieveSubscription(NEW_SUB))
                    .willReturn(new SubscriptionSnapshot(NEW_SUB, "trialing", false,
                            NOW, OLD_PERIOD_END_INSTANT, "seti_123"));

            service.executeSwitch(handoverId);

            // ①切替TXは実行されない
            verify(handoverTxService, never()).executeSwitchTx(any());
            // ②新 trial サブスクを無課金取消
            verify(billingPaymentGateway).cancelHandoverNewSubscription(NEW_SUB, handoverId);
            // ③旧サブスクを継続へ差し戻し
            verify(billingPaymentGateway).revertCancelAtPeriodEndForHandover(OLD_SUB, handoverId);
            // ④FAILED 確定と old_cancel_scheduled_at の NULL クリアは対で行う（R5-P2）
            verify(handoverTxService).markFailedAndClearCancelSchedule(handoverId);
        }

        @Test
        @DisplayName("AC-35①: cancel_at_period_end=false かつ期末境界越えなし → その場で設定してから切替する")
        void ac35_notRolledOver_setsThenSwitches() {
            given(handoverTxService.loadSwitchContext(handoverId)).willReturn(ctx());
            given(billingPaymentGateway.retrieveSubscription(NEW_SUB))
                    .willReturn(new SubscriptionSnapshot(NEW_SUB, "trialing", false,
                            NOW, OLD_PERIOD_END_INSTANT, null));
            // current_period_start が旧期末より前 ＝ まだ旧期間内（境界越えなし）。
            given(billingPaymentGateway.retrieveSubscription(OLD_SUB))
                    .willReturn(new SubscriptionSnapshot(OLD_SUB, "active", false,
                            OLD_PERIOD_END_INSTANT.minusSeconds(1), OLD_PERIOD_END_INSTANT, null));

            service.executeSwitch(handoverId);

            verify(billingPaymentGateway).scheduleCancelAtPeriodEndForHandover(OLD_SUB, handoverId);
            verify(handoverTxService).persistOldCancelScheduledAt(handoverId, NOW);
            verify(handoverTxService).executeSwitchTx(handoverId);
            verify(handoverTxService, never()).markManualIntervention(any());
        }

        @Test
        @DisplayName("AC-35②: cancel_at_period_end=false かつ期末境界越えあり → MANUAL_INTERVENTION・切替TX未実行・自動の金銭操作なし")
        void ac35_rolledOver_manualIntervention() {
            given(handoverTxService.loadSwitchContext(handoverId)).willReturn(ctx());
            given(billingPaymentGateway.retrieveSubscription(NEW_SUB))
                    .willReturn(new SubscriptionSnapshot(NEW_SUB, "trialing", false,
                            NOW, OLD_PERIOD_END_INSTANT, null));
            // current_period_start が旧期末以降 ＝ 既に次の期間へ更新済み（境界越え）。
            given(billingPaymentGateway.retrieveSubscription(OLD_SUB))
                    .willReturn(new SubscriptionSnapshot(OLD_SUB, "active", false,
                            OLD_PERIOD_END_INSTANT, OLD_PERIOD_END_INSTANT.plusSeconds(2592000), null));

            service.executeSwitch(handoverId);

            verify(handoverTxService).markManualIntervention(handoverId);
            verify(handoverTxService, never()).executeSwitchTx(any());
            // ★自動での true 設定・即時解約・差し戻しはいずれも行わない（R5-P1-1 裁定）。
            verify(billingPaymentGateway, never()).scheduleCancelAtPeriodEndForHandover(any(), any());
            verify(billingPaymentGateway, never()).cancelImmediately(any());
            verify(billingPaymentGateway, never()).cancelHandoverNewSubscription(any(), any());
            verify(billingPaymentGateway, never()).revertCancelAtPeriodEndForHandover(any(), any());
        }

        @Test
        @DisplayName("切替TX の DB 書き込み失敗は PARTIALLY_COMPLETED（非終端・リトライ対象。FAILED にしない）")
        void switchTxFailure_marksPartiallyCompleted() {
            given(handoverTxService.loadSwitchContext(handoverId)).willReturn(ctx());
            given(billingPaymentGateway.retrieveSubscription(NEW_SUB))
                    .willReturn(new SubscriptionSnapshot(NEW_SUB, "trialing", false,
                            NOW, OLD_PERIOD_END_INSTANT, null));
            given(billingPaymentGateway.retrieveSubscription(OLD_SUB))
                    .willReturn(new SubscriptionSnapshot(OLD_SUB, "active", true,
                            NOW.minusSeconds(86400), OLD_PERIOD_END_INSTANT, null));
            org.mockito.BDDMockito.willThrow(new IllegalStateException("db down"))
                    .given(handoverTxService).executeSwitchTx(handoverId);

            service.executeSwitch(handoverId);

            verify(handoverTxService).markPartiallyCompleted(handoverId);
            verify(handoverTxService, never()).markFailedAndClearCancelSchedule(any());
        }

        @Test
        @DisplayName("対象外（SWITCHING でない等）は no-op で Stripe を一切呼ばない")
        void notSwitchable_isNoOp() {
            given(handoverTxService.loadSwitchContext(handoverId)).willReturn(null);

            service.executeSwitch(handoverId);

            verifyNoInteractions(billingPaymentGateway);
            verify(handoverTxService, never()).executeSwitchTx(any());
        }
    }

    // ============================================================
    // findSwitchDueHandoverIds
    // ============================================================

    @Test
    @DisplayName("findSwitchDueHandoverIds: SWITCHING かつ旧期末到達済みの ID を返す（期末は契約側の壁時計で比較）")
    void findSwitchDue_delegatesWithConvertedWallClock() {
        UUID due = UUID.randomUUID();
        given(handoverRequestRepository.findSwitchDueIds(
                eq(PayerHandoverStatus.SWITCHING), any(LocalDateTime.class)))
                .willReturn(List.of(due));

        List<UUID> result = service.findSwitchDueHandoverIds(NOW);

        assertThat(result).containsExactly(due);
        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(handoverRequestRepository)
                .findSwitchDueIds(eq(PayerHandoverStatus.SWITCHING), cutoff.capture());
        // Instant → billing_contracts の壁時計へ、同じ Clock の zone で変換される。
        assertThat(cutoff.getValue()).isEqualTo(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
    }
}
