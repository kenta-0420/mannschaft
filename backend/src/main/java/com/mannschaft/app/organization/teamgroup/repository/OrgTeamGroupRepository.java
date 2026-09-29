package com.mannschaft.app.organization.teamgroup.repository;

import com.mannschaft.app.common.repository.AbstractTenantAwareRepository;
import com.mannschaft.app.organization.teamgroup.entity.OrgTeamGroupEntity;

import java.util.UUID;

/**
 * チームグループのリポジトリ（F01.2.1 §5.1）。{@code organization_id} をテナントキーとする。
 */
public interface OrgTeamGroupRepository
        extends AbstractTenantAwareRepository<OrgTeamGroupEntity, UUID> {
}
