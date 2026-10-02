package com.mannschaft.app.schedule.repository;

import com.mannschaft.app.schedule.entity.ScheduleTargetEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** 予定対象者を一括取得・置換するためのリポジトリ。 */
public interface ScheduleTargetRepository extends JpaRepository<ScheduleTargetEntity, UUID> {

    List<ScheduleTargetEntity> findByScheduleIdInOrderByScheduleIdAscUserIdAsc(Collection<Long> scheduleIds);

    List<ScheduleTargetEntity> findByScheduleIdOrderByUserIdAsc(Long scheduleId);

    /**
     * 対象者置換の前に即時削除し、同じ(scheduleId,userId)の再登録との一意制約衝突を防ぐ。
     * derived deleteはHibernateのinsert-before-delete順序に従うため使用しない。
     * 親予定は同一トランザクションで更新中なので、永続化コンテキストはclearしない。
     */
    @Modifying
    @Query("DELETE FROM ScheduleTargetEntity t WHERE t.scheduleId = :scheduleId")
    void deleteByScheduleId(@Param("scheduleId") Long scheduleId);

    void deleteByUserId(Long userId);
}
