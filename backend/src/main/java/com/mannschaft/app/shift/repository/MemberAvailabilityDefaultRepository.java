package com.mannschaft.app.shift.repository;

import com.mannschaft.app.shift.entity.MemberAvailabilityDefaultEntity;
import org.springframework.data.jpa.repository.JpaRepository;

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
     * ユーザーとチームのデフォルト勤務可能時間を全削除する。
     */
    void deleteByUserIdAndTeamId(Long userId, Long teamId);
}
