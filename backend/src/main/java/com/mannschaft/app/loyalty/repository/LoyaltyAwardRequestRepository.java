package com.mannschaft.app.loyalty.repository;

import com.mannschaft.app.loyalty.RequestOperation;
import com.mannschaft.app.loyalty.entity.LoyaltyAwardRequestEntity;
import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

/** チーム境界を条件に含めるポイント台帳Repository。 */
public interface LoyaltyAwardRequestRepository extends JpaRepository<LoyaltyAwardRequestEntity, UUID> {

    Optional<LoyaltyAwardRequestEntity> findByIdAndTeamId(UUID id, Long teamId);
    Optional<LoyaltyAwardRequestEntity> findByIdAndUserId(UUID id, Long userId);
    Optional<LoyaltyAwardRequestEntity> findByTeamIdAndUserIdAndSubmitKey(Long teamId, Long userId, UUID submitKey);
    Optional<LoyaltyAwardRequestEntity> findByTeamIdAndTerminalActorUserIdAndTerminalActionAndActionKey(
            Long teamId, Long terminalActorUserId, RequestOperation terminalAction, UUID actionKey);
    Optional<LoyaltyAwardRequestEntity> findByTeamIdAndTransactionId(Long teamId, UUID transactionId);

    @Query("select r.ruleId from LoyaltyAwardRequestEntity r where r.teamId = :teamId and r.id = :id")
    Optional<UUID> findRuleId(Long teamId, UUID id);

    /** 生成uniqueの全列を絞り、終端履歴を走査せず既存PENDINGだけをsnapshot取得する。 */
    @Query("select r from LoyaltyAwardRequestEntity r where r.teamId = :teamId and r.userId = :userId and r.ruleId = :ruleId and r.awardOn = :awardOn and r.pendingUniqueKey = 1")
    Optional<LoyaltyAwardRequestEntity> findPendingSnapshot(Long teamId, Long userId, UUID ruleId, LocalDate awardOn);

    boolean existsByRuleId(UUID ruleId);
    boolean existsByTeamIdAndVerificationCode(Long teamId, String verificationCode);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from LoyaltyAwardRequestEntity r where r.teamId = :teamId and r.id = :id")
    Optional<LoyaltyAwardRequestEntity> lockExclusive(Long teamId, UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from LoyaltyAwardRequestEntity r where r.teamId = :teamId and r.userId = :userId and r.ruleId = :ruleId and r.awardOn = :awardOn and r.status = com.mannschaft.app.loyalty.RequestStatus.PENDING order by r.id")
    List<LoyaltyAwardRequestEntity> lockPending(Long teamId, Long userId, UUID ruleId, LocalDate awardOn);

    @Query("select r from LoyaltyAwardRequestEntity r where r.userId = :userId order by r.id desc")
    List<LoyaltyAwardRequestEntity> myFirstPage(Long userId, Pageable pageable);

    @Query("select r from LoyaltyAwardRequestEntity r where r.userId = :userId and r.id < :before order by r.id desc")
    List<LoyaltyAwardRequestEntity> myNextPage(Long userId, UUID before, Pageable pageable);

    @Query("select r from LoyaltyAwardRequestEntity r where r.teamId = :teamId order by r.id desc")
    List<LoyaltyAwardRequestEntity> teamFirstPage(Long teamId, Pageable pageable);

    @Query("select r from LoyaltyAwardRequestEntity r where r.teamId = :teamId and r.id < :before order by r.id desc")
    List<LoyaltyAwardRequestEntity> teamNextPage(Long teamId, UUID before, Pageable pageable);

    @Query("select r from LoyaltyAwardRequestEntity r where r.teamId = :teamId and r.status = com.mannschaft.app.loyalty.RequestStatus.PENDING and r.programVersion = :programVersion and r.ruleVersion = :ruleVersion and r.awardOn = :today and r.expiresAt > :now order by r.expiresAt, r.id")
    List<LoyaltyAwardRequestEntity> pendingFirstPage(Long teamId, long programVersion, long ruleVersion, LocalDate today, Instant now, Pageable pageable);

    @Query("select r from LoyaltyAwardRequestEntity r where r.teamId = :teamId and r.status = com.mannschaft.app.loyalty.RequestStatus.PENDING and r.programVersion = :programVersion and r.ruleVersion = :ruleVersion and r.awardOn = :today and r.expiresAt > :now and (r.expiresAt > :afterTime or (r.expiresAt = :afterTime and r.id > :afterId)) order by r.expiresAt, r.id")
    List<LoyaltyAwardRequestEntity> pendingNextPage(Long teamId, long programVersion, long ruleVersion, LocalDate today, Instant now, Instant afterTime, UUID afterId, Pageable pageable);
}
