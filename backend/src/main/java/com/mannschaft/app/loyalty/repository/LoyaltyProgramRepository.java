package com.mannschaft.app.loyalty.repository;

import com.mannschaft.app.loyalty.entity.LoyaltyProgramEntity;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

/** チーム境界を条件に含めるポイント台帳Repository。 */
public interface LoyaltyProgramRepository extends JpaRepository<LoyaltyProgramEntity, UUID> {

    Optional<LoyaltyProgramEntity> findByTeamId(Long teamId);

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select p from LoyaltyProgramEntity p where p.teamId = :teamId")
    Optional<LoyaltyProgramEntity> lockShared(Long teamId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from LoyaltyProgramEntity p where p.teamId = :teamId")
    Optional<LoyaltyProgramEntity> lockExclusive(Long teamId);

    List<LoyaltyProgramEntity> findByTeamIdIn(Collection<Long> teamIds);
}
