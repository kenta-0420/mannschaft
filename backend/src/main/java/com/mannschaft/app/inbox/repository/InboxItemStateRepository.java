package com.mannschaft.app.inbox.repository;

import com.mannschaft.app.common.repository.AbstractUserOwnedRepository;
import com.mannschaft.app.inbox.InboxSourceType;
import com.mannschaft.app.inbox.entity.InboxItemStateEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
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

    /**
     * F04.11 Phase3 ②：スヌーズ復帰 push の対象を全ユーザー横断で取得する（バッチ専用）。
     *
     * <p>条件は「復帰期限到来（{@code snoozed_until <= now}）かつ未通知
     * （{@code snooze_notified_at IS NULL}）かつ非アーカイブ（{@code archived_at IS NULL}）」。
     * これは IDOR 防止の user_id スコープを<b>意図的に越える</b>横断クエリであり、
     * 一般 API からは呼ばない（{@code InboxSnoozeRevivalBatchService} 専用）。
     * 暴走防止のため必ず {@link Pageable} で 1 回の処理件数に上限を設ける。</p>
     *
     * <p>古い順（{@code snoozed_until} 昇順）に拾うことで、長く滞留した復帰待ちを優先する。</p>
     *
     * @param now      現在時刻（UTC 絶対時刻同士の比較・TZ 非依存）
     * @param pageable 1 回の処理件数上限
     * @return 復帰 push を送るべき triage 状態行
     */
    @Query("SELECT e FROM InboxItemStateEntity e "
            + "WHERE e.snoozedUntil IS NOT NULL "
            + "AND e.snoozedUntil <= :now "
            + "AND e.snoozeNotifiedAt IS NULL "
            + "AND e.archivedAt IS NULL "
            + "ORDER BY e.snoozedUntil ASC")
    List<InboxItemStateEntity> findDueForRevival(@Param("now") LocalDateTime now, Pageable pageable);
}
