package com.mannschaft.app.returnstayplan.repository;

import com.mannschaft.app.returnstayplan.entity.ReturnStayPlanEntity;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** F02.11 予定 Repository の骨格。認可付き検索は Service 実装時に追加する。 */
public interface ReturnStayPlanRepository extends JpaRepository<ReturnStayPlanEntity, UUID> {

    Optional<ReturnStayPlanEntity> findByIdAndOwnerUserId(UUID id, Long ownerUserId);

    long countByOwnerUserIdAndEndDateGreaterThanEqual(Long ownerUserId, LocalDate today);

    long countByOwnerUserId(Long ownerUserId);
}
