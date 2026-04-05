package com.mannschaft.app.corkboard.repository;

import com.mannschaft.app.corkboard.entity.CorkboardCardEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

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
}
