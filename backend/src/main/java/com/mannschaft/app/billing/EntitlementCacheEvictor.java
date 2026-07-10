package com.mannschaft.app.billing;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;

import java.util.Collection;

/**
 * F20.1: 権利判定キャッシュ（{@code entitlement:check}）の個別キー evict（設計書 02 §8・M-8）。
 *
 * <p><b>evict 方式は「発行/取消した feature_key 集合ぶんの個別キー evict」の 1 方式に確定</b>（A）。
 * {@code SCAN}+DEL・{@code @CacheEvict} のプレフィックス一括・{@code allEntries=true} は不採用
 * （Redis の {@code @CacheEvict} はプレフィックス一括削除不可・全消しはサンダリングヘッド）。</p>
 *
 * <p>キーの enum は {@code name()} で String 化する（{@link EntitlementQueryService#isEntitled} の
 * {@code @Cacheable} キー式と一致させる・memory {@code feedback_cacheable_enum_key_redis}）。
 * TEAM スコープ変更時は後方互換ブリッジの {@code teamPlan} キャッシュ（{@code TeamPlanService}）も同時 evict する
 * （README §4.1）。</p>
 *
 * <p><b>セキュリティ無効化の非ロールバック</b>: evict はキャッシュ操作であり DB トランザクションと独立している。
 * 取消/契約変更の書き込みが確定した後に呼ぶことで、権利剥奪が確実に反映される（設計書 03 §5・AC-16）。
 * Valkey 障害時は WARN ログのみで続行し（TTL 60 秒で自然収束）、業務処理は止めない。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EntitlementCacheEvictor {

    /** {@link EntitlementQueryService#isEntitled} の {@code @Cacheable} value と一致させる。 */
    static final String ENTITLEMENT_CHECK_CACHE = "entitlement:check";

    /** {@code TeamPlanService.hasPaidPlan} の {@code @Cacheable("teamPlan")} と一致させる。 */
    static final String TEAM_PLAN_CACHE = "teamPlan";

    private final CacheManager cacheManager;

    /**
     * 指定スコープ×feature_key 集合の判定キャッシュを個別 evict する。
     * TEAM スコープのときは {@code teamPlan} も同時 evict する（後方互換ブリッジ・AC-16）。
     *
     * @param scopeKind   USER / TEAM / ORG
     * @param scopeId     users.id / teams.id / organizations.id
     * @param featureKeys evict 対象の feature_key 集合（PLAN=plan_features / ADDON={featureKey} / 変更=旧∪新）
     */
    public void evictScopeFeatures(
            EntitlementScopeKind scopeKind, Long scopeId, Collection<String> featureKeys) {
        if (scopeKind == null || scopeId == null || featureKeys == null || featureKeys.isEmpty()) {
            return;
        }
        Cache cache = cacheManager.getCache(ENTITLEMENT_CHECK_CACHE);
        if (cache == null) {
            log.warn("EntitlementCacheEvictor: キャッシュ '{}' が未定義（cacheManager={}）",
                    ENTITLEMENT_CHECK_CACHE, cacheManager.getClass().getSimpleName());
        } else {
            for (String featureKey : featureKeys) {
                if (featureKey == null) {
                    continue;
                }
                try {
                    cache.evict(scopeKind.name() + ":" + scopeId + ":" + featureKey);
                } catch (RuntimeException ex) {
                    log.warn("EntitlementCacheEvictor: 判定キャッシュ evict 失敗 (scope={}:{}, feature={})",
                            scopeKind, scopeId, featureKey, ex);
                }
            }
        }
        if (scopeKind == EntitlementScopeKind.TEAM) {
            evictTeamPlan(scopeId);
        }
    }

    /** {@code teamPlan} キャッシュ（後方互換ブリッジ）を evict する。 */
    public void evictTeamPlan(Long teamId) {
        if (teamId == null) {
            return;
        }
        Cache cache = cacheManager.getCache(TEAM_PLAN_CACHE);
        if (cache == null) {
            return;
        }
        try {
            cache.evict(teamId);
        } catch (RuntimeException ex) {
            log.warn("EntitlementCacheEvictor: teamPlan キャッシュ evict 失敗 (teamId={})", teamId, ex);
        }
    }
}
