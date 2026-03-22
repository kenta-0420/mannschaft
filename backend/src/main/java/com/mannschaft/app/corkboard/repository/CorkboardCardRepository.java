package com.mannschaft.app.corkboard.repository;

import com.mannschaft.app.corkboard.entity.CorkboardCardEntity;
import org.springframework.data.jpa.repository.JpaRepository;

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
    List<CorkboardCardEntity> findByCorkboardIdAndIsArchivedFalseOrderByZIndexDesc(Long corkboardId);

    /**
     * ボード内の全カード一覧を取得する（アーカイブ含む）。
     */
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
