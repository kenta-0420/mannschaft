package com.mannschaft.app.memberinfo;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TeamMemberInfoFieldRepository extends JpaRepository<TeamMemberInfoFieldEntity, Long> {

    List<TeamMemberInfoFieldEntity> findByTeamIdAndIsActiveTrueOrderBySortOrderAsc(Long teamId);

    long countByTeamId(Long teamId);

    Optional<TeamMemberInfoFieldEntity> findByIdAndTeamId(Long id, Long teamId);
}
