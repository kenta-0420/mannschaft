package com.mannschaft.app.inbox.repository;

import com.mannschaft.app.common.repository.AbstractUserOwnedRepository;
import com.mannschaft.app.inbox.InboxSourceType;
import com.mannschaft.app.inbox.entity.InboxLabelLinkEntity;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * F04.11 統合通知インボックス：ラベル↔通知リンク Repository。
 *
 * <p>user_id 単位の個人データのため {@code AbstractUserOwnedRepository} を継承する（IDOR 防止）。</p>
 */
public interface InboxLabelLinkRepository
        extends AbstractUserOwnedRepository<InboxLabelLinkEntity, UUID> {

    /**
     * インボックス一覧時にラベルを {@code IN} でまとめ取りする（N+1 回避）。
     */
    List<InboxLabelLinkEntity> findByUserIdAndSourceTypeAndSourceIdIn(
            Long userId, InboxSourceType sourceType, Collection<Long> sourceIds);

    /**
     * 同一ラベルの重複付与チェック（冪等付与の判定用）。
     */
    boolean existsByLabelIdAndSourceTypeAndSourceId(
            UUID labelId, InboxSourceType sourceType, Long sourceId);

    /**
     * 付与解除用に該当リンク 1 件を取得する（無ければ冪等に無視）。
     */
    Optional<InboxLabelLinkEntity> findByLabelIdAndSourceTypeAndSourceId(
            UUID labelId, InboxSourceType sourceType, Long sourceId);

    /**
     * 1 通知あたりのラベル付与数を取得する（上限 10 検証用）。
     */
    long countByUserIdAndSourceTypeAndSourceId(
            Long userId, InboxSourceType sourceType, Long sourceId);

    /**
     * ユーザーの全リンクを削除する（退会時の物理削除用）。
     */
    @Modifying
    @Query("DELETE FROM InboxLabelLinkEntity e WHERE e.userId = :userId")
    void deleteAllByUserId(Long userId);
}
