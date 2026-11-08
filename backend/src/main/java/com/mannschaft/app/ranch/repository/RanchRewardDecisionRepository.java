package com.mannschaft.app.ranch.repository;

import com.mannschaft.app.ranch.entity.RanchRewardDecisionEntity;
import com.mannschaft.app.ranch.reward.RanchRewardSourceType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/** eventと正準keyの両方で保存済みterminalを照合する。 */
public interface RanchRewardDecisionRepository extends JpaRepository<RanchRewardDecisionEntity, UUID> {
    Optional<RanchRewardDecisionEntity> findByEventId(UUID eventId);
    long countByUserId(Long userId);
    Optional<RanchRewardDecisionEntity> findByUserIdAndSourceTypeAndCanonicalKeyHash(
            Long userId, RanchRewardSourceType sourceType, byte[] canonicalKeyHash);
}
