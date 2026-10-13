package com.mannschaft.app.shift.repository;

import com.mannschaft.app.shift.entity.MemberAvailabilityDefaultEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * メンバーデフォルト勤務可能時間リポジトリ。
 */
public interface MemberAvailabilityDefaultRepository extends JpaRepository<MemberAvailabilityDefaultEntity, Long> {

    /**
     * ユーザーとチームのデフォルト勤務可能時間を曜日順で取得する。
     */
    List<MemberAvailabilityDefaultEntity> findByUserIdAndTeamIdOrderByDayOfWeekAscStartTimeAsc(
            Long userId, Long teamId);

    /**
     * ユーザーとチームのデフォルト勤務可能時間を<b>現在読み取り＋排他ロック</b>（{@code SELECT ... FOR UPDATE}）で取得する
     * （CMP-261010-1432 欠陥 H）。
     *
     * <p>一意キー {@code uq_mad_user_team_dow_time(user_id, team_id, ...)} の左端 2 列での範囲走査になるため、
     * InnoDB（REPEATABLE-READ）は当該 (user_id, team_id) の範囲だけにレコードロック＋ギャップロックを張る。
     * これにより同一ユーザー×同一チームの一括保存（全削除→再作成）が直列化される。
     * 同型の前例は {@code UserCalendarLayerSettingRepository#countByUserIdForUpdate}。</p>
     *
     * <p>FOR UPDATE は最新のコミット済み行を読む（スナップショット読みではない）ため、先着がコミットした行を
     * 後着が確実に見て削除できる。</p>
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM MemberAvailabilityDefaultEntity m WHERE m.userId = :userId AND m.teamId = :teamId")
    List<MemberAvailabilityDefaultEntity> findAllForUpdateByUserIdAndTeamId(
            @Param("userId") Long userId, @Param("teamId") Long teamId);

    /**
     * ユーザーとチームのデフォルト勤務可能時間を全削除する。
     */
    void deleteByUserIdAndTeamId(Long userId, Long teamId);
}
