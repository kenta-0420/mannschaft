package com.mannschaft.app.team.repository;

import java.util.UUID;

/**
 * 「チームグループ ID → ACTIVE な加盟数」軽量射影（F01.2.1 4-A のグループ一覧の件数集計用）。
 */
public interface GroupTeamCountProjection {

    /** チームグループ ID。 */
    UUID getGroupId();

    /** 当該グループに割り当てられた ACTIVE な加盟数。 */
    long getTeamCount();
}
