package com.mannschaft.app.family.repository;

import com.mannschaft.app.family.entity.CoinTossResultEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * コイントス結果リポジトリ。
 */
public interface CoinTossResultRepository extends JpaRepository<CoinTossResultEntity, Long> {

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

    long countByTeamIdAndUserIdAndCreatedAtAfter(Long teamId, Long userId, LocalDateTime after);

    void deleteByCreatedAtBefore(LocalDateTime threshold);
}
