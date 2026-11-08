package com.mannschaft.app.loyalty.repository;

import com.mannschaft.app.loyalty.entity.LoyaltyAccountEntity;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

/** チーム境界を条件に含めるポイント台帳Repository。 */
public interface LoyaltyAccountRepository extends JpaRepository<LoyaltyAccountEntity, UUID> {

    Optional<LoyaltyAccountEntity> findByTeamIdAndUserId(Long teamId, Long userId);
    List<LoyaltyAccountEntity> findByTeamIdInAndUserIdIn(Collection<Long> teamIds, Collection<Long> userIds);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from LoyaltyAccountEntity a where a.teamId = :teamId and a.userId = :userId")
    Optional<LoyaltyAccountEntity> lockExclusive(Long teamId, Long userId);

    @Query("select a from LoyaltyAccountEntity a where a.userId = :userId order by a.id")
    List<LoyaltyAccountEntity> firstPage(Long userId, Pageable pageable);

    @Query("select a from LoyaltyAccountEntity a where a.userId = :userId and a.id > :after order by a.id")
    List<LoyaltyAccountEntity> nextPage(Long userId, UUID after, Pageable pageable);
}
