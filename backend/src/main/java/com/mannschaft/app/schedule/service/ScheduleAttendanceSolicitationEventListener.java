package com.mannschaft.app.schedule.service;

import com.mannschaft.app.schedule.ScheduledTaskStatus;
import com.mannschaft.app.schedule.ScheduledTaskType;
import com.mannschaft.app.schedule.event.ScheduleCreatedEvent;
import com.mannschaft.app.schedule.repository.ScheduleScheduledTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 出欠募集の即時起動リスナー（機能55 第二陣・RSVP 根治）。
 *
 * <p>これまで {@link ScheduleCreatedEvent} は Google Calendar 同期にしか配線されておらず、
 * {@code attendanceRequired = true} で予定を作っても出欠レコードが生成されず募集通知も飛ばない
 * 半完成状態だった。本リスナーがその配線欠落を根治する。</p>
 *
 * <p>{@link GoogleCalendarEventListener} に倣い {@link TransactionalEventListener}
 * （AFTER_COMMIT）＋ {@link Async} で、予定作成トランザクションのコミット後に非同期実行する。</p>
 *
 * <p><b>二重募集の防止</b>: 当該予定に PENDING の ATTENDANCE 予約タスクが存在する場合は、
 * 募集開始は予約タスク（{@code scheduledAt} 到来時にバッチが materialize）に委ねるため、
 * 即時募集は行わない。予約タスクが無い「即時出欠」のケースのみ本リスナーが募集を開始する。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduleAttendanceSolicitationEventListener {

    private final ScheduleAttendanceService scheduleAttendanceService;
    private final ScheduleScheduledTaskRepository scheduledTaskRepository;

    /**
     * 予定作成イベントを受信し、即時出欠募集を開始する（条件を満たす場合のみ）。
     *
     * @param event 予定作成イベント
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onScheduleCreated(ScheduleCreatedEvent event) {
        if (!event.isAttendanceRequired()) {
            return;
        }

        // 予約 ATTENDANCE タスクがある場合はバッチ（scheduledAt 到来時）に委ねる → 即時募集しない
        boolean hasPendingScheduledAttendance =
                scheduledTaskRepository.existsByScheduleIdAndTaskTypeAndStatusAndDeletedAtIsNull(
                        event.getScheduleId(), ScheduledTaskType.ATTENDANCE, ScheduledTaskStatus.PENDING);
        if (hasPendingScheduledAttendance) {
            log.info("即時出欠募集スキップ（予約タスクあり・バッチに委譲）: scheduleId={}",
                    event.getScheduleId());
            return;
        }

        log.info("即時出欠募集起動: scheduleId={}", event.getScheduleId());
        scheduleAttendanceService.openAttendanceSolicitation(event.getScheduleId());
    }
}
