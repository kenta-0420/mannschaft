package com.mannschaft.app.role.event;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** MembershipChangedEvent後にrole-permissionsをcommit後失効させる。 */
@Component
public class RolePermissionCacheMembershipListener {

    private final CacheManager cacheManager;
    private final CacheErrorHandler cacheErrorHandler;

    public RolePermissionCacheMembershipListener(CacheManager cacheManager,
                                                 CacheErrorHandler cacheErrorHandler) {
        this.cacheManager = cacheManager;
        this.cacheErrorHandler = cacheErrorHandler;
    }

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "所属変更後の権限キャッシュ失効を止めると、剥奪済み権限がTTLまで残り認可境界を破るため")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMembershipChanged(MembershipChangedEvent event) {
        String key = event.userId() + ":" + event.scopeType() + ":" + event.scopeId();
        Cache cache = null;
        try {
            cache = cacheManager.getCache("role-permissions");
            if (cache != null) {
                cache.evict(key);
            }
        } catch (RuntimeException ex) {
            cacheErrorHandler.handleCacheEvictError(ex, cache, key);
        }
    }
}
