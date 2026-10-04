package com.mannschaft.app.ranch.repository;

import com.mannschaft.app.ranch.entity.RanchCareRuleEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 発生時点で公開・有効になっている不変規則のみを取得する。 */
public interface RanchCareRuleRepository extends JpaRepository<RanchCareRuleEntity, UUID> {
    @Query("SELECT rule FROM RanchCareRuleEntity rule WHERE rule.effectiveAt <= :at "
            + "AND rule.publishedAt <= :at ORDER BY rule.effectiveAt DESC, rule.versionNumber DESC")
    List<RanchCareRuleEntity> publishedAt(@Param("at") Instant at, Pageable pageable);
    Optional<RanchCareRuleEntity> findTopByOrderByVersionNumberDesc();
    boolean existsByEffectiveAt(Instant effectiveAt);
}