package com.mannschaft.app.team;

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
}
