package com.mannschaft.app.schedule.repository;

import com.mannschaft.app.schedule.CrossRefStatus;
import com.mannschaft.app.schedule.CrossRefTargetType;
import com.mannschaft.app.schedule.entity.ScheduleCrossRefEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * スケジュールクロスリファレンスリポジトリ。
 */
public interface ScheduleCrossRefRepository extends JpaRepository<ScheduleCrossRefEntity, Long> {

    /**
     * ソーススケジュールIDで招待一覧を取得する。
     */
    List<ScheduleCrossRefEntity> findBySourceScheduleId(Long sourceScheduleId);

    /**
     * ターゲット種別・ターゲットID・ステータスで招待一覧を取得する。
     */
    List<ScheduleCrossRefEntity> findByTargetTypeAndTargetIdAndStatus(
            CrossRefTargetType type, Long targetId, CrossRefStatus status);

    /**
     * ソーススケジュールID・ターゲット種別・ターゲットIDで招待を取得する。
     */
    Optional<ScheduleCrossRefEntity> findBySourceScheduleIdAndTargetTypeAndTargetId(
            Long sourceId, CrossRefTargetType type, Long targetId);

    /**
     * ソーススケジュールIDと指定ステータスリストで招待数を取得する。
     */
    long countBySourceScheduleIdAndStatusIn(Long sourceId, List<CrossRefStatus> statuses);
}
