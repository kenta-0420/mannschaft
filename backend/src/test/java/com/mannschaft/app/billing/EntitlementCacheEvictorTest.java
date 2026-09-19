package com.mannschaft.app.billing;

import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EntitlementCacheEvictorTest {

    private final CacheManager cacheManager = mock(CacheManager.class);
    private final Cache entitlementCache = mock(Cache.class);
    private final Cache teamPlanCache = mock(Cache.class);
    private final EntitlementCacheEvictor evictor = new EntitlementCacheEvictor(cacheManager);

    @Test
    void afterCommit呼出でも再遅延させず個別キーを即時削除する() {
        when(cacheManager.getCache(EntitlementCacheEvictor.ENTITLEMENT_CHECK_CACHE))
                .thenReturn(entitlementCache);
        when(cacheManager.getCache(EntitlementCacheEvictor.TEAM_PLAN_CACHE))
                .thenReturn(teamPlanCache);

        evictor.evictScopeFeatures(EntitlementScopeKind.TEAM, 10L, List.of("FEATURE_A"));

        verify(entitlementCache).evictIfPresent("TEAM:10:FEATURE_A");
        verify(teamPlanCache).evictIfPresent(10L);
    }
}
