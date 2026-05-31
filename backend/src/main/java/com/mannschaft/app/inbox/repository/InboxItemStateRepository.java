package com.mannschaft.app.inbox.repository;

import com.mannschaft.app.common.repository.AbstractUserOwnedRepository;
import com.mannschaft.app.inbox.InboxSourceType;
import com.mannschaft.app.inbox.entity.InboxItemStateEntity;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * F04.11 統合通知インボックス：triage 状態オーバーレイ Repository。
 *
 * <p>{@code organization_id} を持たない user_id 単位の個人データのため
 * {@code AbstractUserOwnedRepository}（全クエリに user_id 必須）を継承する（原則7 代替・IDOR 防止）。</p>
 */
public interface InboxItemStateRepository
        extends AbstractUserOwnedRepository<InboxItemStateEntity, UUID> {

    /**
     * 通知 1 件の triage 状態を取得する（upsert キー）。
     */
    Optional<InboxItemStateEntity> findByUserIdAndSourceTypeAndSourceId(
            Long userId, InboxSourceType sourceType, Long sourceId);

    /**
     * 複数ソース種別の triage 状態をまとめ取りする（N+1 回避）。
     */
    List<InboxItemStateEntity> findByUserIdAndSourceTypeIn(
            Long userId, Collection<InboxSourceType> sourceTypes);

    /**
     * ユーザーの全 triage 状態を削除する（退会時の物理削除用）。
     */
    @Modifying
    @Query("DELETE FROM InboxItemStateEntity e WHERE e.userId = :userId")
    void deleteAllByUserId(Long userId);

    /**
     * triage 状態を物理削除する（両カラム NULL の遅延削除用）。
     */
    @Override
    void delete(InboxItemStateEntity entity);
}
