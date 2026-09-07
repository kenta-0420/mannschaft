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
 * {@link BillingContractService} 単体テスト（試練先行）。
 *
 * <p>対象 AC: AC-16（取消時 evict 呼び出し）／AC-19（プラン変更で旧 revoke＋新発行）／
 * AC-20（解約で由来 entitlements 全 revoke）／AC-21（uk_ent_grant 二重発行→DUPLICATE_ENTITLEMENT 409）／
 * AC-28/30（uk_acp_slot 二重契約→CONTRACT_ALREADY_ACTIVE 409）。IDOR 二重防御（スコープ不一致→404 秘匿）も検証。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BillingContractService 単体テスト（契約作成/解約/変更）")
class BillingContractServiceTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-07-10T00:00:00Z"), ZoneOffset.UTC);

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
    @Mock private BillingPriceResolver billingPriceResolver;
    @Mock private BillingOperationAuthorizer billingOperationAuthorizer;

    private BillingContractService service;

    @BeforeEach
    void setUp() {
        // F20.3 リファクタ: entitlements 発行は EntitlementIssuanceService へ抽出済み。
        // 実体を注入する（モック entitlementRepository へ委譲するため、既存の saveAll/flush 検証はそのまま通る）。
        EntitlementIssuanceService issuanceService =
                new EntitlementIssuanceService(entitlementRepository, FIXED_CLOCK);
        service = new BillingContractService(
                billingContractRepository, activeContractPointerRepository, entitlementRepository,
                planRepository, planFeatureRepository, featureCatalogRepository, planPriceBandRepository,
                scopeMemberCountService, cacheEvictor, FIXED_CLOCK, billingPaymentGateway,
                billingPriceResolver, issuanceService, billingOperationAuthorizer);
    }

    private PlanEntity plan(String key, boolean enabled) {
        return PlanEntity.builder().planKey(key)
                .displayNameKey("k.name").descriptionKey("k.desc")
                .sortOrder(1).enabled(enabled).build();
    }

    private PlanFeatureEntity pf(String planKey, String featureKey) {
        return PlanFeatureEntity.builder().planKey(planKey).featureKey(featureKey).build();
    }

    private FeatureCatalogEntity feature(String key, boolean enabled, boolean addonAvailable) {
        return FeatureCatalogEntity.builder().featureKey(key).category(FeatureCategory.INTERNAL)
                .addonAvailable(addonAvailable).freeForNonprofit(false)
                .displayNameKey("k.name").descriptionKey("k.desc").sortOrder(0).enabled(enabled).build();
    }

    private BillingContractEntity activeContract(
            UUID id, EntitlementScopeKind scopeKind, Long scopeId, ContractKind kind, String planKey) {
        BillingContractEntity c = BillingContractEntity.builder()
                .scopeKind(scopeKind).scopeId(scopeId).contractKind(kind).planKey(planKey)
                .status(ContractStatus.ACTIVE).contractedAt(java.time.LocalDateTime.now()).build();
        c.setId(id);
        return c;
    }

    @SuppressWarnings("unchecked")
    private void stubSaveAssignsId() {
        given(billingContractRepository.save(any(BillingContractEntity.class))).willAnswer(inv -> {
            BillingContractEntity e = inv.getArgument(0);
            if (e.getId() == null) {
                e.setId(UUID.randomUUID());
            }
            return e;
        });
        given(entitlementRepository.saveAll(anyList())).willAnswer(inv -> inv.getArgument(0));
    }

    // ============================================================
    // 作成
    // ============================================================

    @Test
    @DisplayName("PLAN 契約作成: entitlements 発行＋pointer 確保＋evict（AC-01/16 系）")
    void createPlanContract_success() {
        stubSaveAssignsId();
        given(planRepository.findById("FULL")).willReturn(Optional.of(plan("FULL", true)));
        given(planFeatureRepository.findByPlanKey("FULL"))
                .willReturn(List.of(pf("FULL", FeatureKeys.ADS_HIDE), pf("FULL", FeatureKeys.TEMPLATE_PREMIUM_MODULES)));
        given(scopeMemberCountService.countActiveMembers(EntitlementScopeKind.TEAM, 10L)).willReturn(34);
        given(planPriceBandRepository.findByPlanKeyAndScopeKindOrderByBandNoAsc("FULL", PlanPriceBandScopeKind.TEAM))
                .willReturn(List.of());

        BillingContractService.ContractResult result = service.createContract(
                EntitlementScopeKind.TEAM, 10L, 99L, ContractKind.PLAN, "FULL", null, 7L);

        assertThat(result.status()).isEqualTo(ContractStatus.ACTIVE);
        assertThat(result.grantedFeatureKeys())
                .containsExactlyInAnyOrder(FeatureKeys.ADS_HIDE, FeatureKeys.TEMPLATE_PREMIUM_MODULES);
        assertThat(result.memberCountSnapshot()).isEqualTo(34);

        // pointer は PLAN スロット（addon_feature_key=""）で確保。
        ArgumentCaptor<ActiveContractPointerEntity> ptr = ArgumentCaptor.forClass(ActiveContractPointerEntity.class);
        verify(activeContractPointerRepository).saveAndFlush(ptr.capture());
        assertThat(ptr.getValue().getAddonFeatureKey()).isEmpty();
        assertThat(ptr.getValue().getContractId()).isNotNull();

        // entitlements は 2 件・source_kind=PLAN・valid_until=NULL。
        ArgumentCaptor<List<EntitlementEntity>> ents = ArgumentCaptor.forClass(List.class);
        verify(entitlementRepository).saveAll(ents.capture());
        assertThat(ents.getValue()).hasSize(2)
                .allSatisfy(e -> {
                    assertThat(e.getSourceKind()).isEqualTo(EntitlementSourceKind.PLAN);
                    assertThat(e.getValidUntil()).isNull();
                });

        verify(cacheEvictor).evictScopeFeatures(eq(EntitlementScopeKind.TEAM), eq(10L),
                argThatContains(FeatureKeys.ADS_HIDE, FeatureKeys.TEMPLATE_PREMIUM_MODULES));
    }

    @Test
    @DisplayName("AC-2: 新規 TEAM/ORG 契約作成時、payer_user_id は created_by（operatorUserId）と同値で初期化される")
    void createContract_initializesPayerUserIdFromCreatedBy() {
        stubSaveAssignsId();
        given(planRepository.findById("FULL")).willReturn(Optional.of(plan("FULL", true)));
        given(planFeatureRepository.findByPlanKey("FULL")).willReturn(List.of(pf("FULL", FeatureKeys.ADS_HIDE)));
        given(scopeMemberCountService.countActiveMembers(EntitlementScopeKind.TEAM, 10L)).willReturn(34);
        given(planPriceBandRepository.findByPlanKeyAndScopeKindOrderByBandNoAsc("FULL", PlanPriceBandScopeKind.TEAM))
                .willReturn(List.of());

        service.createContract(EntitlementScopeKind.TEAM, 10L, 99L, ContractKind.PLAN, "FULL", null, 7L);

        ArgumentCaptor<BillingContractEntity> captor = ArgumentCaptor.forClass(BillingContractEntity.class);
        verify(billingContractRepository).save(captor.capture());
        assertThat(captor.getValue().getCreatedBy()).isEqualTo(7L);
        assertThat(captor.getValue().getPayerUserId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("AC-28/30: pointer の uk_acp_slot 衝突は CONTRACT_ALREADY_ACTIVE（entitlements 未発行）")
    void createContract_duplicatePointer_throwsAlreadyActive() {
        given(billingContractRepository.save(any(BillingContractEntity.class))).willAnswer(inv -> {
            BillingContractEntity e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            return e;
        });
        given(planRepository.findById("FULL")).willReturn(Optional.of(plan("FULL", true)));
        given(planFeatureRepository.findByPlanKey("FULL")).willReturn(List.of(pf("FULL", FeatureKeys.ADS_HIDE)));
        given(scopeMemberCountService.countActiveMembers(EntitlementScopeKind.TEAM, 10L)).willReturn(5);
        given(planPriceBandRepository.findByPlanKeyAndScopeKindOrderByBandNoAsc("FULL", PlanPriceBandScopeKind.TEAM))
                .willReturn(List.of());
        given(activeContractPointerRepository.saveAndFlush(any()))
                .willThrow(new DataIntegrityViolationException("uk_acp_slot"));

        assertThatThrownBy(() -> service.createContract(
                EntitlementScopeKind.TEAM, 10L, 99L, ContractKind.PLAN, "FULL", null, 7L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(EntitlementErrorCode.CONTRACT_ALREADY_ACTIVE);

        verify(entitlementRepository, never()).saveAll(anyList());
        verify(cacheEvictor, never()).evictScopeFeatures(any(), any(), anyList());
    }

    @Test
    @DisplayName("AC-21: entitlements の uk_ent_grant 衝突は DUPLICATE_ENTITLEMENT")
    void createContract_duplicateEntitlement_throwsDuplicate() {
        given(billingContractRepository.save(any(BillingContractEntity.class))).willAnswer(inv -> {
            BillingContractEntity e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            return e;
        });
        given(planRepository.findById("FULL")).willReturn(Optional.of(plan("FULL", true)));
        given(planFeatureRepository.findByPlanKey("FULL")).willReturn(List.of(pf("FULL", FeatureKeys.ADS_HIDE)));
        given(scopeMemberCountService.countActiveMembers(EntitlementScopeKind.TEAM, 10L)).willReturn(5);
        given(planPriceBandRepository.findByPlanKeyAndScopeKindOrderByBandNoAsc("FULL", PlanPriceBandScopeKind.TEAM))
                .willReturn(List.of());
        given(entitlementRepository.saveAll(anyList())).willAnswer(inv -> inv.getArgument(0));
        org.mockito.BDDMockito.willThrow(new DataIntegrityViolationException("uk_ent_grant"))
                .given(entitlementRepository).flush();

        assertThatThrownBy(() -> service.createContract(
                EntitlementScopeKind.TEAM, 10L, 99L, ContractKind.PLAN, "FULL", null, 7L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(EntitlementErrorCode.DUPLICATE_ENTITLEMENT);
    }

    @Test
    @DisplayName("ADDON 契約作成: addon_available=false は ADDON_NOT_AVAILABLE")
    void createAddon_notAvailable_throws() {
        given(featureCatalogRepository.findById(FeatureKeys.ADS_HIDE))
                .willReturn(Optional.of(feature(FeatureKeys.ADS_HIDE, true, false)));

        assertThatThrownBy(() -> service.createContract(
                EntitlementScopeKind.USER, 3L, null, ContractKind.ADDON, null, FeatureKeys.ADS_HIDE, 3L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(EntitlementErrorCode.ADDON_NOT_AVAILABLE);
    }

    @Test
    @DisplayName("USER の ADDON 契約作成は member_count/band を解決しない（USER はバンド無し）")
    void createAddon_userSuccess_noMemberCount() {
        given(featureCatalogRepository.findById(FeatureKeys.ADS_HIDE))
                .willReturn(Optional.of(feature(FeatureKeys.ADS_HIDE, true, true)));
        given(billingContractRepository.save(any(BillingContractEntity.class))).willAnswer(inv -> {
            BillingContractEntity e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            return e;
        });
        given(entitlementRepository.saveAll(anyList())).willAnswer(inv -> inv.getArgument(0));

        BillingContractService.ContractResult result = service.createContract(
                EntitlementScopeKind.USER, 3L, null, ContractKind.ADDON, null, FeatureKeys.ADS_HIDE, 3L);

        assertThat(result.memberCountSnapshot()).isNull();
        assertThat(result.grantedFeatureKeys()).containsExactly(FeatureKeys.ADS_HIDE);
        // pointer は ADDON スロット（addon_feature_key=featureKey）。
        ArgumentCaptor<ActiveContractPointerEntity> ptr = ArgumentCaptor.forClass(ActiveContractPointerEntity.class);
        verify(activeContractPointerRepository).saveAndFlush(ptr.capture());
        assertThat(ptr.getValue().getAddonFeatureKey()).isEqualTo(FeatureKeys.ADS_HIDE);
        // USER なので member count は算出しない。
        verify(scopeMemberCountService, never()).countActiveMembers(any(), any());
    }

    // ============================================================
    // 解約
    // ============================================================

    @Test
    @DisplayName("AC-20/16: 解約で由来 entitlements 全 revoke＋pointer 物理 DELETE＋evict")
    void cancelContract_revokesAndEvicts() {
        UUID cid = UUID.randomUUID();
        BillingContractEntity contract = activeContract(cid, EntitlementScopeKind.TEAM, 10L, ContractKind.PLAN, "FULL");
        given(billingContractRepository.findByIdAndDeletedAtIsNull(cid)).willReturn(Optional.of(contract));
        EntitlementEntity e1 = EntitlementEntity.builder().scopeKind(EntitlementScopeKind.TEAM).scopeId(10L)
                .featureKey(FeatureKeys.ADS_HIDE).sourceKind(EntitlementSourceKind.PLAN).sourceRefId(cid)
                .validFrom(java.time.LocalDateTime.now()).build();
        EntitlementEntity e2 = EntitlementEntity.builder().scopeKind(EntitlementScopeKind.TEAM).scopeId(10L)
                .featureKey(FeatureKeys.TEMPLATE_PREMIUM_MODULES).sourceKind(EntitlementSourceKind.PLAN).sourceRefId(cid)
                .validFrom(java.time.LocalDateTime.now()).build();
        given(entitlementRepository.findBySourceKindAndSourceRefIdAndRevokedAtIsNull(EntitlementSourceKind.PLAN, cid))
                .willReturn(List.of(e1, e2));

        BillingContractService.ContractResult result =
                service.cancelContract(EntitlementScopeKind.TEAM, 10L, cid, 7L);

        assertThat(contract.getStatus()).isEqualTo(ContractStatus.CANCELLED);
        assertThat(contract.getCancelledAt()).isNotNull();
        assertThat(e1.getRevokedAt()).isNotNull();
        assertThat(e1.getRevokedBy()).isEqualTo(7L);
        assertThat(e2.getRevokedAt()).isNotNull();
        assertThat(result.revokedFeatureKeys())
                .containsExactlyInAnyOrder(FeatureKeys.ADS_HIDE, FeatureKeys.TEMPLATE_PREMIUM_MODULES);

        verify(activeContractPointerRepository).hardDeleteBySlot(
                EntitlementScopeKind.TEAM, 10L, ContractKind.PLAN, "");
        verify(cacheEvictor).evictScopeFeatures(eq(EntitlementScopeKind.TEAM), eq(10L),
                argThatContains(FeatureKeys.ADS_HIDE, FeatureKeys.TEMPLATE_PREMIUM_MODULES));
    }

    @Test
    @DisplayName("evict は AFTER_COMMIT に遅延登録される（コミット前は evict しない・stale 再ポピュレート防止）")
    void cancelContract_evictDeferredUntilAfterCommit() {
        UUID cid = UUID.randomUUID();
        BillingContractEntity contract = activeContract(cid, EntitlementScopeKind.TEAM, 10L, ContractKind.PLAN, "FULL");
        given(billingContractRepository.findByIdAndDeletedAtIsNull(cid)).willReturn(Optional.of(contract));
        EntitlementEntity e1 = EntitlementEntity.builder().scopeKind(EntitlementScopeKind.TEAM).scopeId(10L)
                .featureKey(FeatureKeys.ADS_HIDE).sourceKind(EntitlementSourceKind.PLAN).sourceRefId(cid)
                .validFrom(java.time.LocalDateTime.now()).build();
        given(entitlementRepository.findBySourceKindAndSourceRefIdAndRevokedAtIsNull(EntitlementSourceKind.PLAN, cid))
                .willReturn(List.of(e1));

        // トランザクション同期を能動化（@Transactional 実行中を模擬）。
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            service.cancelContract(EntitlementScopeKind.TEAM, 10L, cid, 7L);

            // コミット前: evict はまだ呼ばれていない（遅延登録のみ）。
            verify(cacheEvictor, never()).evictScopeFeatures(any(), any(), anyList());
            var syncs = org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations();
            assertThat(syncs).hasSize(1);

            // コミット確定を模擬 → afterCommit で evict が走る。
            syncs.forEach(org.springframework.transaction.support.TransactionSynchronization::afterCommit);
            verify(cacheEvictor).evictScopeFeatures(eq(EntitlementScopeKind.TEAM), eq(10L),
                    argThatContains(FeatureKeys.ADS_HIDE));
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("IDOR 二重防御: 他スコープの契約 ID は CONTRACT_NOT_FOUND（404 秘匿）")
    void cancelContract_wrongScope_throwsNotFound() {
        UUID cid = UUID.randomUUID();
        BillingContractEntity contract = activeContract(cid, EntitlementScopeKind.TEAM, 99L, ContractKind.PLAN, "FULL");
        given(billingContractRepository.findByIdAndDeletedAtIsNull(cid)).willReturn(Optional.of(contract));

        assertThatThrownBy(() -> service.cancelContract(EntitlementScopeKind.TEAM, 10L, cid, 7L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(EntitlementErrorCode.CONTRACT_NOT_FOUND);
    }

    @Test
    @DisplayName("既に CANCELLED の契約の解約は CONTRACT_NOT_CANCELLABLE")
    void cancelContract_alreadyCancelled_throws() {
        UUID cid = UUID.randomUUID();
        BillingContractEntity contract = activeContract(cid, EntitlementScopeKind.TEAM, 10L, ContractKind.PLAN, "FULL");
        contract.setStatus(ContractStatus.CANCELLED);
        given(billingContractRepository.findByIdAndDeletedAtIsNull(cid)).willReturn(Optional.of(contract));

        assertThatThrownBy(() -> service.cancelContract(EntitlementScopeKind.TEAM, 10L, cid, 7L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(EntitlementErrorCode.CONTRACT_NOT_CANCELLABLE);
    }

    // ============================================================
    // プラン変更
    // ============================================================

    @Test
    @DisplayName("AC-19: プラン変更で旧契約 revoke＋新契約発行＋pointer 付け替え＋旧∪新 evict")
    void changePlan_revokesOldIssuesNew() {
        UUID oldId = UUID.randomUUID();
        BillingContractEntity oldContract =
                activeContract(oldId, EntitlementScopeKind.TEAM, 10L, ContractKind.PLAN, "BASIC");
        given(billingContractRepository.findByIdAndDeletedAtIsNull(oldId)).willReturn(Optional.of(oldContract));
        given(planRepository.findById("FULL")).willReturn(Optional.of(plan("FULL", true)));
        given(planFeatureRepository.findByPlanKey("FULL"))
                .willReturn(List.of(pf("FULL", FeatureKeys.ADS_HIDE), pf("FULL", FeatureKeys.TEMPLATE_PREMIUM_MODULES)));
        given(scopeMemberCountService.countActiveMembers(EntitlementScopeKind.TEAM, 10L)).willReturn(20);
        given(planPriceBandRepository.findByPlanKeyAndScopeKindOrderByBandNoAsc("FULL", PlanPriceBandScopeKind.TEAM))
                .willReturn(List.of());
        // 旧契約由来（BASIC の 1 機能）を revoke 対象に。
        EntitlementEntity old1 = EntitlementEntity.builder().scopeKind(EntitlementScopeKind.TEAM).scopeId(10L)
                .featureKey(FeatureKeys.LEGACY_PAID_PLAN_BUNDLE).sourceKind(EntitlementSourceKind.PLAN)
                .sourceRefId(oldId).validFrom(java.time.LocalDateTime.now()).build();
        given(entitlementRepository.findBySourceKindAndSourceRefIdAndRevokedAtIsNull(EntitlementSourceKind.PLAN, oldId))
                .willReturn(List.of(old1));
        given(billingContractRepository.save(any(BillingContractEntity.class))).willAnswer(inv -> {
            BillingContractEntity e = inv.getArgument(0);
            if (e.getId() == null) {
                e.setId(UUID.randomUUID());
            }
            return e;
        });
        given(entitlementRepository.saveAll(anyList())).willAnswer(inv -> inv.getArgument(0));
        ActiveContractPointerEntity pointer = ActiveContractPointerEntity.builder()
                .scopeKind(EntitlementScopeKind.TEAM).scopeId(10L).contractKind(ContractKind.PLAN)
                .addonFeatureKey("").contractId(oldId).build();
        given(activeContractPointerRepository.findByScopeKindAndScopeIdAndContractKindAndAddonFeatureKey(
                EntitlementScopeKind.TEAM, 10L, ContractKind.PLAN, "")).willReturn(Optional.of(pointer));

        BillingContractService.ContractResult result =
                service.changePlan(EntitlementScopeKind.TEAM, 10L, oldId, "FULL", 7L);

        assertThat(oldContract.getStatus()).isEqualTo(ContractStatus.CANCELLED);
        assertThat(old1.getRevokedAt()).isNotNull();
        assertThat(result.planKey()).isEqualTo("FULL");
        assertThat(result.grantedFeatureKeys())
                .containsExactlyInAnyOrder(FeatureKeys.ADS_HIDE, FeatureKeys.TEMPLATE_PREMIUM_MODULES);
        // pointer は新契約 ID へ付け替え（行を増やさない）。
        assertThat(pointer.getContractId()).isNotEqualTo(oldId);
        // 旧∪新 の feature_key を evict。
        verify(cacheEvictor).evictScopeFeatures(eq(EntitlementScopeKind.TEAM), eq(10L),
                argThatContains(FeatureKeys.LEGACY_PAID_PLAN_BUNDLE, FeatureKeys.ADS_HIDE,
                        FeatureKeys.TEMPLATE_PREMIUM_MODULES));
    }

    @Test
    @DisplayName("同一プランへの変更は CONTRACT_ALREADY_ACTIVE")
    void changePlan_samePlan_throws() {
        UUID oldId = UUID.randomUUID();
        BillingContractEntity oldContract =
                activeContract(oldId, EntitlementScopeKind.TEAM, 10L, ContractKind.PLAN, "FULL");
        given(billingContractRepository.findByIdAndDeletedAtIsNull(oldId)).willReturn(Optional.of(oldContract));

        assertThatThrownBy(() -> service.changePlan(EntitlementScopeKind.TEAM, 10L, oldId, "FULL", 7L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(EntitlementErrorCode.CONTRACT_ALREADY_ACTIVE);
    }

    /** feature_key 集合を「指定要素を全て含む」で照合する ArgumentMatcher（順序不問）。 */
    private static java.util.Collection<String> argThatContains(String... keys) {
        return org.mockito.ArgumentMatchers.argThat(col -> {
            if (col == null) {
                return false;
            }
            for (String k : keys) {
                if (!col.contains(k)) {
                    return false;
                }
            }
            return true;
        });
    }
}
