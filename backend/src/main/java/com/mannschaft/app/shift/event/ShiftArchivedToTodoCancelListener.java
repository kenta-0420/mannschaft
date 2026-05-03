package com.mannschaft.app.shift.event;

import com.mannschaft.app.shift.service.ShiftArchivedTodoCancelService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * シフト ARCHIVED 時 Todo 自動キャンセルリスナー。F03.5 Phase 4-γ。
 * ShiftArchivedEvent を購読し、紐づく自動作成 Todo を CANCELLED へ遷移させる。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShiftArchivedToTodoCancelListener {

    private final ShiftArchivedTodoCancelService cancelService;

    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onShiftArchived(ShiftArchivedEvent event) {
        try {
            int count = cancelService.cancelShiftLinkedTodos(event.getScheduleId());
            log.info("ShiftArchivedEvent 処理完了: scheduleId={}, cancelled={}",
                    event.getScheduleId(), count);
        } catch (Exception e) {
            log.error("ShiftArchivedEvent 処理失敗: scheduleId={}", event.getScheduleId(), e);
        }
    }
}
