package com.mannschaft.app.billing;

import com.mannschaft.app.common.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * F20.1 実決済（D-1〜D-4・2026-07-10 御裁可）: {@link BillingContractService} の決済フロー単体テスト（試練）。
 *
 * <p>対象 AC:</p>
 * <ul>
 *   <li>AC-32: 決済フロー起票は PENDING＋価格焼付＋pointer 確保・<b>entitlements 未発行</b></li>
 *   <li>AC-33: checkout.session.completed（activatePaidContract）で初めて ACTIVE＋発行＋PSP 焼付</li>
 *   <li>AC-34: activatePaidContract は冪等（既に ACTIVE なら二重発行ゼロ）</li>
 *   <li>AC-35: 有償解約=期末解約（cancel_at_period_end・ACTIVE 維持・valid_until=期末・半開区間）・
 *       subscription.deleted（expireSubscriptionContract）で EXPIRED＋失効</li>
 *   <li>AC-36: 無償解約=即時失効（既存フロー不変）</li>
 *   <li>AC-37: invoice.payment_failed→PAST_DUE（権利は触らない）・invoice.paid で回復</li>
 *   <li>AC-40: 価格入力前に結ばれた無償契約（snapshot NULL）は遡及されない（解約も即時のまま）</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BillingContractService 決済フロー単体テスト（PENDING/ACTIVE化/期末解約/PAST_DUE）")
class BillingContractServicePaymentTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-07-10T00:00:00Z"), ZoneOffset.UTC);
    private static final LocalDateTime NOW = LocalDateTime.ofInstant(FIXED_CLOCK.instant(), ZoneOffset.UTC);
    private static final LocalDateTime PERIOD_END = NOW.plusMonths(1);

    @Mock private BillingContractRepository billingContractRepository;
    @Mock private ActiveContractPointerRepository activeContractPointerRepository;
    @Mock private EntitlementRepository entitlementRepository;
    @Mock private PlanRepository planRepository;
    @Mock private PlanFeatureRepository planFeatureRepository;
    @Mock private FeatureCatalogRepository featureCatalogRepository;
    @Mock private PlanPriceBandRepository planPriceBandRepository;
    @Mock private ScopeMemberCountService scopeMemberCountService;
    @Mock private EntitlementCacheEvictor cacheEvictor;
    @Mock private BillingPaymentGateway billingPaymentGateway;

    private BillingContractService service;

    @BeforeEach
    void setUp() {
        service = new BillingContractService(
                billingContractRepository, activeContractPointerRepository, entitlementRepository,
                planRepository, planFeatureRepository, featureCatalogRepository, planPriceBandRepository,
                scopeMemberCountService, cacheEvictor, FIXED_CLOCK, billingPaymentGateway);
    }

    // ============================================================
    // ヘルパ
    // ============================================================

    private PlanEntity plan(String key) {
        return PlanEntity.builder().planKey(key)
                .displayNameKey("k.name").descriptionKey("k.desc").sortOrder(1).enabled(true).build();
    }

    private PlanFeatureEntity pf(String planKey, String featureKey) {
        return PlanFeatureEntity.builder().planKey(planKey).featureKey(featureKey).build();
    }

    private void stubSaveAssignsId() {
        given(billingContractRepository.save(any(BillingContractEntity.class))).willAnswer(inv -> {
            BillingContractEntity e = inv.getArgument(0);
            if (e.getId() == null) {
                e.setId(UUID.randomUUID());
            }
            return e;
        });
    }

    /** 決済フローの契約行（status/snapshot/PSP を指定して生成）。 */
    private BillingContractEntity contract(
            UUID id, ContractStatus status, Integer priceJpySnapshot, String pspSubscriptionRef) {
        BillingContractEntity c = BillingContractEntity.builder()
                .scopeKind(EntitlementScopeKind.USER).scopeId(9L)
                .contractKind(ContractKind.PLAN).planKey("FULL")
                .status(status).priceJpySnapshot(priceJpySnapshot)
                .pspSubscriptionRef(pspSubscriptionRef)
                .contractedAt(NOW).build();
        c.setId(id);
        return c;
    }

    private EntitlementEntity ent(String featureKey) {
        return EntitlementEntity.builder()
                .scopeKind(EntitlementScopeKind.USER).scopeId(9L).featureKey(featureKey)
                .sourceKind(EntitlementSourceKind.PLAN).sourceRefId(UUID.randomUUID())
                .validFrom(NOW.minusDays(1)).build();
    }

    // ============================================================
    // AC-32: 決済フロー起票（PENDING・entitlements 未発行）
    // ============================================================

    @Test
    @DisplayName("AC-32: createPendingPaidContract は PENDING＋価格焼付＋pointer 確保・entitlements を発行しない")
    void ac32_createPendingPaidContract_pendingWithoutEntitlements() {
        stubSaveAssignsId();
        given(planRepository.findById("FULL")).willReturn(Optional.of(plan("FULL")));
        given(planFeatureRepository.findByPlanKey("FULL")).willReturn(List.of(pf("FULL", "ads.hide")));

        BillingContractService.ContractResult result = service.createPendingPaidContract(
                EntitlementScopeKind.USER, 9L, null, ContractKind.PLAN, "FULL", null, 2000, 9L);

        assertThat(result.status()).isEqualTo(ContractStatus.PENDING);
        assertThat(result.priceJpySnapshot()).isEqualTo(2000);
        assertThat(result.grantedFeatureKeys()).isEmpty();

        // 契約行は PENDING＋price_jpy_snapshot=2000 で保存される。
        ArgumentCaptor<BillingContractEntity> captor = ArgumentCaptor.forClass(BillingContractEntity.class);
        verify(billingContractRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(ContractStatus.PENDING);
        assertThat(captor.getValue().getPriceJpySnapshot()).isEqualTo(2000);

        // pointer はスロット確保のため saveAndFlush される（uk_acp_slot 物理担保）。
        verify(activeContractPointerRepository).saveAndFlush(any(ActiveContractPointerEntity.class));
        // ★entitlements は未発行（入金 webhook で初めて発行・AC-33）。
        verify(entitlementRepository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("AC-32: PENDING スロット占有中の再契約は ENTITLEMENT_016（CONTRACT_PENDING_PAYMENT・409）")
    void ac32_pendingSlotConflict_throws016() {
        stubSaveAssignsId();
        given(planRepository.findById("FULL")).willReturn(Optional.of(plan("FULL")));
        given(planFeatureRepository.findByPlanKey("FULL")).willReturn(List.of(pf("FULL", "ads.hide")));
        given(activeContractPointerRepository.saveAndFlush(any()))
                .willThrow(new DataIntegrityViolationException("uk_acp_slot"));

        // 既存スロットの契約は PENDING（入金前）。
        UUID existingId = UUID.randomUUID();
        ActiveContractPointerEntity pointer = ActiveContractPointerEntity.builder()
                .scopeKind(EntitlementScopeKind.USER).scopeId(9L)
                .contractKind(ContractKind.PLAN).addonFeatureKey("").contractId(existingId).build();
        given(activeContractPointerRepository.findByScopeKindAndScopeIdAndContractKindAndAddonFeatureKey(
                EntitlementScopeKind.USER, 9L, ContractKind.PLAN, ""))
                .willReturn(Optional.of(pointer));
        given(billingContractRepository.findByIdAndDeletedAtIsNull(existingId))
                .willReturn(Optional.of(contract(existingId, ContractStatus.PENDING, 2000, null)));

        assertThatThrownBy(() -> service.createPendingPaidContract(
                EntitlementScopeKind.USER, 9L, null, ContractKind.PLAN, "FULL", null, 2000, 9L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(EntitlementErrorCode.CONTRACT_PENDING_PAYMENT);
    }

    @Test
    @DisplayName("AC-32: ACTIVE スロット占有中の再契約は従来どおり ENTITLEMENT_006（CONTRACT_ALREADY_ACTIVE・409）")
    void ac32_activeSlotConflict_throws006() {
        stubSaveAssignsId();
        given(planRepository.findById("FULL")).willReturn(Optional.of(plan("FULL")));
        given(planFeatureRepository.findByPlanKey("FULL")).willReturn(List.of(pf("FULL", "ads.hide")));
        given(activeContractPointerRepository.saveAndFlush(any()))
                .willThrow(new DataIntegrityViolationException("uk_acp_slot"));

        UUID existingId = UUID.randomUUID();
        ActiveContractPointerEntity pointer = ActiveContractPointerEntity.builder()
                .scopeKind(EntitlementScopeKind.USER).scopeId(9L)
                .contractKind(ContractKind.PLAN).addonFeatureKey("").contractId(existingId).build();
        given(activeContractPointerRepository.findByScopeKindAndScopeIdAndContractKindAndAddonFeatureKey(
                EntitlementScopeKind.USER, 9L, ContractKind.PLAN, ""))
                .willReturn(Optional.of(pointer));
        given(billingContractRepository.findByIdAndDeletedAtIsNull(existingId))
                .willReturn(Optional.of(contract(existingId, ContractStatus.ACTIVE, null, null)));

        assertThatThrownBy(() -> service.createPendingPaidContract(
                EntitlementScopeKind.USER, 9L, null, ContractKind.PLAN, "FULL", null, 2000, 9L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(EntitlementErrorCode.CONTRACT_ALREADY_ACTIVE);
    }

    // ============================================================
    // AC-33: 入金確定で初めて ACTIVE＋発行
    // ============================================================

    @Test
    @DisplayName("AC-33: activatePaidContract は PENDING→ACTIVE・PSP 参照/期末焼付・entitlements 発行・evict")
    void ac33_activatePaidContract_issuesEntitlements() {
        UUID id = UUID.randomUUID();
        BillingContractEntity pending = contract(id, ContractStatus.PENDING, 2000, null);
        given(billingContractRepository.findByIdAndDeletedAtIsNull(id)).willReturn(Optional.of(pending));
        given(billingContractRepository.save(any(BillingContractEntity.class))).willAnswer(inv -> inv.getArgument(0));
        given(planFeatureRepository.findByPlanKey("FULL")).willReturn(List.of(pf("FULL", "ads.hide")));
        given(entitlementRepository.saveAll(anyList())).willAnswer(inv -> inv.getArgument(0));

        BillingContractService.ContractResult result =
                service.activatePaidContract(id, "cus_1", "sub_1", PERIOD_END);

        assertThat(result.status()).isEqualTo(ContractStatus.ACTIVE);
        assertThat(pending.getStatus()).isEqualTo(ContractStatus.ACTIVE);
        assertThat(pending.getPspCustomerRef()).isEqualTo("cus_1");
        assertThat(pending.getPspSubscriptionRef()).isEqualTo("sub_1");
        assertThat(pending.getCurrentPeriodEnd()).isEqualTo(PERIOD_END);

        // ★入金確定で初めて entitlements 発行＋evict。
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<EntitlementEntity>> entCaptor = ArgumentCaptor.forClass(List.class);
        verify(entitlementRepository).saveAll(entCaptor.capture());
        assertThat(entCaptor.getValue()).extracting(EntitlementEntity::getFeatureKey).containsExactly("ads.hide");
        verify(cacheEvictor).evictScopeFeatures(eq(EntitlementScopeKind.USER), eq(9L), any());
    }

    @Test
    @DisplayName("AC-34: activatePaidContract は冪等（既に ACTIVE なら二重発行ゼロ・no-op）")
    void ac34_activatePaidContract_idempotentOnActive() {
        UUID id = UUID.randomUUID();
        BillingContractEntity active = contract(id, ContractStatus.ACTIVE, 2000, "sub_1");
        given(billingContractRepository.findByIdAndDeletedAtIsNull(id)).willReturn(Optional.of(active));
        given(planFeatureRepository.findByPlanKey("FULL")).willReturn(List.of(pf("FULL", "ads.hide")));

        BillingContractService.ContractResult result =
                service.activatePaidContract(id, "cus_1", "sub_1", PERIOD_END);

        assertThat(result.status()).isEqualTo(ContractStatus.ACTIVE);
        // 二重発行ゼロ: saveAll も save も呼ばれない。
        verify(entitlementRepository, never()).saveAll(anyList());
        verify(billingContractRepository, never()).save(any());
    }

    @Test
    @DisplayName("AC-33 補: 未達なら未発行のまま（PENDING の契約に対して発行系は一切走らない前提の確認）")
    void ac33_notDelivered_noEntitlements() {
        // activatePaidContract を呼ばない限り entitlements は発行されない（AC-32 の検証と対）。
        // ここでは abandonPendingContract（期限切れ放棄）でも発行されないことを確認する。
        UUID id = UUID.randomUUID();
        BillingContractEntity pending = contract(id, ContractStatus.PENDING, 2000, null);
        given(billingContractRepository.findByIdAndDeletedAtIsNull(id)).willReturn(Optional.of(pending));
        given(billingContractRepository.save(any(BillingContractEntity.class))).willAnswer(inv -> inv.getArgument(0));

        service.abandonPendingContract(id);

        assertThat(pending.getStatus()).isEqualTo(ContractStatus.CANCELLED);
        // pointer 解放（再挑戦可能に）＋entitlements 発行なし。
        verify(activeContractPointerRepository).hardDeleteBySlot(
                EntitlementScopeKind.USER, 9L, ContractKind.PLAN, "");
        verify(entitlementRepository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("AC-34 補: abandonPendingContract は冪等（PENDING 以外は no-op）")
    void ac34_abandon_idempotent() {
        UUID id = UUID.randomUUID();
        given(billingContractRepository.findByIdAndDeletedAtIsNull(id))
                .willReturn(Optional.of(contract(id, ContractStatus.ACTIVE, 2000, "sub_1")));

        service.abandonPendingContract(id);

        verify(billingContractRepository, never()).save(any());
        verify(activeContractPointerRepository, never()).hardDeleteBySlot(any(), any(), any(), any());
    }

    // ============================================================
    // AC-35: 有償解約=期末解約
    // ============================================================

    @Test
    @DisplayName("AC-35: 有償解約は cancel_at_period_end・ACTIVE 維持・valid_until=期末・revoke しない・pointer 残置")
    void ac35_paidCancel_atPeriodEnd() {
        UUID id = UUID.randomUUID();
        BillingContractEntity paid = contract(id, ContractStatus.ACTIVE, 2000, "sub_1");
        given(billingContractRepository.findByIdAndDeletedAtIsNull(id)).willReturn(Optional.of(paid));
        given(billingContractRepository.save(any(BillingContractEntity.class))).willAnswer(inv -> inv.getArgument(0));
        Instant periodEndInstant = PERIOD_END.toInstant(ZoneOffset.UTC);
        given(billingPaymentGateway.cancelAtPeriodEnd("sub_1")).willReturn(periodEndInstant);

        EntitlementEntity e1 = ent("ads.hide");
        given(entitlementRepository.findBySourceKindAndSourceRefIdAndRevokedAtIsNull(
                EntitlementSourceKind.PLAN, id)).willReturn(List.of(e1));
        given(entitlementRepository.saveAll(anyList())).willAnswer(inv -> inv.getArgument(0));

        BillingContractService.ContractResult result =
                service.cancelContract(EntitlementScopeKind.USER, 9L, id, 9L);

        // 契約は ACTIVE のまま（EXPIRED 化は subscription.deleted webhook）・cancelled_at セット・期末を応答。
        assertThat(result.status()).isEqualTo(ContractStatus.ACTIVE);
        assertThat(result.currentPeriodEnd()).isEqualTo(PERIOD_END);
        assertThat(paid.getStatus()).isEqualTo(ContractStatus.ACTIVE);
        assertThat(paid.getCancelledAt()).isEqualTo(NOW);

        // ★由来 entitlements は revoke せず valid_until=期末（webhook 未達でも期末に自動失効する保険）。
        assertThat(e1.getRevokedAt()).isNull();
        assertThat(e1.getValidUntil()).isEqualTo(PERIOD_END);

        // ★半開区間: 期末ちょうどは false・期末 1 秒前は true（AC-35）。
        assertThat(e1.isActiveAt(PERIOD_END)).isFalse();
        assertThat(e1.isActiveAt(PERIOD_END.minusSeconds(1))).isTrue();

        // pointer は残置（EXPIRED 確定まで再契約させない）。
        verify(activeContractPointerRepository, never()).hardDeleteBySlot(any(), any(), any(), any());
        verify(billingPaymentGateway).cancelAtPeriodEnd("sub_1");
    }

    @Test
    @DisplayName("AC-35: customer.subscription.deleted（expireSubscriptionContract）で EXPIRED＋pointer DELETE＋残 revoke")
    void ac35_subscriptionDeleted_expires() {
        UUID id = UUID.randomUUID();
        BillingContractEntity paid = contract(id, ContractStatus.ACTIVE, 2000, "sub_1");
        given(billingContractRepository.findByPspSubscriptionRefAndDeletedAtIsNull("sub_1"))
                .willReturn(Optional.of(paid));
        given(billingContractRepository.save(any(BillingContractEntity.class))).willAnswer(inv -> inv.getArgument(0));

        EntitlementEntity e1 = ent("ads.hide");
        given(entitlementRepository.findBySourceKindAndSourceRefIdAndRevokedAtIsNull(
                EntitlementSourceKind.PLAN, id)).willReturn(List.of(e1));
        given(entitlementRepository.saveAll(anyList())).willAnswer(inv -> inv.getArgument(0));

        service.expireSubscriptionContract("sub_1", PERIOD_END);

        assertThat(paid.getStatus()).isEqualTo(ContractStatus.EXPIRED);
        assertThat(e1.getRevokedAt()).isEqualTo(NOW);
        verify(activeContractPointerRepository).hardDeleteBySlot(
                EntitlementScopeKind.USER, 9L, ContractKind.PLAN, "");
        verify(cacheEvictor).evictScopeFeatures(eq(EntitlementScopeKind.USER), eq(9L), any());
    }

    @Test
    @DisplayName("AC-35 冪等: 既に EXPIRED の契約への subscription.deleted 再送は no-op")
    void ac35_subscriptionDeleted_idempotent() {
        UUID id = UUID.randomUUID();
        given(billingContractRepository.findByPspSubscriptionRefAndDeletedAtIsNull("sub_1"))
                .willReturn(Optional.of(contract(id, ContractStatus.EXPIRED, 2000, "sub_1")));

        service.expireSubscriptionContract("sub_1", PERIOD_END);

        verify(billingContractRepository, never()).save(any());
        verify(activeContractPointerRepository, never()).hardDeleteBySlot(any(), any(), any(), any());
        verify(entitlementRepository, never()).saveAll(anyList());
    }

    // ============================================================
    // AC-36 / AC-40: 無償解約=即時失効（既存フロー不変・遡及なし）
    // ============================================================

    @Test
    @DisplayName("AC-36/AC-40: 無償契約（snapshot NULL）の解約は即時失効・gateway を呼ばない（遡及なし）")
    void ac36_freeCancel_immediate() {
        UUID id = UUID.randomUUID();
        BillingContractEntity free = contract(id, ContractStatus.ACTIVE, null, null);
        given(billingContractRepository.findByIdAndDeletedAtIsNull(id)).willReturn(Optional.of(free));
        given(billingContractRepository.save(any(BillingContractEntity.class))).willAnswer(inv -> inv.getArgument(0));

        EntitlementEntity e1 = ent("ads.hide");
        given(entitlementRepository.findBySourceKindAndSourceRefIdAndRevokedAtIsNull(
                EntitlementSourceKind.PLAN, id)).willReturn(List.of(e1));
        given(entitlementRepository.saveAll(anyList())).willAnswer(inv -> inv.getArgument(0));

        BillingContractService.ContractResult result =
                service.cancelContract(EntitlementScopeKind.USER, 9L, id, 9L);

        // 即時失効: CANCELLED＋revoke＋pointer DELETE。D-4: マスタに価格が後から入っても snapshot NULL なら無償のまま。
        assertThat(result.status()).isEqualTo(ContractStatus.CANCELLED);
        assertThat(free.getStatus()).isEqualTo(ContractStatus.CANCELLED);
        assertThat(e1.getRevokedAt()).isEqualTo(NOW);
        verify(activeContractPointerRepository).hardDeleteBySlot(
                EntitlementScopeKind.USER, 9L, ContractKind.PLAN, "");
        verify(billingPaymentGateway, never()).cancelAtPeriodEnd(any());
    }

    // ============================================================
    // AC-37: invoice.payment_failed → PAST_DUE / invoice.paid → 回復
    // ============================================================

    @Test
    @DisplayName("AC-37: markContractPastDue は ACTIVE→PAST_DUE・entitlements は一切触らない")
    void ac37_paymentFailed_pastDue_keepsEntitlements() {
        UUID id = UUID.randomUUID();
        BillingContractEntity paid = contract(id, ContractStatus.ACTIVE, 2000, "sub_1");
        given(billingContractRepository.findByPspSubscriptionRefAndDeletedAtIsNull("sub_1"))
                .willReturn(Optional.of(paid));
        given(billingContractRepository.save(any(BillingContractEntity.class))).willAnswer(inv -> inv.getArgument(0));

        service.markContractPastDue("sub_1");

        assertThat(paid.getStatus()).isEqualTo(ContractStatus.PAST_DUE);
        // ★権利は current_period_end まで維持（revoke も valid_until 変更もしない）。
        verify(entitlementRepository, never()).findBySourceKindAndSourceRefIdAndRevokedAtIsNull(any(), any());
        verify(entitlementRepository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("AC-37: extendContractPeriod は current_period_end 延長＋PAST_DUE→ACTIVE 回復")
    void ac37_invoicePaid_recovers() {
        UUID id = UUID.randomUUID();
        BillingContractEntity pastDue = contract(id, ContractStatus.PAST_DUE, 2000, "sub_1");
        given(billingContractRepository.findByPspSubscriptionRefAndDeletedAtIsNull("sub_1"))
                .willReturn(Optional.of(pastDue));
        given(billingContractRepository.save(any(BillingContractEntity.class))).willAnswer(inv -> inv.getArgument(0));

        LocalDateTime nextEnd = PERIOD_END.plusMonths(1);
        service.extendContractPeriod("sub_1", nextEnd);

        assertThat(pastDue.getStatus()).isEqualTo(ContractStatus.ACTIVE);
        assertThat(pastDue.getCurrentPeriodEnd()).isEqualTo(nextEnd);
    }

    @Test
    @DisplayName("AC-37 冪等: PENDING/CANCELLED への markContractPastDue は no-op（ACTIVE のみ遷移）")
    void ac37_pastDue_onlyFromActive() {
        UUID id = UUID.randomUUID();
        given(billingContractRepository.findByPspSubscriptionRefAndDeletedAtIsNull("sub_1"))
                .willReturn(Optional.of(contract(id, ContractStatus.CANCELLED, 2000, "sub_1")));

        service.markContractPastDue("sub_1");

        verify(billingContractRepository, never()).save(any());
    }
}
