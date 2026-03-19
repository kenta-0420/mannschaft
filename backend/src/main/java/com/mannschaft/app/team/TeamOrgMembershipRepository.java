package com.mannschaft.app.team;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * チーム−組織所属リポジトリ。
 */
public interface TeamOrgMembershipRepository extends JpaRepository<TeamOrgMembershipEntity, Long> {

    Optional<TeamOrgMembershipEntity> findByTeamIdAndOrganizationId(Long teamId, Long organizationId);

    List<TeamOrgMembershipEntity> findByOrganizationIdAndStatus(Long organizationId, TeamOrgMembershipEntity.Status status);
}
