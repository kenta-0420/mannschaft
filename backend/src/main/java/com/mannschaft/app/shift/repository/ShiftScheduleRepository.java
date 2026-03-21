package com.mannschaft.app.shift.repository;

import com.mannschaft.app.shift.ShiftScheduleStatus;
import com.mannschaft.app.shift.entity.ShiftScheduleEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * シフトスケジュールリポジトリ。
 */
public interface ShiftScheduleRepository extends JpaRepository<ShiftScheduleEntity, Long> {

    /**
     * チームのシフトスケジュール一覧を開始日降順で取得する。
     */
    List<ShiftScheduleEntity> findByTeamIdOrderByStartDateDesc(Long teamId);

    /**
     * チームとステータスでシフトスケジュールを取得する。
     */
    List<ShiftScheduleEntity> findByTeamIdAndStatus(Long teamId, ShiftScheduleStatus status);

    /**
     * チームIDとIDでシフトスケジュールを取得する。
     */
    Optional<ShiftScheduleEntity> findByIdAndTeamId(Long id, Long teamId);

    /**
     * チームの期間指定でシフトスケジュールを取得する。
     */
    List<ShiftScheduleEntity> findByTeamIdAndStartDateBetweenOrderByStartDateDesc(
            Long teamId, LocalDate from, LocalDate to);

    /**
     * 特定ステータスのスケジュール一覧を取得する（自動遷移用）。
     */
    List<ShiftScheduleEntity> findByStatus(ShiftScheduleStatus status);
}
