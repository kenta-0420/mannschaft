package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.BillingContractChangeEntity;
import com.mannschaft.app.billing.BillingContractChangeKind;
import com.mannschaft.app.billing.BillingContractChangeRepository;
import com.mannschaft.app.billing.BillingContractChangeStatus;
import com.mannschaft.app.billing.BillingContractEntity;
import com.mannschaft.app.billing.BillingContractRepository;
import com.mannschaft.app.billing.ContractKind;
import com.mannschaft.app.billing.ContractStatus;
import com.mannschaft.app.billing.EntitlementQueryService;
import com.mannschaft.app.billing.EntitlementRepository;
import com.mannschaft.app.billing.EntitlementScopeKind;
import com.mannschaft.app.billing.BillingPriceBandVersionEntity;
import com.mannschaft.app.billing.BillingProductKind;
import com.mannschaft.app.billing.FeatureCatalogRepository;
import com.mannschaft.app.billing.PlanEntity;
import com.mannschaft.app.billing.PlanFeatureRepository;
import com.mannschaft.app.billing.PlanRepository;
import com.mannschaft.app.billing.ScopeMemberCountService;
import com.mannschaft.app.billing.api.dto.ActiveContract;
import com.mannschaft.app.billing.api.dto.EntitlementSummaryResponse;
import com.mannschaft.app.common.AccessControlService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.when;

