package com.mannschaft.app.organization.repository;

/**
 * 組織階層を段階的に展開するための親ID射影。
 */
public interface OrganizationParentIdProjection {

    Long getOrganizationId();

    Long getParentOrganizationId();
}
