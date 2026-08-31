package com.mannschaft.app.role.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.interceptor.CacheErrorHandler;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("RolePermissionCacheMembershipListener")
class RolePermissionCacheMembershipListenerTest {

    @Mock
    private CacheManager cacheManager;

    @Mock
    private CacheErrorHandler cacheErrorHandler;

    @Mock
    private Cache cache;

    @Test
    @DisplayName("afterCommit event の user/scope キーだけ evict する")
    void evictsOnlyEventKey() {
        given(cacheManager.getCache("role-permissions")).willReturn(cache);
        RolePermissionCacheMembershipListener listener =
                new RolePermissionCacheMembershipListener(cacheManager, cacheErrorHandler);

        listener.onMembershipChanged(new MembershipChangedEvent(
                42L, "TEAM", 7L, MembershipChangedEvent.ChangeType.CHANGED));

        verify(cache).evict("42:TEAM:7");
    }

    @Test
    @DisplayName("cacheManager が利用不能でも fail-open で error handler に委譲する")
    void cacheManagerFailureIsFailOpen() {
        RuntimeException failure = new IllegalStateException("cache unavailable");
        given(cacheManager.getCache("role-permissions")).willThrow(failure);
        RolePermissionCacheMembershipListener listener =
                new RolePermissionCacheMembershipListener(cacheManager, cacheErrorHandler);

        listener.onMembershipChanged(new MembershipChangedEvent(
                42L, "ORGANIZATION", 8L, MembershipChangedEvent.ChangeType.REMOVED));

        verify(cacheErrorHandler).handleCacheEvictError(failure, null, "42:ORGANIZATION:8");
    }

    @Test
    @DisplayName("1件の evict 例外は error handler に委譲しイベント処理を継続する")
    void evictionFailureIsFailOpen() {
        RuntimeException failure = new IllegalStateException("evict unavailable");
        given(cacheManager.getCache("role-permissions")).willReturn(cache);
        doThrow(failure).when(cache).evict("42:TEAM:7");
        RolePermissionCacheMembershipListener listener =
                new RolePermissionCacheMembershipListener(cacheManager, cacheErrorHandler);

        listener.onMembershipChanged(new MembershipChangedEvent(
                42L, "TEAM", 7L, MembershipChangedEvent.ChangeType.ASSIGNED));

        verify(cacheErrorHandler).handleCacheEvictError(failure, cache, "42:TEAM:7");
    }

    @Test
    @DisplayName("cache が未登録なら何も evict しない")
    void absentCacheIsIgnored() {
        given(cacheManager.getCache("role-permissions")).willReturn(null);
        RolePermissionCacheMembershipListener listener =
                new RolePermissionCacheMembershipListener(cacheManager, cacheErrorHandler);

        listener.onMembershipChanged(new MembershipChangedEvent(
                42L, "TEAM", 7L, MembershipChangedEvent.ChangeType.REMOVED));

        verify(cache, never()).evict("42:TEAM:7");
        verify(cacheErrorHandler, never()).handleCacheEvictError(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString());
    }
}
