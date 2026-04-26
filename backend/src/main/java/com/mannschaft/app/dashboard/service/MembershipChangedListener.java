package com.mannschaft.app.dashboard.service;

import com.mannschaft.app.role.event.MembershipChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * F02.2.1: メンバーシップ変更イベント受信時に閲覧者ロールキャッシュを無効化するリスナー。
 *
 * <p>F01.2 の {@code RoleService.assignRole / changeRole / removeMember / leaveScope /
 * transferOwnership} 完了時に発火される {@link MembershipChangedEvent} を AFTER_COMMIT で受信し、
 * {@code dashboard:viewer-role:{userId}:{scopeType}:{scopeId}} キーを Valkey から DEL する。</p>
 *
 * <p>これにより「降格直後にダッシュボードを見ても古いロールでデータが見える」事故を最小化する
 * （TTL 60秒を待たず即時反映）。Valkey 障害時は WARN ログを残して続行（TTL で自然解消）。</p>
 *
 * <p>設計書: docs/features/F02.2.1_dashboard_widget_role_visibility.md §5（キャッシュ戦略）</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MembershipChangedListener {

    /** {@link RoleResolver} と一致させる必要がある */
    private static final String VIEWER_ROLE_CACHE_NAME = "dashboard:viewer-role";

    private final CacheManager cacheManager;

    /**
     * メンバーシップ変更イベントを受信して該当ユーザー × スコープのキャッシュを無効化する。
     *
     * <p>{@code AFTER_COMMIT} で動作するため、ロール変更が DB にコミットされた後に
     * キャッシュ DEL を行う。これにより一貫性を保ちつつ最新ロールが即座に反映される。</p>
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMembershipChanged(MembershipChangedEvent event) {
        try {
            Cache cache = cacheManager.getCache(VIEWER_ROLE_CACHE_NAME);
            if (cache == null) {
                log.warn("MembershipChangedListener: キャッシュ '{}' が未定義のためスキップ",
                        VIEWER_ROLE_CACHE_NAME);
                return;
            }
            String key = event.userId() + ":" + event.scopeType() + ":" + event.scopeId();
            cache.evict(key);
            log.debug("MembershipChangedListener: キャッシュ無効化完了 (cache={}, key={}, changeType={})",
                    VIEWER_ROLE_CACHE_NAME, key, event.changeType());
        } catch (Exception ex) {
            // Valkey 障害など → WARN だけ残し続行（60秒 TTL で自然解消）
            log.warn("MembershipChangedListener: キャッシュ無効化失敗 "
                    + "(cache={}, userId={}, scopeType={}, scopeId={}, changeType={})",
                    VIEWER_ROLE_CACHE_NAME, event.userId(), event.scopeType(),
                    event.scopeId(), event.changeType(), ex);
        }
    }
}
