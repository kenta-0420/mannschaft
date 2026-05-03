package com.mannschaft.app.corkboard.repository;

import com.mannschaft.app.corkboard.entity.CorkboardCardEntity;
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
}
