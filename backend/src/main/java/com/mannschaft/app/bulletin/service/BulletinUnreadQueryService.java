package com.mannschaft.app.bulletin.service;

import com.mannschaft.app.bulletin.ScopeType;
import com.mannschaft.app.bulletin.repository.BulletinReadStatusRepository;
import com.mannschaft.app.bulletin.repository.BulletinThreadRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * 掲示板の既読・未読の一括照会サービス（CMP-261103-1225 / #4270 の N+1 解消用・read-only）。
 *
 * <p>ダッシュボードがスレッドごとに {@code existsByThreadIdAndUserId} を呼んでいた箇所
 * （スレッド数比例の SQL）を、まとめて 1 クエリで引けるようにする。他ドメインから bulletin の
 * Repository を直接参照しない（番人 D-5）ため本 Service を経由させ、シグネチャには ID と数値だけを出す
 * （番人 D-1 API 境界）。入力が空なら SQL を発行しない。</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BulletinUnreadQueryService {

    private final BulletinThreadRepository threadRepository;
    private final BulletinReadStatusRepository readStatusRepository;

    /**
     * 指定スレッド群のうち、指定ユーザーが既読のスレッド ID を返す。
     *
     * @param threadIds スレッド ID 群
     * @param userId    ユーザー ID
     * @return 既読スレッド ID の集合（入力が空なら空集合）
     */
    public Set<Long> findReadThreadIds(Collection<Long> threadIds, Long userId) {
        if (threadIds == null || threadIds.isEmpty()) {
            return new HashSet<>();
        }
        return new HashSet<>(readStatusRepository.findReadThreadIds(threadIds, userId));
    }

    /**
     * 同一スコープ種別の複数スコープについて、スコープごとの先頭 {@code perScopeLimit} 件
     * （ピン留め優先 → 更新日時降順）のうち未読のスレッド数を返す。
     *
     * <p>スコープごとに {@code findByScopeTypeAndScopeIdOrderByIsPinnedDescUpdatedAtDesc} で先頭 N 件を取り、
     * 各スレッドを既読判定していた旧実装と同じ件数を 1 クエリで返す。</p>
     *
     * @param scopeType     スコープ種別
     * @param scopeIds      スコープ ID 群（空なら 0）
     * @param perScopeLimit スコープごとの走査上限
     * @param userId        ユーザー ID
     * @return 未読スレッド数
     */
    public long countUnreadInTopThreadsPerScope(ScopeType scopeType, Collection<Long> scopeIds,
                                                int perScopeLimit, Long userId) {
        if (scopeIds == null || scopeIds.isEmpty()) {
            return 0L;
        }
        return threadRepository.countUnreadInTopThreadsPerScope(scopeType.name(), scopeIds, perScopeLimit, userId);
    }
}
