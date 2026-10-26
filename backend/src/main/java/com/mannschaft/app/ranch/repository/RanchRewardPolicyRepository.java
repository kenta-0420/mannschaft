package com.mannschaft.app.ranch.repository;

import com.mannschaft.app.ranch.entity.RanchRewardPolicyEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
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

    /**
     * 初回DEV公開のcontrol lock下だけで使用する、四表の有無判定。行や本文は返さない。
     * MySQLのnative論理式は数値scalarを返すため、0/1を明示的にbooleanへ変換する。
     */
    default boolean isDevelopmentStoreEmpty() {
        return developmentStoreEmptyScalar().longValue() == 1L;
    }

    @Query(value = "SELECT NOT EXISTS (SELECT 1 FROM ranch_reward_policies) "
            + "AND NOT EXISTS (SELECT 1 FROM ranch_week_budgets) "
            + "AND NOT EXISTS (SELECT 1 FROM ranch_reward_decisions) "
            + "AND NOT EXISTS (SELECT 1 FROM ranch_point_ledger WHERE entry_kind = 'REWARD')", nativeQuery = true)
    Number developmentStoreEmptyScalar();

    @Query("SELECT p FROM RanchRewardPolicyEntity p WHERE p.effectiveAt <= :occurredAt "
            + "AND p.publishedAt <= :occurredAt ORDER BY p.effectiveAt DESC, p.versionNumber DESC")
    List<RanchRewardPolicyEntity> publishedFor(@Param("occurredAt") Instant occurredAt, Pageable page);

    // 隔離DEVだけ同じ制御行ロックを使う。正式経路と保存済み再送の順序は維持する。
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("SELECT p FROM RanchRewardPolicyEntity p WHERE p.effectiveAt <= :occurredAt "
            + "AND p.publishedAt <= :occurredAt ORDER BY p.effectiveAt DESC, p.versionNumber DESC")
    List<RanchRewardPolicyEntity> publishedForDevelopment(@Param("occurredAt") Instant occurredAt, Pageable page);

    @Query("SELECT p FROM RanchRewardPolicyEntity p WHERE "
            + "(:beforeVersion IS NULL OR p.versionNumber < :beforeVersion) "
            + "ORDER BY p.versionNumber DESC")
    List<RanchRewardPolicyEntity> history(@Param("beforeVersion") Long beforeVersion, Pageable page);
}
