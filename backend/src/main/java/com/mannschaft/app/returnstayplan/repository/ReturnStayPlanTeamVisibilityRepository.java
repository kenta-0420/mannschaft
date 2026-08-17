package com.mannschaft.app.returnstayplan.repository;

import com.mannschaft.app.returnstayplan.entity.ReturnStayPlanTeamVisibilityEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** F02.11 TEAM 公開先 Repository の骨格。 */
public interface ReturnStayPlanTeamVisibilityRepository
        extends JpaRepository<ReturnStayPlanTeamVisibilityEntity, UUID> {

    List<ReturnStayPlanTeamVisibilityEntity> findByPlanId(UUID planId);

    List<ReturnStayPlanTeamVisibilityEntity> findByTeamId(Long teamId);
    void deleteByPlanId(UUID planId);
}
