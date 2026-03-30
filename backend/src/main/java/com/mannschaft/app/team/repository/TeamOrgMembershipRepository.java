package com.mannschaft.app.team.repository;

import com.mannschaft.app.team.entity.TeamOrgMembershipEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;


/**
 * チーム−組織所属リポジトリ。
 */
public interface TeamOrgMembershipRepository extends JpaRepository<TeamOrgMembershipEntity, Long> {

    Optional<TeamOrgMembershipEntity> findByTeamIdAndOrganizationId(Long teamId, Long organizationId);

    List<TeamOrgMembershipEntity> findByOrganizationIdAndStatus(Long organizationId, TeamOrgMembershipEntity.Status status);

    /**
     * チームが所属するACTIVE状態の組織を取得する（通常1件）。
     */
    Optional<TeamOrgMembershipEntity> findFirstByTeamIdAndStatus(Long teamId, TeamOrgMembershipEntity.Status status);

    /**
     * チームが所属する全組織を取得する。
     */
    List<TeamOrgMembershipEntity> findByTeamIdAndStatus(Long teamId, TeamOrgMembershipEntity.Status status);
}
