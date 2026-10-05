package com.mannschaft.app.team.repository;

import java.util.UUID;

/** チーム 1 件の ACTIVE な加盟（組織 ID と所属グループ ID）の射影。 */
public interface TeamOrgGroupProjection {

    /** 加盟先の組織 ID。 */
    Long getOrganizationId();

    /** 所属チームグループ ID（未分類は null。削除済みグループを指すこともある）。 */
    UUID getGroupId();
}
