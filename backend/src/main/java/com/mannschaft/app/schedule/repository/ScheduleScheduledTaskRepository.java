package com.mannschaft.app.schedule.repository;

import com.mannschaft.app.common.repository.AbstractTenantAwareRepository;
import com.mannschaft.app.schedule.ScheduledTaskStatus;
import com.mannschaft.app.schedule.entity.ScheduleScheduledTaskEntity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 予約タスク（機能55）リポジトリ。
 *
 * <p>{@link AbstractTenantAwareRepository} を継承し organization_id スコープの絞り込みを統一する（原則7）。
 * 主キーは UUIDv7（{@link UUID}）。</p>
 */
public interface ScheduleScheduledTaskRepository
        extends AbstractTenantAwareRepository<ScheduleScheduledTaskEntity, UUID> {

    /**
     * 指定状態かつ scheduled_at が指定時刻より前（materialize 対象）の予約タスクを取得する。
     * 後続バッチの materialize ポーリングで使用する。
     *
     * @param status 対象状態（通常 {@link ScheduledTaskStatus#PENDING}）
     * @param now    判定基準時刻
     * @return materialize 対象の予約タスク一覧
     */
    List<ScheduleScheduledTaskEntity> findByStatusAndScheduledAtBeforeAndDeletedAtIsNull(
            ScheduledTaskStatus status, LocalDateTime now);

    /**
     * 親予定に紐づく予約タスクを取得する（論理削除を除く）。
     *
     * @param scheduleId 親予定 schedules.id
     * @return 当該予定の予約タスク一覧
     */
    List<ScheduleScheduledTaskEntity> findByScheduleIdAndDeletedAtIsNull(Long scheduleId);
}
