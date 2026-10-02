package com.mannschaft.app.notification.fanout;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/**
 * fan-out 宛先集合の宛先チームリポジトリ（F01.2.1 §5.6）。
 */
public interface NotificationFanoutAudienceTeamRepository
        extends JpaRepository<NotificationFanoutAudienceTeamEntity, UUID> {

    /** 宛先集合に含まれるチーム ID を返す（0 件なら空）。 */
    @Query("SELECT t.teamId FROM NotificationFanoutAudienceTeamEntity t "
            + "WHERE t.audienceSnapshotId = :audienceSnapshotId")
    List<Long> findTeamIdsByAudienceSnapshotId(@Param("audienceSnapshotId") UUID audienceSnapshotId);
}
