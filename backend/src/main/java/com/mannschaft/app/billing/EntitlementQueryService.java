package com.mannschaft.app.billing;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * F20.1: 権利判定の単一実装（設計書 README §3.1 の判定式・02 §1.1）。
 *
 * <p>全ゲート（{@link EntitlementGuard}・結線先 3 箇所・後方互換ブリッジ）はこの 1 本を通る。
 * 判定式（正準）:</p>
 * <pre>
 * feature = featureCatalog.find(featureKey)
 * if feature == null || !feature.enabled: return false   // fail-safe（不明/無効キーは拒否＋WARN・AC-18）
 * if featureKey ∈ planFeatures(FREE): return true         // FREE 掲載機能（AC-05）
 * if feature.freeForNonprofit && isNonProfitScope: return true  // 非営利無料枠（機構・初期値は全 FALSE）
 * return exists active entitlement                        // 半開区間 [valid_from, valid_until)・revoked_at IS NULL
 * </pre>
 *
 * <p><b>キャッシュ</b>: Valkey {@code @Cacheable("entitlement:check")} TTL 60 秒。キーの enum は
 * {@code name()} で String 化する（memory {@code feedback_cacheable_enum_key_redis}）。
 * 契約/付与/取消時は {@link EntitlementCacheEvictor} が発行/取消した feature_key 集合を個別 evict する。</p>
 *
 * <p><b>時刻</b>: {@link Clock} を注入し（{@code utcClock} Bean）、テストで固定 Clock に差し替えて
 * 半開区間の境界（AC-06/08）を決定論的に検証できるようにする。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EntitlementQueryService {

    private final FeatureCatalogRepository featureCatalogRepository;
    private final PlanFeatureRepository planFeatureRepository;
    private final EntitlementRepository entitlementRepository;
    private final ScopeClassificationService scopeClassificationService;
    private final Clock clock;

    /**
     * 権利判定の正準実装（README §3.1）。
     *
     * <p>{@code now} はキャッシュキーに含めない（TTL 60 秒の粒度で評価・AC-16 の許容範囲）。</p>
     *
     * @param scopeKind  USER / TEAM / ORG
     * @param scopeId    users.id / teams.id / organizations.id
     * @param featureKey feature_catalog.feature_key
     * @return 権利があれば true
     */
    @Cacheable(value = "entitlement:check",
            key = "#scopeKind.name() + ':' + #scopeId + ':' + #featureKey")
    public boolean isEntitled(EntitlementScopeKind scopeKind, Long scopeId, String featureKey) {
        LocalDateTime now = LocalDateTime.now(clock);
        FeatureCatalogEntity feature = featureCatalogRepository.findById(featureKey).orElse(null);
        if (feature == null || !Boolean.TRUE.equals(feature.getEnabled())) {
            // fail-safe: 不明/無効キーは拒否側に倒す（症状を隠さず WARN ログ・AC-18）。
            log.warn("isEntitled: unknown/disabled feature_key={} scope={}:{}", featureKey, scopeKind, scopeId);
            return false;
        }
        if (planFeatureRepository.existsByPlanKeyAndFeatureKey(FeatureKeys.PLAN_FREE, featureKey)) {
            return true; // FREE 掲載機能は契約ゼロで全スコープ利用可（AC-05）。
        }
        if (Boolean.TRUE.equals(feature.getFreeForNonprofit())
                && scopeClassificationService.isNonProfitScope(scopeKind, scopeId)) {
            return true; // 非営利無料枠（機構・初期値は全 FALSE ゆえ初期は到達しない）。
        }
        return entitlementRepository.existsActiveGrant(scopeKind, scopeId, featureKey, now);
    }

    /**
     * スコープが「利用できる機能」の feature_key 集合を返す（権利サマリ EP の {@code entitledFeatures} 合成・AC-23）。
     *
     * <p><b>UI と BE 判定の齟齬ゼロ</b>を保証するため、集合は必ず {@link #isEntitled} で確定する
     * （enabled なカタログ機能を候補とし、{@code isEntitled=true} のものだけを残す）。これにより
     * 「利用できる機能」一覧 ＝ {@code isEntitled=true} となる feature_key 集合が構造的に一致する
     * （FREE 掲載・非営利無料枠の virtual 合成も {@code isEntitled} 側で吸収される・設計書 02 §2.2）。</p>
     *
     * @return isEntitled=true となる feature_key 集合（sort_order 昇順・重複排除）
     */
    public Set<String> entitledFeatureKeys(EntitlementScopeKind scopeKind, Long scopeId) {
        List<FeatureCatalogEntity> enabledFeatures =
                featureCatalogRepository.findByEnabledTrueOrderBySortOrderAsc();
        Set<String> result = new LinkedHashSet<>();
        for (FeatureCatalogEntity feature : enabledFeatures) {
            String key = feature.getFeatureKey();
            // legacy.* は内部ブリッジ用途でカタログ表示から除外する（設計書 02 §2.1）。
            if (key.startsWith(FeatureKeys.CATALOG_HIDDEN_PREFIX)) {
                continue;
            }
            if (isEntitled(scopeKind, scopeId, key)) {
                result.add(key);
            }
        }
        return result;
    }
}
