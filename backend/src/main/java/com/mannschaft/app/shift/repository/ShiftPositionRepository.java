package com.mannschaft.app.shift.repository;

import com.mannschaft.app.shift.entity.ShiftPositionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * シフトポジションリポジトリ。
 */
public interface ShiftPositionRepository extends JpaRepository<ShiftPositionEntity, Long> {

    /**
     * チームのポジション一覧を表示順で取得する。
     */
    List<ShiftPositionEntity> findByTeamIdOrderByDisplayOrderAsc(Long teamId);

    /**
     * チームの有効なポジション一覧を表示順で取得する。
     */
    List<ShiftPositionEntity> findByTeamIdAndIsActiveTrueOrderByDisplayOrderAsc(Long teamId);

    /**
     * チームIDとIDでポジションを取得する。
     */
    Optional<ShiftPositionEntity> findByIdAndTeamId(Long id, Long teamId);

    /**
     * チームIDとポジション名でポジションを検索する（重複チェック用）。
     */
    Optional<ShiftPositionEntity> findByTeamIdAndName(Long teamId, String name);
}
