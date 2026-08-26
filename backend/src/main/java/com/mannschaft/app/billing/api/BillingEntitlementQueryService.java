package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.BillingContractEntity;
import com.mannschaft.app.billing.BillingContractRepository;
import com.mannschaft.app.billing.ContractKind;
import com.mannschaft.app.billing.ContractStatus;
import com.mannschaft.app.billing.EntitlementEntity;
import com.mannschaft.app.billing.EntitlementErrorCode;
import com.mannschaft.app.billing.EntitlementQueryService;
import com.mannschaft.app.billing.EntitlementRepository;
import com.mannschaft.app.billing.EntitlementScopeKind;
import com.mannschaft.app.billing.FeatureCatalogEntity;
import com.mannschaft.app.billing.FeatureCatalogRepository;
import com.mannschaft.app.billing.FeatureKeys;
import com.mannschaft.app.billing.PlanEntity;
import com.mannschaft.app.billing.PlanFeatureEntity;
import com.mannschaft.app.billing.PlanFeatureRepository;
import com.mannschaft.app.billing.PlanRepository;
import com.mannschaft.app.billing.api.dto.ActiveContract;
import com.mannschaft.app.billing.api.dto.EntitledFeature;
import com.mannschaft.app.billing.api.dto.EntitlementCheckResponse;
import com.mannschaft.app.billing.api.dto.EntitlementSummaryResponse;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * F20.1: 権利サマリ・単一判定 API の読み取りサービス（設計書 02 §2.2 / §2.3 / 03 §2.2）。
 *
 * <p><b>サマリの entitledFeatures 合成</b>: {@link EntitlementQueryService#entitledFeatureKeys}
 * を正準集合とし、各 feature_key の由来（{@code PLAN/ADDON/BETA_GRANT} は実 entitlement 行から、
 * それ以外は FREE / NONPROFIT_FREE の virtual）を解決する。これで「利用できる機能」一覧が
 * {@code isEntitled=true} の集合と構造的に一致する（M-2・AC-23）。</p>
 *
 * <p><b>check の探索防止</b>: scopeKind/scopeId をクエリで受けるため、呼び出し元が当該スコープの
 * メンバー（USER は本人）であることを {@link #assertScopeReadable} で必須検証する（03 §2.2・AC-10）。
 * 不適合は {@code SCOPE_FORBIDDEN}（403）。</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BillingEntitlementQueryService {

    private final EntitlementQueryService entitlementQueryService;
    private final EntitlementRepository entitlementRepository;
    private final BillingContractRepository billingContractRepository;
    private final FeatureCatalogRepository featureCatalogRepository;
    private final PlanFeatureRepository planFeatureRepository;
    private final PlanRepository planRepository;
    private final AccessControlService accessControlService;
    private final Clock clock;

    // ============================================================
    // 権利サマリ（§2.2）
    // ============================================================

    /** スコープの権利サマリ（現在の契約と有効機能）を組み立てる。 */
    public EntitlementSummaryResponse getSummary(EntitlementScopeKind scopeKind, Long scopeId) {
        List<BillingContractEntity> active = billingContractRepository
                .findByScopeKindAndScopeIdAndStatusAndDeletedAtIsNull(scopeKind, scopeId, ContractStatus.ACTIVE);

        ActiveContract activePlan = null;
        List<ActiveContract> activeAddons = new ArrayList<>();
        for (BillingContractEntity c : active) {
            ActiveContract dto = ActiveContract.builder()
                    .contractId(c.getId().toString())
                    .planKey(c.getPlanKey())
                    .featureKey(c.getFeatureKey())
                    .contractedAt(c.getContractedAt())
                    .priceJpySnapshot(c.getPriceJpySnapshot())
                    .build();
            if (c.getContractKind() == ContractKind.PLAN) {
                activePlan = dto;
            } else {
                activeAddons.add(dto);
            }
        }

        return EntitlementSummaryResponse.builder()
                .scopeKind(scopeKind.name())
                .scopeId(scopeId)
                .activePlan(activePlan)
                .activeAddons(activeAddons)
                .entitledFeatures(buildEntitledFeatures(scopeKind, scopeId))
                .build();
    }

    /**
     * 利用できる機能一覧を合成する（AC-23）。正準集合は {@code isEntitled=true} の feature_key。
     * 実 entitlement 行のある key は {@code sourceKind/validUntil} を行から、無い key は FREE / NONPROFIT_FREE。
     */
    private List<EntitledFeature> buildEntitledFeatures(EntitlementScopeKind scopeKind, Long scopeId) {
        Set<String> entitledKeys = entitlementQueryService.entitledFeatureKeys(scopeKind, scopeId);

        // 実 entitlement 行を feature_key で索引化（同一 key が複数由来なら最初の有効行を採る）。
        LocalDateTime now = LocalDateTime.now(clock);
        Map<String, EntitlementEntity> byKey = new LinkedHashMap<>();
        for (EntitlementEntity e : entitlementRepository.findActiveByScope(scopeKind, scopeId, now)) {
            byKey.putIfAbsent(e.getFeatureKey(), e);
        }

        List<EntitledFeature> result = new ArrayList<>();
        for (String key : entitledKeys) {
            EntitlementEntity row = byKey.get(key);
            if (row != null) {
                result.add(EntitledFeature.builder()
                        .featureKey(key)
                        .sourceKind(row.getSourceKind().name())
                        .validUntil(row.getValidUntil())
                        .build());
            } else if (planFeatureRepository.existsByPlanKeyAndFeatureKey(FeatureKeys.PLAN_FREE, key)) {
                result.add(virtual(key, "FREE"));
            } else {
                // FREE 掲載でも実行でもない＝非営利無料枠（free_for_nonprofit）由来の virtual。
                result.add(virtual(key, "NONPROFIT_FREE"));
            }
        }
        return result;
    }

    private static EntitledFeature virtual(String key, String sourceKind) {
        return EntitledFeature.builder().featureKey(key).sourceKind(sourceKind).validUntil(null).build();
    }

    // ============================================================
    // 単一判定（§2.3）
    // ============================================================

    /**
     * 単一機能の判定（FE ゲート補助・BE が正）。呼び出し元のスコープ可読性を検証してから判定する。
     *
     * @param callerUserId 呼び出しユーザー
     */
    public EntitlementCheckResponse check(
            Long callerUserId, EntitlementScopeKind scopeKind, Long scopeId, String featureKey) {
        assertScopeReadable(callerUserId, scopeKind, scopeId);

        boolean entitled = entitlementQueryService.isEntitled(scopeKind, scopeId, featureKey);
        FeatureCatalogEntity feature = featureCatalogRepository.findById(featureKey).orElse(null);
        boolean enabled = feature != null && Boolean.TRUE.equals(feature.getEnabled());

        List<String> plansContaining = enabled ? plansContaining(featureKey) : List.of();
        boolean addonAvailable = enabled && Boolean.TRUE.equals(feature.getAddonAvailable());
        boolean purchasable = addonAvailable || !plansContaining.isEmpty();
        Integer addonPriceJpy = addonAvailable ? feature.getAddonPriceJpy() : null;

        return EntitlementCheckResponse.builder()
                .entitled(entitled)
                .featureKey(featureKey)
                .purchasable(purchasable)
                .addonPriceJpy(addonPriceJpy)
                .plansContaining(plansContaining)
                .build();
    }

    /** 指定 feature を掲載する購入可能プラン（enabled・非 FREE）のキー一覧。 */
    private List<String> plansContaining(String featureKey) {
        List<String> keys = new ArrayList<>();
        for (PlanFeatureEntity pf : planFeatureRepository.findByFeatureKey(featureKey)) {
            if (FeatureKeys.PLAN_FREE.equals(pf.getPlanKey())) {
                continue;
            }
            PlanEntity plan = planRepository.findById(pf.getPlanKey()).orElse(null);
            if (plan != null && Boolean.TRUE.equals(plan.getEnabled())) {
                keys.add(pf.getPlanKey());
            }
        }
        return keys;
    }

    /**
     * 呼び出し元が当該スコープを読める（メンバー以上・USER は本人）ことを検証する（03 §2.2・AC-10）。
     * 不適合は {@code SCOPE_FORBIDDEN}（403）で無認可の横断列挙を封じる。
     */
    public void assertScopeReadable(Long callerUserId, EntitlementScopeKind scopeKind, Long scopeId) {
        if (callerUserId == null || scopeId == null) {
            throw new BusinessException(EntitlementErrorCode.SCOPE_FORBIDDEN);
        }
        if (scopeKind == EntitlementScopeKind.USER) {
            if (!scopeId.equals(callerUserId)) {
                throw new BusinessException(EntitlementErrorCode.SCOPE_FORBIDDEN);
            }
            return;
        }
        String scopeType = BillingApiSupport.toAccessScopeType(scopeKind);
        boolean member = accessControlService.isSystemAdmin(callerUserId)
                || accessControlService.isMember(callerUserId, scopeId, scopeType);
        if (!member) {
            throw new BusinessException(EntitlementErrorCode.SCOPE_FORBIDDEN);
        }
    }
}
