package com.mannschaft.app.shift.event;

import com.mannschaft.app.shift.service.ShiftToTaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * シフト公開 Todo 自動作成リスナー。F03.5 Phase 4-β。
 * ShiftPublishedEvent を購読し、各スロット割り当てユーザーの Todo を非同期で作成する。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShiftToTaskListener {

    private final ShiftToTaskService shiftToTaskService;

    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onShiftPublished(ShiftPublishedEvent event) {
        try {
            shiftToTaskService.createTodosForSchedule(
                    event.getScheduleId(), event.getTeamId(), event.getTriggeredByUserId());
        } catch (Exception e) {
            log.error("シフト公開 Todo 自動作成失敗: scheduleId={}", event.getScheduleId(), e);
        }
    }
}
