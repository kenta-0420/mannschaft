package com.mannschaft.app.schedule.repository;

import com.mannschaft.app.schedule.entity.ScheduleTargetEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** 予定対象者を一括取得・置換するためのリポジトリ。 */
public interface ScheduleTargetRepository extends JpaRepository<ScheduleTargetEntity, UUID> {

    List<ScheduleTargetEntity> findByScheduleIdInOrderByScheduleIdAscUserIdAsc(Collection<Long> scheduleIds);

    List<ScheduleTargetEntity> findByScheduleIdOrderByUserIdAsc(Long scheduleId);

    void deleteByScheduleId(Long scheduleId);

    void deleteByUserId(Long userId);
}
