package com.mannschaft.app.team;

import com.mannschaft.app.team.entity.TeamEntity;

import java.util.UUID;

/** 実Filter復元試練の使い捨てチーム。既存予定fixtureはそのまま再利用する。 */
final class TeamRestoreSecurityFilterTestFixture {
    private TeamRestoreSecurityFilterTestFixture() { }

    static TeamEntity team() {
        return TeamEntity.builder()
                .slug("restore-" + UUID.randomUUID().toString().substring(0, 20))
                .name("実Filter復元契約チーム")
                .template("sports")
                .visibility(TeamEntity.Visibility.PUBLIC)
                .supporterEnabled(false)
                .build();
    }
}