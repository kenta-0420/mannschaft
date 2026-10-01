package com.mannschaft.app.organization.teamgroup.service;

import com.mannschaft.app.organization.teamgroup.entity.OrgTeamGroupEntity;

import java.util.UUID;

/**
 * {@link OrgTeamGroupCommandService} が返すチームグループの値表現。
 *
 * <p>Service の public API に Entity を出さない（ServiceApiEntityBoundaryArchTest）ための境界型。</p>
 */
public record OrgTeamGroupView(UUID id, String name, String description, int sortOrder) {

    static OrgTeamGroupView of(OrgTeamGroupEntity e) {
        return new OrgTeamGroupView(e.getId(), e.getName(), e.getDescription(), e.getSortOrder());
    }
}
