package com.mannschaft.app.loyalty.repository;

import com.mannschaft.app.loyalty.RequestOperation;
import com.mannschaft.app.loyalty.entity.LoyaltyTransactionEntity;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** チーム境界を条件に含めるポイント台帳Repository。 */
public interface LoyaltyTransactionRepository extends JpaRepository<LoyaltyTransactionEntity, UUID> {

    Optional<LoyaltyTransactionEntity> findByTeamIdAndActorUserIdAndRequestOperationAndRequestKey(
            Long teamId, Long actorUserId, RequestOperation requestOperation, UUID requestKey);
    boolean existsByTeamIdAndUserIdAndRuleIdAndAwardOn(Long teamId, Long userId, UUID ruleId, LocalDate awardOn);
    boolean existsByRuleId(UUID ruleId);
    List<LoyaltyTransactionEntity> findByTeamIdInAndUserIdInAndAwardOn(Collection<Long> teamIds, Collection<Long> userIds, LocalDate awardOn);

    @Query("select t from LoyaltyTransactionEntity t where t.teamId = :teamId and t.userId = :userId order by t.id desc")
    List<LoyaltyTransactionEntity> firstPage(Long teamId, Long userId, Pageable pageable);

    @Query("select t from LoyaltyTransactionEntity t where t.teamId = :teamId and t.userId = :userId and t.id < :before order by t.id desc")
    List<LoyaltyTransactionEntity> nextPage(Long teamId, Long userId, UUID before, Pageable pageable);
}
