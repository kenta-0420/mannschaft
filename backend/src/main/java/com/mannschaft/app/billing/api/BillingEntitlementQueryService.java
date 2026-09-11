package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.BillingCancelState;
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
import java.time.OffsetDateTime;
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

    /** 権利サマリの投影に載せる契約状態（AC-65。PAST_DUE は期末まで利用でき解約もできる・D4）。 */
    private static final List<ContractStatus> DISPLAYED_CONTRACT_STATUSES =
            List.of(ContractStatus.ACTIVE, ContractStatus.PAST_DUE);

    // ============================================================
    // 権利サマリ（§2.2）
    // ============================================================

    /** スコープの権利サマリ（現在の契約と有効機能）を組み立てる。 */
    public EntitlementSummaryResponse getSummary(EntitlementScopeKind scopeKind, Long scopeId) {
        // PAST_DUE も投影に載せる（支払失敗中でも期末まで利用でき、解約もできる・D4 / AC-65）。
        // 解約確認画面はこの投影だけを読むため、ここに出ない契約は FE から操作できない。
        List<BillingContractEntity> active = billingContractRepository
                .findByScopeKindAndScopeIdAndStatusInAndDeletedAtIsNull(
                        scopeKind, scopeId, DISPLAYED_CONTRACT_STATUSES);

        ActiveContract activePlan = null;
        ContractStatus activePlanStatus = null;
        List<ActiveContract> activeAddons = new ArrayList<>();
        for (BillingContractEntity c : active) {
            ActiveContract dto = toActiveContract(c);
            if (c.getContractKind() == ContractKind.PLAN) {
                // ACTIVE と PAST_DUE が同時に並ぶ異常時でも見え方を一意にする（ACTIVE を優先）。
                if (activePlan == null || (activePlanStatus != ContractStatus.ACTIVE
                        && c.getStatus() == ContractStatus.ACTIVE)) {
                    activePlan = dto;
                    activePlanStatus = c.getStatus();
                }
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
     * 契約 1 件を表示用の投影へ落とす（PR6a AC-60 / AC-63 / AC-65）。
     *
     * <p><b>Stripe を一度も呼ばない</b>（AC-63。正本 05:369）。{@code canCancel} / {@code canResume} は
     * DB の {@code status} / {@code cancelled_at} / {@code current_period_end} と注入 {@link Clock} だけから
     * {@link BillingCancelState} が導出する。解約 API の応答と同じ関数を使うので、両者が食い違わない。</p>
     */
    private ActiveContract toActiveContract(BillingContractEntity c) {
        var now = LocalDateTime.now(clock);
        var endAt = c.getCurrentPeriodEnd();
        boolean scheduled = BillingCancelState.scheduled(c.getStatus(), c.getCancelledAt());
        return ActiveContract.builder()
                .contractId(c.getId().toString())
                .planKey(c.getPlanKey())
                .featureKey(c.getFeatureKey())
                .contractedAt(c.getContractedAt())
                .priceJpySnapshot(c.getPriceJpySnapshot())
                .status(c.getStatus() == null ? null : c.getStatus().name())
                .currentPeriodEnd(toOffset(endAt))
                .canCancel(BillingCancelState.canCancel(c.getStatus(), c.getCancelledAt()))
                .canResume(BillingCancelState.canResume(c.getStatus(), c.getCancelledAt(), endAt, now))
                .cancel(scheduled
                        ? ActiveContract.ScheduledCancel.builder()
                                .scheduledAt(toOffset(c.getCancelledAt()))
                                .endAt(toOffset(endAt))
                                .build()
                        : null)
                .build();
    }

    /** DB の壁時計値を、注入 {@link Clock} のゾーンでオフセット付きへ変換する唯一の変換点。 */
    private OffsetDateTime toOffset(LocalDateTime value) {
        return value == null ? null : value.atZone(clock.getZone()).toOffsetDateTime();
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
