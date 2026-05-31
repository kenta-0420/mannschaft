package com.mannschaft.app.inbox.repository;

import com.mannschaft.app.common.repository.AbstractUserOwnedRepository;
import com.mannschaft.app.inbox.entity.NotificationLabelEntity;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

/**
 * F04.11 統合通知インボックス：軽量ラベル Repository。
 *
 * <p>{@code @SQLRestriction("deleted_at IS NULL")} により論理削除済みは全クエリから自動除外される。
 * user_id 単位の個人データのため {@code AbstractUserOwnedRepository} を継承する（IDOR 防止）。</p>
 */
public interface NotificationLabelRepository
        extends AbstractUserOwnedRepository<NotificationLabelEntity, UUID> {

    /**
     * ユーザーの現役ラベルを表示順（昇順）で取得する。
     */
    List<NotificationLabelEntity> findByUserIdOrderBySortOrderAsc(Long userId);

    /**
     * ユーザーの全ラベルを物理削除する（退会時の物理削除用）。
     *
     * <p>{@code @SQLRestriction} は SELECT のみに適用されるため、論理削除済みを含む全行を
     * 物理削除するには明示的な JPQL DELETE を用いる。</p>
     */
    @Modifying
    @Query("DELETE FROM NotificationLabelEntity e WHERE e.userId = :userId")
    void deleteAllByUserId(Long userId);
}