/**
 * Billing Center PR6b-1 修繕（2巡目） — <b>P1 / P2-1</b> の純 UT。
 *
 * <p><b>P1（AC-71 が未成立だった）</b>: {@code BillingActiveContract.pendingChange} 投影が
 * {@code changeId} を持たないため、ページ再読込・別端末では FE が
 * {@code GET …/changes/{changeId}/payment-action} を組み立てられず、3DS を再開できなかった。
 * 正本 05_billing_center.md:293-295 は再ログイン・別端末からの再開を設計要件としている。</p>
 *
 * <p><b>P2-1（支払期限の誤表示）</b>: FE は期限として {@code effectiveAt}（＝変更行を作った時刻）を
 * 表示していた。実際の 3DS の期限は {@code billing_contract_changes.pending_update_expires_at} で
 * あり、投影がこれを運ばないため FE は正しい値を表示しようがなかった。</p>
 *
 * <p>IT（{@code BillingEntitlementPendingChangeProjectionRedIT}）は Docker 必須で環境によっては
 * 1本も走らないため、投影の中身は本純 UT でも独立に固定する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PR6b-1 修繕: pendingChange 投影が changeId と支払期限を運ぶ")
class BillingEntitlementQueryServicePendingChangeIdentityTest {

    private static final UUID CONTRACT_ID = UUID.fromString("00000000-0000-7000-8000-000000000001");
    private static final UUID CHANGE_ID = UUID.fromString("00000000-0000-7000-8000-000000000020");
    private static final Instant EFFECTIVE_AT = Instant.parse("2026-09-16T00:00:00Z");
    private static final Instant PENDING_UPDATE_EXPIRES_AT = Instant.parse("2026-09-16T23:00:00Z");

    @Mock private EntitlementQueryService entitlementQueryService;
    @Mock private EntitlementRepository entitlementRepository;
    @Mock private BillingContractRepository billingContractRepository;
    @Mock private BillingContractChangeRepository billingContractChangeRepository;
    @Mock private FeatureCatalogRepository featureCatalogRepository;
    @Mock private PlanFeatureRepository planFeatureRepository;
    @Mock private PlanRepository planRepository;
    @Mock private AccessControlService accessControlService;
    @Mock private BillingCurrentBandResolver currentBandResolver;
    @Mock private ScopeMemberCountService scopeMemberCountService;

    private BillingEntitlementQueryService service;

    @BeforeEach
    void setUp() {
        service = new BillingEntitlementQueryService(
                entitlementQueryService, entitlementRepository, billingContractRepository,
                billingContractChangeRepository, featureCatalogRepository, planFeatureRepository,
                planRepository, accessControlService, currentBandResolver, scopeMemberCountService,
                Clock.fixed(EFFECTIVE_AT, ZoneId.of("Asia/Tokyo")));
    }

    private BillingContractEntity contract() {
        BillingContractEntity c = BillingContractEntity.builder()
                .scopeKind(EntitlementScopeKind.USER)
                .scopeId(1L)
                .contractKind(ContractKind.PLAN)
                .planKey("BASIC")
                .status(ContractStatus.ACTIVE)
                .contractedAt(LocalDateTime.of(2026, 1, 1, 0, 0))
                .version(3L)
                .build();
        c.setId(CONTRACT_ID);
        return c;
    }

    private BillingContractChangeEntity change(Instant pendingUpdateExpiresAt) {
        BillingContractChangeEntity change = BillingContractChangeEntity.builder()
                .operationId(UUID.randomUUID())
                .contractId(CONTRACT_ID)
                .billingCustomerId(UUID.randomUUID())
                .kind(BillingContractChangeKind.UPGRADE)
                .status(BillingContractChangeStatus.REQUIRES_ACTION)
                .fromPlanKey("BASIC")
                .toPlanKey("FULL")
                .fromPriceBandVersionId(UUID.randomUUID())
                .toPriceBandVersionId(UUID.randomUUID())
                .fromAmountIncludingTax(1000L)
                .toAmountIncludingTax(2000L)
                .effectiveAt(EFFECTIVE_AT)
                .pendingUpdateExpiresAt(pendingUpdateExpiresAt)
                .build();
        change.setId(CHANGE_ID);
        return change;
    }

    private ActiveContract.PendingChange project(Instant pendingUpdateExpiresAt) {
        when(billingContractRepository.findByScopeKindAndScopeIdAndStatusInAndDeletedAtIsNull(
                eq(EntitlementScopeKind.USER), eq(1L), anyList())).thenReturn(List.of(contract()));
        when(billingContractChangeRepository.findByContractIdInAndStatusInAndDeletedAtIsNull(
                anyList(), anyList())).thenReturn(List.of(change(pendingUpdateExpiresAt)));
        when(entitlementQueryService.entitledFeatureKeys(any(), any())).thenReturn(Set.of());
        when(entitlementRepository.findActiveByScope(any(), any(), any())).thenReturn(List.of());

        EntitlementSummaryResponse res = service.getSummary(EntitlementScopeKind.USER, 1L);
        ActiveContract plan = res.getActivePlan();
        assertThat(plan).isNotNull();
        ActiveContract.PendingChange pending = plan.getPendingChange();
        assertThat(pending).isNotNull();
        return pending;
    }

    @Test
    @DisplayName("P1: pendingChange に changeId が載る（再読込・別端末から payment-action を組める）")
    void projectsChangeId() {
        assertThat(project(PENDING_UPDATE_EXPIRES_AT).getChangeId()).isEqualTo(CHANGE_ID.toString());
    }

    @Test
    @DisplayName("P2-1: 支払期限は pending_update_expires_at であり effectiveAt ではない")
    void projectsPendingUpdateExpiresAt() {
        ActiveContract.PendingChange pending = project(PENDING_UPDATE_EXPIRES_AT);
        assertThat(pending.getPendingUpdateExpiresAt()).isEqualTo(PENDING_UPDATE_EXPIRES_AT);
        assertThat(pending.getPendingUpdateExpiresAt()).isNotEqualTo(pending.getEffectiveAt());
    }

    @Test
    @DisplayName("P2-1: 期限が取れない変更では null のまま（effectiveAt で埋め合わせて嘘をつかない）")
    void doesNotSubstituteEffectiveAtWhenExpiryUnknown() {
        ActiveContract.PendingChange pending = project(null);
        assertThat(pending.getPendingUpdateExpiresAt()).isNull();
    }

    // ============================================================
    // PR6b-1 残務③: changeablePlanKeys（BillingCurrentBandResolver と同じ読み方の候補投影）
    // ============================================================

    private PlanEntity plan(String planKey) {
        return PlanEntity.builder().planKey(planKey).displayNameKey("plan." + planKey)
                .descriptionKey("plan." + planKey + ".desc").build();
    }

    private BillingPriceBandVersionEntity band(long amountIncludingTax, String stripePriceRef) {
        BillingPriceBandVersionEntity band = BillingPriceBandVersionEntity.builder()
                .productKind(BillingProductKind.PLAN)
                .amountIncludingTax(amountIncludingTax)
                .stripePriceRef(stripePriceRef)
                .build();
        return band;
    }

    private ActiveContract projectActivePlan() {
        when(billingContractRepository.findByScopeKindAndScopeIdAndStatusInAndDeletedAtIsNull(
                eq(EntitlementScopeKind.USER), eq(1L), anyList())).thenReturn(List.of(contract()));
        when(billingContractChangeRepository.findByContractIdInAndStatusInAndDeletedAtIsNull(
                anyList(), anyList())).thenReturn(List.of());
        when(entitlementQueryService.entitledFeatureKeys(any(), any())).thenReturn(Set.of());
        when(entitlementRepository.findActiveByScope(any(), any(), any())).thenReturn(List.of());

        EntitlementSummaryResponse res = service.getSummary(EntitlementScopeKind.USER, 1L);
        ActiveContract plan = res.getActivePlan();
        assertThat(plan).isNotNull();
        return plan;
    }

    @Test
    @DisplayName("BASIC(1000)からFULL(2000, Stripe Price ref あり)へは changeablePlanKeys に載る")
    void 上位でStripePriceRefがある候補は載る() {
        given(scopeMemberCountService.countActiveMembers(EntitlementScopeKind.USER, 1L)).willReturn(1);
        given(currentBandResolver.resolveContractBand(any(), eq(1), any()))
                .willReturn(Optional.of(band(1_000L, "price_basic")));
        given(planRepository.findByEnabledTrueOrderBySortOrderAsc())
                .willReturn(List.of(plan("BASIC"), plan("FULL")));
        given(currentBandResolver.resolveCurrentBand(
                eq(BillingProductKind.PLAN), eq("FULL"), eq(EntitlementScopeKind.USER), eq(1), any()))
                .willReturn(Optional.of(band(2_000L, "price_full")));

        assertThat(projectActivePlan().getChangeablePlanKeys()).containsExactly("FULL");
    }

    @Test
    @DisplayName("同額・下位・Stripe Price ref 無しの候補は載らない（見積りが必ず409になるものを出さない）")
    void 見積りが必ず409になる候補は載らない() {
        given(scopeMemberCountService.countActiveMembers(EntitlementScopeKind.USER, 1L)).willReturn(1);
        given(currentBandResolver.resolveContractBand(any(), eq(1), any()))
                .willReturn(Optional.of(band(1_000L, "price_basic")));
        given(planRepository.findByEnabledTrueOrderBySortOrderAsc())
                .willReturn(List.of(plan("BASIC"), plan("SAME"), plan("NO_REF")));
        given(currentBandResolver.resolveCurrentBand(
                eq(BillingProductKind.PLAN), eq("SAME"), eq(EntitlementScopeKind.USER), eq(1), any()))
                .willReturn(Optional.of(band(1_000L, "price_same")));
        given(currentBandResolver.resolveCurrentBand(
                eq(BillingProductKind.PLAN), eq("NO_REF"), eq(EntitlementScopeKind.USER), eq(1), any()))
                .willReturn(Optional.of(band(5_000L, null)));

        assertThat(projectActivePlan().getChangeablePlanKeys()).isEmpty();
    }

    @Test
    @DisplayName("現行 band が解決できない契約は changeablePlanKeys が空配列（カタログ検索自体を行わない）")
    void 現行bandが解決できなければ空配列() {
        given(scopeMemberCountService.countActiveMembers(EntitlementScopeKind.USER, 1L)).willReturn(1);
        given(currentBandResolver.resolveContractBand(any(), eq(1), any())).willReturn(Optional.empty());

        assertThat(projectActivePlan().getChangeablePlanKeys()).isEmpty();
    }
}
