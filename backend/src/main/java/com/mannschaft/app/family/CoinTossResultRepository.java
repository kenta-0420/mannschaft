package com.mannschaft.app.family;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * コイントス結果リポジトリ。
 */
public interface CoinTossResultRepository extends JpaRepository<CoinTossResultEntity, Long> {

    /**
     * チームのコイントス履歴を取得する。
     */
    @Query("""
            SELECT c FROM CoinTossResultEntity c
            WHERE c.teamId = :teamId
              AND (:cursor IS NULL OR c.id < :cursor)
            ORDER BY c.id DESC
            """)
    List<CoinTossResultEntity> findHistory(
            @Param("teamId") Long teamId,
            @Param("cursor") Long cursor,
            org.springframework.data.domain.Pageable pageable);

    /**
     * レートリミット用：指定期間内のコイントス回数を取得する。
     */
    long countByTeamIdAndUserIdAndCreatedAtAfter(Long teamId, Long userId, LocalDateTime after);

    /**
     * 指定日時より前の結果を削除する（クリーンアップバッチ用）。
     */
    void deleteByCreatedAtBefore(LocalDateTime threshold);
}
