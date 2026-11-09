package com.mannschaft.app.billing;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** legacy PLANの公開履歴と価格親子をPRIMARYの同一TXで検証し、scalarへcopyする。 */
@Service
@RequiredArgsConstructor
public class LegacyPlanPriceSnapshotReader {

    private final BillingPriceVersionRepository versions;
    private final BillingPriceBandVersionRepository bands;
    private final LegacyPlanPriceMapper mapper;

    /** 非削除SCHEDULEDの半開期間に合う要求pairだけ。公開履歴flagを前段から引き継がない。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = false)
    public Set<BillingPriceSelector.PlanScopeKey> readDueKeys(
            Set<BillingPriceSelector.PlanScopeKey> keys, Instant at) {
        if (keys.isEmpty()) {
            return Set.of();
        }
        return versions.findLegacyPlanDueKeys(planKeys(keys), scopeKinds(keys), BillingProductKind.PLAN,
                        BillingPriceVersionStatus.SCHEDULED, at).stream()
                .map(row -> new BillingPriceSelector.PlanScopeKey(row.getPlanKey(), row.getScopeKind()))
                .filter(keys::contains).collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Planの保存canonical keyをDBから観測できた要求だけを未公開と分類する。
     * raw alias/不存在の内部要求は初期NOT_SELLABLEのままにし、無料へ落とさない。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = false)
    public Map<BillingPriceSelector.PlanScopeKey, BillingPriceSelector.LegacyPlanSelection> readActive(
            Set<BillingPriceSelector.PlanScopeKey> keys, Instant at) {
        if (keys.isEmpty()) {
            return Map.of();
        }
        List<BillingPriceVersionRepository.LegacyPlanParentRow> rows = versions.findLegacyPlanParents(
                planKeys(keys), scopeKinds(keys), BillingProductKind.PLAN, BillingPriceVersionStatus.ACTIVE,
                BillingPriceVersionStatus.RETIRED, BillingPriceVersionStatus.SCHEDULED, at);
        Set<String> observedPlans = rows.stream().map(BillingPriceVersionRepository.LegacyPlanParentRow::getPlanKey)
                .collect(Collectors.toSet());
        Map<BillingPriceSelector.PlanScopeKey, BillingPriceSelector.LegacyPlanSelection> result = new HashMap<>();
        for (BillingPriceSelector.PlanScopeKey key : keys) {
            result.put(key, selection(observedPlans.contains(key.planKey())
                    ? BillingPriceSelector.LegacyPlanSelectionState.NO_PUBLISHED_PRICE
                    : BillingPriceSelector.LegacyPlanSelectionState.NOT_SELLABLE));
        }
        Map<BillingPriceSelector.PlanScopeKey, List<BillingPriceVersionEntity>> parents = groupParents(keys, rows);
        Map<BillingPriceSelector.PlanScopeKey, BillingPriceVersionEntity> candidates = new HashMap<>();
        for (var entry : parents.entrySet()) {
            result.put(entry.getKey(), selection(BillingPriceSelector.LegacyPlanSelectionState.NOT_SELLABLE));
            List<BillingPriceVersionEntity> active = entry.getValue().stream()
                    .filter(parent -> parent.getStatus() == BillingPriceVersionStatus.ACTIVE)
                    .filter(parent -> isEffectiveAt(parent.getEffectiveFrom(), parent.getEffectiveUntil(), at)).toList();
            if (active.size() == 1) {
                candidates.put(entry.getKey(), active.getFirst());
            }
        }
        Map<UUID, List<BillingPriceBandVersionEntity>> children = readBands(candidates);
        for (var entry : candidates.entrySet()) {
            BillingPriceVersionEntity parent = entry.getValue();
            List<BillingPriceBandVersionEntity> priceBands = children.getOrDefault(parent.getId(), List.of());
            if (hasSellableBands(parent, priceBands, at)) {
                result.put(entry.getKey(), new BillingPriceSelector.LegacyPlanSelection(
                        BillingPriceSelector.LegacyPlanSelectionState.SELECTED,
                        mapper.toLegacySnapshot(parent, priceBands.stream().map(mapper::toLegacyBandSnapshot).toList())));
            }
        }
        return Map.copyOf(result);
    }

    private Map<BillingPriceSelector.PlanScopeKey, List<BillingPriceVersionEntity>> groupParents(
            Set<BillingPriceSelector.PlanScopeKey> keys, List<BillingPriceVersionRepository.LegacyPlanParentRow> rows) {
        Map<BillingPriceSelector.PlanScopeKey, List<BillingPriceVersionEntity>> result = new HashMap<>();
        for (var row : rows) {
            BillingPriceVersionEntity parent = row.getRevision();
            if (parent == null) {
                continue;
            }
            BillingPriceSelector.PlanScopeKey key = new BillingPriceSelector.PlanScopeKey(row.getPlanKey(), parent.getScopeKind());
            if (keys.contains(key)) {
                result.computeIfAbsent(key, ignored -> new ArrayList<>()).add(parent);
            }
        }
        return result;
    }

    private Map<UUID, List<BillingPriceBandVersionEntity>> readBands(
            Map<BillingPriceSelector.PlanScopeKey, BillingPriceVersionEntity> candidates) {
        if (candidates.isEmpty()) {
            return Map.of();
        }
        Set<UUID> ids = candidates.values().stream().map(BillingPriceVersionEntity::getId).collect(Collectors.toSet());
        return bands.findLegacyPlanBands(ids).stream()
                .collect(Collectors.groupingBy(BillingPriceBandVersionEntity::getPriceVersionId));
    }

    private boolean hasSellableBands(
            BillingPriceVersionEntity parent, List<BillingPriceBandVersionEntity> priceBands, Instant at) {
        return !priceBands.isEmpty() && priceBands.stream().allMatch(band ->
                band.getStatus() == BillingPriceVersionStatus.ACTIVE
                        && isEffectiveAt(band.getEffectiveFrom(), band.getEffectiveUntil(), at)
                        && BillingPriceSelector.isSellable(parent, band))
                && BillingPriceSelector.hasNonOverlappingRanges(priceBands);
    }

    private boolean isEffectiveAt(Instant from, Instant until, Instant at) {
        return !from.isAfter(at) && (until == null || at.isBefore(until));
    }

    private BillingPriceSelector.LegacyPlanSelection selection(BillingPriceSelector.LegacyPlanSelectionState state) {
        return new BillingPriceSelector.LegacyPlanSelection(state, null);
    }

    private Set<String> planKeys(Set<BillingPriceSelector.PlanScopeKey> keys) {
        return keys.stream().map(BillingPriceSelector.PlanScopeKey::planKey).collect(Collectors.toSet());
    }

    private Set<EntitlementScopeKind> scopeKinds(Set<BillingPriceSelector.PlanScopeKey> keys) {
        return keys.stream().map(BillingPriceSelector.PlanScopeKey::scopeKind).collect(Collectors.toSet());
    }
}
