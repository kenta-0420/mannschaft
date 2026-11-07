package com.mannschaft.app.team.repository;

import java.util.UUID;

/**
 * 「ACTIVE な加盟チーム ID → 所属チームグループ ID」軽量射影（F01.2.1 6-A のグループ宛て展開用）。
 */
public interface TeamGroupAssignmentProjection {

    /** チーム ID。 */
    Long getTeamId();

    /** 所属チームグループ ID（未分類は null。削除済みグループを指すこともある）。 */
    UUID getGroupId();
}
