package com.mannschaft.app.ranch.repository;

import com.mannschaft.app.ranch.entity.RanchRewardPolicyEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 過去時点で既公開だった最新の政策だけを参照する。 */
public interface RanchRewardPolicyRepository extends JpaRepository<RanchRewardPolicyEntity, UUID> {
    Optional<RanchRewardPolicyEntity> findTopByOrderByVersionNumberDesc();
    boolean existsByEffectiveAt(Instant effectiveAt);

    @Query("SELECT p FROM RanchRewardPolicyEntity p WHERE p.effectiveAt <= :occurredAt "
            + "AND p.publishedAt <= :occurredAt ORDER BY p.effectiveAt DESC, p.versionNumber DESC")
    List<RanchRewardPolicyEntity> publishedFor(@Param("occurredAt") Instant occurredAt, Pageable page);

    @Query("SELECT p FROM RanchRewardPolicyEntity p WHERE "
            + "(:beforeVersion IS NULL OR p.versionNumber < :beforeVersion) "
            + "ORDER BY p.versionNumber DESC")
    List<RanchRewardPolicyEntity> history(@Param("beforeVersion") Long beforeVersion, Pageable page);
}
