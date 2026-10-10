package com.mannschaft.app.social.announcement;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/**
 * グループ宛てお知らせの送信時スナップショットリポジトリ（F01.2.1 §5.6）。
 *
 * <p>{@code announcement_feed_group_snapshots} へのアクセス経路。書き込み・判定ロジックは部隊 6-A 以降。</p>
 */
public interface AnnouncementFeedGroupSnapshotRepository
        extends JpaRepository<AnnouncementFeedGroupSnapshotEntity, UUID> {

    /** お知らせ 1 件の送信時の対象（グループ×チーム）を取得する。 */
    List<AnnouncementFeedGroupSnapshotEntity> findByFeedId(Long feedId);

    /** 指定チームがそのお知らせの送信時の対象に含まれていたか。 */
    boolean existsByFeedIdAndTeamId(Long feedId, Long teamId);

    /** お知らせ 1 件のスナップショットを消す（同じ告知の再登録で置き換えるため。部隊 6-A）。 */
    @Modifying
    @Query("DELETE FROM AnnouncementFeedGroupSnapshotEntity s WHERE s.feedId = :feedId")
    int deleteByFeedId(@Param("feedId") Long feedId);
}
