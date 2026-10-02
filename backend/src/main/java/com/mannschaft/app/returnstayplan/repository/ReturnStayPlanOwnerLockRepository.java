package com.mannschaft.app.returnstayplan.repository;

import com.mannschaft.app.returnstayplan.entity.ReturnStayPlanOwnerLockEntity;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** F02.11 owner lock Repository の骨格。 */
public interface ReturnStayPlanOwnerLockRepository
        extends JpaRepository<ReturnStayPlanOwnerLockEntity, UUID> {

    Optional<ReturnStayPlanOwnerLockEntity> findByOwnerUserId(Long ownerUserId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from ReturnStayPlanOwnerLockEntity l where l.ownerUserId = :ownerUserId")
    Optional<ReturnStayPlanOwnerLockEntity> findByOwnerUserIdForUpdate(
            @Param("ownerUserId") Long ownerUserId);

    @Modifying
    @Query(value = """
            INSERT IGNORE INTO return_stay_plan_owner_locks
                (id, owner_user_id, created_at, updated_at)
            VALUES (:lockId, :ownerUserId, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
            """, nativeQuery = true)
    int insertIfAbsent(
            @Param("lockId") UUID lockId,
            @Param("ownerUserId") Long ownerUserId);
}
