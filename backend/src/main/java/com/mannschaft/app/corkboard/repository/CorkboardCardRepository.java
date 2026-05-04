package com.mannschaft.app.corkboard.repository;

import com.mannschaft.app.corkboard.entity.CorkboardCardEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * コルクボードカードリポジトリ。
 */
public interface CorkboardCardRepository extends JpaRepository<CorkboardCardEntity, Long> {

    /**
     * ボード内のアクティブなカード一覧を取得する。
     */
    @Query("SELECT c FROM CorkboardCardEntity c WHERE c.corkboardId = :corkboardId AND c.isArchived = false ORDER BY c.zIndex DESC")
    List<CorkboardCardEntity> findByCorkboardIdAndIsArchivedFalseOrderByZIndexDesc(Long corkboardId);

    /**
     * ボード内の全カード一覧を取得する（アーカイブ含む）。
     */
    @Query("SELECT c FROM CorkboardCardEntity c WHERE c.corkboardId = :corkboardId ORDER BY c.zIndex DESC")
    List<CorkboardCardEntity> findByCorkboardIdOrderByZIndexDesc(Long corkboardId);

    /**
     * ボードIDとカードIDで取得する。
     */
    Optional<CorkboardCardEntity> findByIdAndCorkboardId(Long id, Long corkboardId);

    /**
     * 自動アーカイブ対象のカードを取得する。
     */
    List<CorkboardCardEntity> findByIsArchivedFalseAndAutoArchiveAtBefore(LocalDateTime now);

    /**
     * ボード内のカード数を取得する。
     */
    long countByCorkboardId(Long corkboardId);

    /**
     * 指定ユーザーの個人スコープボード配下でピン止め中（かつ未アーカイブ・未削除）のカード数を取得する。
     * F09.8.1 ピン止め上限チェックに使用する。
     *
     * <p>{@link CorkboardCardEntity} および {@code CorkboardEntity} は
     * {@code @SQLRestriction("deleted_at IS NULL")} により、論理削除済みは自動的に除外される。
     * ただし JPQL 明示クエリでは {@code @SQLRestriction} は適用されないため、
     * 本クエリでは明示的に {@code deletedAt IS NULL} を条件に含める。</p>
     */
    @Query("""
            SELECT COUNT(c) FROM CorkboardCardEntity c, CorkboardEntity b
             WHERE c.corkboardId = b.id
               AND b.ownerId = :ownerId
               AND b.scopeType = 'PERSONAL'
               AND b.deletedAt IS NULL
               AND c.isPinned = true
               AND c.isArchived = false
               AND c.deletedAt IS NULL
            """)
    int countPinnedByOwnerIdAndScopePersonal(@Param("ownerId") Long ownerId);

    /**
     * 指定ユーザーの個人スコープボード配下のピン止めカードを横断取得する（cursor ページネーション対応）。
     *
     * <p>F09.8.1 Phase 3 ダッシュボード横断取得 API
     * {@code GET /api/v1/users/me/corkboards/pinned-cards} のメインクエリ。
     * 設計書 §5.1 のインデックス {@code idx_cc_pinned} および {@code idx_cc_pinned_at} を活用する。</p>
     *
     * <p>カーソル: {@code (pinnedAt, id)} の複合キー。降順並び替えのため、
     * 「より古い pinnedAt」または「同一 pinnedAt かつより小さい id」のレコードを次ページとして取得する。
     * 初回呼び出し時は両者を NULL で渡せばカーソル条件は無視される。</p>
     *
     * @param ownerId         所有ユーザーID
     * @param cursorPinnedAt  カーソル基準の pinned_at（NULL で先頭から）
     * @param cursorId        カーソル基準のカードID（NULL で先頭から）
     * @param pageable        取得件数（limit + 1 を渡し、次ページ有無判定に使う想定）
     */
    @Query("""
            SELECT c FROM CorkboardCardEntity c, CorkboardEntity b
             WHERE c.corkboardId = b.id
               AND b.ownerId = :ownerId
               AND b.scopeType = 'PERSONAL'
               AND b.deletedAt IS NULL
               AND c.isPinned = true
               AND c.isArchived = false
               AND c.deletedAt IS NULL
               AND (
                 :cursorPinnedAt IS NULL
                 OR c.pinnedAt < :cursorPinnedAt
                 OR (c.pinnedAt = :cursorPinnedAt AND c.id < :cursorId)
               )
             ORDER BY c.pinnedAt DESC, c.id DESC
            """)
    List<CorkboardCardEntity> findPinnedCardsForUser(
            @Param("ownerId") Long ownerId,
            @Param("cursorPinnedAt") LocalDateTime cursorPinnedAt,
            @Param("cursorId") Long cursorId,
            Pageable pageable);
}
