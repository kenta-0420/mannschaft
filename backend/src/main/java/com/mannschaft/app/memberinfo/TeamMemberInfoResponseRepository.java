package com.mannschaft.app.memberinfo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface TeamMemberInfoResponseRepository extends JpaRepository<TeamMemberInfoResponseEntity, Long> {

    List<TeamMemberInfoResponseEntity> findByTeamIdAndUserId(Long teamId, Long userId);

    List<TeamMemberInfoResponseEntity> findByTeamId(Long teamId);

    Optional<TeamMemberInfoResponseEntity> findByUserIdAndFieldId(Long userId, Long fieldId);

    List<TeamMemberInfoResponseEntity> findByFieldIdIn(List<Long> fieldIds);

    @Modifying
    @Transactional
    void deleteByUserId(Long userId);
}
