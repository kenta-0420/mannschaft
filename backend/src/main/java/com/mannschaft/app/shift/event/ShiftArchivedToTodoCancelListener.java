package com.mannschaft.app.shift.event;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
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

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.DROP_WHEN_DISABLED,
            gateKeys = "FEATURE_SHIFT_ENABLED",
            reason = "対になる ShiftToTaskListener とアーカイブバッチも同じキーで止まるため、取り消すべきタスクがそもそも生成されずアーカイブイベント自体も発火しない")
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
