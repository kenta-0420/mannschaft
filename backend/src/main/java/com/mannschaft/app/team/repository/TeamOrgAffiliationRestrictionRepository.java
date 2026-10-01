package com.mannschaft.app.team.repository;

import com.mannschaft.app.team.entity.TeamOrgAffiliationDirection;
import com.mannschaft.app.team.entity.TeamOrgAffiliationRestrictionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * チーム加盟の再送制限リポジトリ（F01.2.1 §5.1）。
 *
 * <p>{@code AbstractTenantAwareRepository} は継承しない（物理削除・deleted_at 列なし・
 * チーム側から組織を横断して引く読み手があるため。§5.1）。</p>
 */
public interface TeamOrgAffiliationRestrictionRepository
        extends JpaRepository<TeamOrgAffiliationRestrictionEntity, UUID> {

    Optional<TeamOrgAffiliationRestrictionEntity> findByOrganizationIdAndTeamIdAndDirection(
            Long organizationId, Long teamId, TeamOrgAffiliationDirection direction);
}
