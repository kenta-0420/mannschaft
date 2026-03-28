package com.mannschaft.app.team.repository;

import com.mannschaft.app.team.entity.TeamEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * チームリポジトリ。
 */
public interface TeamRepository extends JpaRepository<TeamEntity, Long> {

    List<TeamEntity> findByVisibility(TeamEntity.Visibility visibility);

    @Query("SELECT t FROM TeamEntity t WHERE t.name LIKE %:keyword% OR t.nameKana LIKE %:keyword%")
    Page<TeamEntity> searchByKeyword(@Param("keyword") String keyword, Pageable pageable);

    /**
     * 広告セグメント用: アクティブなチームをテンプレート・都道府県でフィルタリングする。
     */
    @Query("""
            SELECT t FROM TeamEntity t
            WHERE t.deletedAt IS NULL
              AND t.archivedAt IS NULL
              AND (:template IS NULL OR t.template = :template)
              AND (:prefecture IS NULL OR t.prefecture = :prefecture)
            ORDER BY t.id ASC
            """)
    Page<TeamEntity> findActiveTeamsForSegment(
            @Param("template") String template,
            @Param("prefecture") String prefecture,
            Pageable pageable);
}
