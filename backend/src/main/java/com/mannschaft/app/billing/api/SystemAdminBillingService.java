package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.BillingContractEntity;
import com.mannschaft.app.billing.BillingContractRepository;
import com.mannschaft.app.billing.BillingContractService;
import com.mannschaft.app.billing.BillingContractService.ContractResult;
import com.mannschaft.app.billing.ContractKind;
import com.mannschaft.app.billing.ContractStatus;
import com.mannschaft.app.billing.EntitlementErrorCode;
import com.mannschaft.app.billing.EntitlementScopeKind;
import com.mannschaft.app.billing.FeatureCatalogEntity;
import com.mannschaft.app.billing.FeatureCatalogRepository;
import com.mannschaft.app.billing.FeatureCategory;
import com.mannschaft.app.billing.PlanEntity;
import com.mannschaft.app.billing.PlanFeatureEntity;
import com.mannschaft.app.billing.PlanFeatureRepository;
import com.mannschaft.app.billing.PlanPriceBandEntity;
import com.mannschaft.app.billing.PlanPriceBandRepository;
import com.mannschaft.app.billing.PlanPriceBandScopeKind;
import com.mannschaft.app.billing.PlanRepository;
import com.mannschaft.app.billing.api.dto.ContractResponse;
import com.mannschaft.app.billing.api.dto.FeatureAdminResponse;
import com.mannschaft.app.billing.api.dto.FeatureUpsertRequest;
import com.mannschaft.app.billing.api.dto.ManualGrantRequest;
import com.mannschaft.app.billing.api.dto.PagedContractResponse;
import com.mannschaft.app.billing.api.dto.PlanAdminResponse;
import com.mannschaft.app.billing.api.dto.PlanFeaturesReplaceRequest;
import com.mannschaft.app.billing.api.dto.PlanUpsertRequest;
import com.mannschaft.app.billing.api.dto.PriceBandsReplaceRequest;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.team.service.TeamOrgMembershipQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * F20.1: シスアド運用 API のサービス（マスタ CRUD・手動付与・契約横断検索・設計書 02 §4）。
 *
 * <p>認可は Controller の {@code @PreAuthorize("hasRole('SYSTEM_ADMIN')")} ＋ SecurityConfig の
 * {@code /api/v1/system-admin/**} パスルールで二重担保する（03 §1・AC-17）。マスタ整合の一次防御
 * （feature 実在・バンド昇順・REVENUE×非営利無料の排他）は本サービスで {@code ENTITLEMENT_010}（400）
 * として拒否する（症状を隠さず明示エラー）。</p>
 */
@Service
@RequiredArgsConstructor
@Transactional
public class SystemAdminBillingService {

    private final PlanRepository planRepository;
    private final FeatureCatalogRepository featureCatalogRepository;
    private final PlanFeatureRepository planFeatureRepository;
    private final PlanPriceBandRepository planPriceBandRepository;
    private final BillingContractRepository billingContractRepository;
    private final BillingContractService billingContractService;
    private final TeamOrgMembershipQueryService teamOrgMembershipQueryService;

    // ============================================================
    // プラン CRUD
    // ============================================================

    @Transactional(readOnly = true)
    public List<PlanAdminResponse> listPlans() {
        return planRepository.findAll().stream()
                .sorted(Comparator.comparing(p -> p.getSortOrder() == null ? 0 : p.getSortOrder()))
                .map(SystemAdminBillingService::toPlanResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public PlanAdminResponse getPlan(String planKey) {
        return toPlanResponse(loadPlan(planKey));
    }

    public PlanAdminResponse createPlan(String planKey, PlanUpsertRequest req) {
        if (planRepository.existsById(planKey)) {
            // 既存キーの再作成は不可（更新は PUT）。マスタ整合違反として拒否する。
            throw new BusinessException(EntitlementErrorCode.PLAN_MASTER_VALIDATION_FAILED);
        }
        PlanEntity plan = PlanEntity.builder()
                .planKey(planKey)
                .displayNameKey(req.displayNameKey())
                .descriptionKey(req.descriptionKey())
                .baseMonthlyPriceJpy(req.baseMonthlyPriceJpy())
                .sortOrder(req.sortOrder())
                .enabled(req.enabled())
                .build();
        return toPlanResponse(planRepository.save(plan));
    }

    public PlanAdminResponse updatePlan(String planKey, PlanUpsertRequest req) {
        PlanEntity plan = loadPlan(planKey);
        plan.setDisplayNameKey(req.displayNameKey());
        plan.setDescriptionKey(req.descriptionKey());
        plan.setBaseMonthlyPriceJpy(req.baseMonthlyPriceJpy());
        plan.setSortOrder(req.sortOrder());
        plan.setEnabled(req.enabled());
        return toPlanResponse(planRepository.save(plan));
    }

    public void deletePlan(String planKey) {
        PlanEntity plan = loadPlan(planKey);
        boolean referenced = billingContractRepository
                .existsByPlanKeyAndStatusAndDeletedAtIsNull(planKey, ContractStatus.ACTIVE)
                || !planFeatureRepository.findByPlanKey(planKey).isEmpty();
        if (referenced) {
            throw new BusinessException(EntitlementErrorCode.PLAN_MASTER_IN_USE);
        }
        planRepository.delete(plan);
    }

    // ============================================================
    // 機能カタログ CRUD
    // ============================================================

    @Transactional(readOnly = true)
    public List<FeatureAdminResponse> listFeatures() {
        return featureCatalogRepository.findAll().stream()
                .sorted(Comparator.comparing(f -> f.getSortOrder() == null ? 0 : f.getSortOrder()))
                .map(SystemAdminBillingService::toFeatureResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public FeatureAdminResponse getFeature(String featureKey) {
        return toFeatureResponse(loadFeature(featureKey));
    }

    public FeatureAdminResponse createFeature(String featureKey, FeatureUpsertRequest req) {
        if (featureCatalogRepository.existsById(featureKey)) {
            throw new BusinessException(EntitlementErrorCode.PLAN_MASTER_VALIDATION_FAILED);
        }
        FeatureCategory category = parseCategory(req.category());
        validateRevenueNonprofit(category, req.freeForNonprofit());
        FeatureCatalogEntity feature = FeatureCatalogEntity.builder()
                .featureKey(featureKey)
                .category(category)
                .addonAvailable(req.addonAvailable())
                .addonPriceJpy(req.addonPriceJpy())
                .freeForNonprofit(req.freeForNonprofit())
                .displayNameKey(req.displayNameKey())
                .descriptionKey(req.descriptionKey())
                .sortOrder(req.sortOrder())
                .enabled(req.enabled())
                .build();
        return toFeatureResponse(featureCatalogRepository.save(feature));
    }

    public FeatureAdminResponse updateFeature(String featureKey, FeatureUpsertRequest req) {
        FeatureCatalogEntity feature = loadFeature(featureKey);
        FeatureCategory category = parseCategory(req.category());
        validateRevenueNonprofit(category, req.freeForNonprofit());
        feature.setCategory(category);
        feature.setAddonAvailable(req.addonAvailable());
        feature.setAddonPriceJpy(req.addonPriceJpy());
        feature.setFreeForNonprofit(req.freeForNonprofit());
        feature.setDisplayNameKey(req.displayNameKey());
        feature.setDescriptionKey(req.descriptionKey());
        feature.setSortOrder(req.sortOrder());
        feature.setEnabled(req.enabled());
        return toFeatureResponse(featureCatalogRepository.save(feature));
    }

    public void deleteFeature(String featureKey) {
        FeatureCatalogEntity feature = loadFeature(featureKey);
        boolean referenced = billingContractRepository
                .existsByFeatureKeyAndStatusAndDeletedAtIsNull(featureKey, ContractStatus.ACTIVE)
                || !planFeatureRepository.findByFeatureKey(featureKey).isEmpty();
        if (referenced) {
            throw new BusinessException(EntitlementErrorCode.PLAN_MASTER_IN_USE);
        }
        featureCatalogRepository.delete(feature);
    }

    // ============================================================
    // plan_features 一括置換
    // ============================================================

    public void replacePlanFeatures(String planKey, PlanFeaturesReplaceRequest req) {
        loadPlan(planKey); // 存在検証（404）。
        List<String> keys = req.featureKeys() == null ? List.of() : req.featureKeys();
        for (String featureKey : keys) {
            if (!featureCatalogRepository.existsById(featureKey)) {
                // 実在しない feature を掲載しようとした（マスタ整合違反・400）。
                throw new BusinessException(EntitlementErrorCode.PLAN_MASTER_VALIDATION_FAILED);
            }
        }
        planFeatureRepository.deleteAll(planFeatureRepository.findByPlanKey(planKey));
        planFeatureRepository.flush();
        List<PlanFeatureEntity> rows = new ArrayList<>();
        for (String featureKey : keys.stream().distinct().toList()) {
            rows.add(PlanFeatureEntity.builder().planKey(planKey).featureKey(featureKey).build());
        }
        planFeatureRepository.saveAll(rows);
    }

    // ============================================================
    // price-bands 一括置換
    // ============================================================

    public void replacePriceBands(String planKey, PriceBandsReplaceRequest req) {
        loadPlan(planKey); // 存在検証（404）。
        List<PriceBandsReplaceRequest.PriceBandInput> bands =
                req.bands() == null ? List.of() : req.bands();

        // scopeKind ごとに band_no 昇順・min = 前 max+1・最終のみ max=null を検証する。
        for (PlanPriceBandScopeKind scope : PlanPriceBandScopeKind.values()) {
            List<PriceBandsReplaceRequest.PriceBandInput> group = bands.stream()
                    .filter(b -> scope == BillingApiSupport.toBandScope(
                            BillingApiSupport.parseScopeKind(b.scopeKind())))
                    .sorted(Comparator.comparingInt(b -> b.bandNo()))
                    .toList();
            validateBandGroup(group);
        }

        planPriceBandRepository.deleteAll(planPriceBandRepository.findByPlanKey(planKey));
        planPriceBandRepository.flush();
        List<PlanPriceBandEntity> rows = new ArrayList<>();
        for (PriceBandsReplaceRequest.PriceBandInput b : bands) {
            PlanPriceBandScopeKind scope = toBandScope(b.scopeKind());
            rows.add(PlanPriceBandEntity.builder()
                    .planKey(planKey)
                    .scopeKind(scope)
                    .bandNo(b.bandNo())
                    .minMembers(b.minMembers())
                    .maxMembers(b.maxMembers())
                    .monthlyPriceJpy(b.monthlyPriceJpy())
                    .build());
        }
        planPriceBandRepository.saveAll(rows);
    }

    private void validateBandGroup(List<PriceBandsReplaceRequest.PriceBandInput> group) {
        for (int i = 0; i < group.size(); i++) {
            PriceBandsReplaceRequest.PriceBandInput b = group.get(i);
            boolean isLast = i == group.size() - 1;
            if (!isLast && b.maxMembers() == null) {
                // 最終バンド以外で上限 null は不可。
                throw new BusinessException(EntitlementErrorCode.PLAN_MASTER_VALIDATION_FAILED);
            }
            if (i > 0) {
                Integer prevMax = group.get(i - 1).maxMembers();
                if (prevMax == null || b.minMembers() != prevMax + 1) {
                    throw new BusinessException(EntitlementErrorCode.PLAN_MASTER_VALIDATION_FAILED);
                }
            }
        }
    }

    // ============================================================
    // 手動付与
    // ============================================================

    /** 手動付与（契約行を作って発行・§3.1 と同一処理・created_by=シスアド・REVENUE イベント非発火）。 */
    public ContractResponse grant(ManualGrantRequest req, Long sysAdminUserId) {
        EntitlementScopeKind scopeKind = BillingApiSupport.parseScopeKind(req.scopeKind());
        ContractKind contractKind = BillingApiSupport.parseContractKind(req.contractKind());
        Long organizationId = resolveOrganizationId(scopeKind, req.scopeId());
        ContractResult result = billingContractService.createContractBySystemAdmin(
                scopeKind, req.scopeId(), organizationId, contractKind,
                req.planKey(), req.featureKey(), sysAdminUserId);
        return toContractResponse(result);
    }

    // ============================================================
    // 契約横断検索
    // ============================================================

    /** 契約横断検索の 1 ページ最大件数（無制限の巨大クエリを防ぐ・promotion 側 max50 に揃える）。 */
    static final int MAX_PAGE_SIZE = 50;

    @Transactional(readOnly = true)
    public PagedContractResponse searchContracts(
            String scopeKindRaw, Long scopeId, String statusRaw, int page, int size) {
        EntitlementScopeKind scopeKind = scopeKindRaw == null || scopeKindRaw.isBlank()
                ? null : BillingApiSupport.parseScopeKind(scopeKindRaw);
        ContractStatus status = statusRaw == null || statusRaw.isBlank()
                ? null : parseStatus(statusRaw);
        // size は上限 MAX_PAGE_SIZE でキャップし、下限 1 を保証する（0/負値の IllegalArgument を防ぐ）。
        int effectiveSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        int effectivePage = Math.max(0, page);
        Page<BillingContractEntity> result = billingContractRepository
                .searchContracts(scopeKind, scopeId, status, PageRequest.of(effectivePage, effectiveSize));
        List<ContractResponse> content = result.getContent().stream()
                .map(SystemAdminBillingService::toContractResponse)
                .toList();
        return PagedContractResponse.builder()
                .content(content)
                .page(effectivePage)
                .size(effectiveSize)
                .totalElements(result.getTotalElements())
                .build();
    }

    // ============================================================
    // ヘルパ
    // ============================================================

    private Long resolveOrganizationId(EntitlementScopeKind scopeKind, Long scopeId) {
        return switch (scopeKind) {
            case USER -> null;
            case ORG -> scopeId;
            case TEAM -> {
                List<Long> orgIds = teamOrgMembershipQueryService.findActiveOrganizationIds(scopeId);
                yield orgIds.isEmpty() ? null : orgIds.get(0);
            }
        };
    }

    private PlanEntity loadPlan(String planKey) {
        return planRepository.findById(planKey)
                .orElseThrow(() -> new BusinessException(EntitlementErrorCode.PLAN_NOT_FOUND));
    }

    private FeatureCatalogEntity loadFeature(String featureKey) {
        return featureCatalogRepository.findById(featureKey)
                .orElseThrow(() -> new BusinessException(EntitlementErrorCode.FEATURE_NOT_FOUND));
    }

    private static FeatureCategory parseCategory(String raw) {
        try {
            return FeatureCategory.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new BusinessException(EntitlementErrorCode.PLAN_MASTER_VALIDATION_FAILED);
        }
    }

    private static ContractStatus parseStatus(String raw) {
        try {
            return ContractStatus.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(EntitlementErrorCode.PLAN_MASTER_VALIDATION_FAILED);
        }
    }

    private static PlanPriceBandScopeKind toBandScope(String raw) {
        PlanPriceBandScopeKind scope = BillingApiSupport.toBandScope(BillingApiSupport.parseScopeKind(raw));
        if (scope == null) {
            // USER はバンドを持てない。
            throw new BusinessException(EntitlementErrorCode.PLAN_MASTER_VALIDATION_FAILED);
        }
        return scope;
    }

    private void validateRevenueNonprofit(FeatureCategory category, boolean freeForNonprofit) {
        if (category == FeatureCategory.REVENUE && freeForNonprofit) {
            // 収益機能は区分問わず有料（README 原則）。
            throw new BusinessException(EntitlementErrorCode.PLAN_MASTER_VALIDATION_FAILED);
        }
    }

    private static PlanAdminResponse toPlanResponse(PlanEntity p) {
        return PlanAdminResponse.builder()
                .planKey(p.getPlanKey())
                .displayNameKey(p.getDisplayNameKey())
                .descriptionKey(p.getDescriptionKey())
                .baseMonthlyPriceJpy(p.getBaseMonthlyPriceJpy())
                .sortOrder(p.getSortOrder() == null ? 0 : p.getSortOrder())
                .enabled(Boolean.TRUE.equals(p.getEnabled()))
                .build();
    }

    private static FeatureAdminResponse toFeatureResponse(FeatureCatalogEntity f) {
        return FeatureAdminResponse.builder()
                .featureKey(f.getFeatureKey())
                .category(f.getCategory().name())
                .addonAvailable(Boolean.TRUE.equals(f.getAddonAvailable()))
                .addonPriceJpy(f.getAddonPriceJpy())
                .freeForNonprofit(Boolean.TRUE.equals(f.getFreeForNonprofit()))
                .displayNameKey(f.getDisplayNameKey())
                .descriptionKey(f.getDescriptionKey())
                .sortOrder(f.getSortOrder() == null ? 0 : f.getSortOrder())
                .enabled(Boolean.TRUE.equals(f.getEnabled()))
                .build();
    }

    private static ContractResponse toContractResponse(ContractResult r) {
        return ContractResponse.builder()
                .contractId(r.contractId().toString())
                .scopeKind(r.scopeKind().name())
                .scopeId(r.scopeId())
                .contractKind(r.contractKind().name())
                .planKey(r.planKey())
                .featureKey(r.featureKey())
                .status(r.status().name())
                .memberCountSnapshot(r.memberCountSnapshot())
                .bandNoSnapshot(r.bandNoSnapshot())
                .priceJpySnapshot(null)
                .contractedAt(r.contractedAt())
                .grantedFeatureKeys(r.grantedFeatureKeys())
                .build();
    }

    private static ContractResponse toContractResponse(BillingContractEntity c) {
        return ContractResponse.builder()
                .contractId(c.getId().toString())
                .scopeKind(c.getScopeKind().name())
                .scopeId(c.getScopeId())
                .contractKind(c.getContractKind().name())
                .planKey(c.getPlanKey())
                .featureKey(c.getFeatureKey())
                .status(c.getStatus().name())
                .memberCountSnapshot(c.getMemberCountSnapshot())
                .bandNoSnapshot(c.getBandNoSnapshot())
                .priceJpySnapshot(c.getPriceJpySnapshot())
                .contractedAt(c.getContractedAt())
                .grantedFeatureKeys(List.of())
                .build();
    }
}
