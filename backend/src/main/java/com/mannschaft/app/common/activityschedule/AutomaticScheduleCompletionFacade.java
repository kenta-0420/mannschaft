package com.mannschaft.app.common.activityschedule;

import com.mannschaft.app.activity.service.ActivityPlanningService;
import com.mannschaft.app.schedule.service.ScheduleCompletionService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.Clock;
import java.util.List;

/** 予定状態と活動予定表示の原子性に必要な操作だけを共通層のTXで段取りする。 */
@Service
public class AutomaticScheduleCompletionFacade {
    private final ScheduleCompletionService schedules;
    private final ActivityPlanningService activities;
    private final Clock wallClock;

    public AutomaticScheduleCompletionFacade(ScheduleCompletionService schedules, ActivityPlanningService activities,
                                              @Qualifier("wallClock") Clock wallClock) {
        this.schedules = schedules;
        this.activities = activities;
        this.wallClock = wallClock;
    }

    /** 非TXのbatchから行ごとに呼び、失敗の影響をこの行の短TXへ限定する。 */
    @Transactional
    public boolean completeOne(Long id, OffsetDateTime now) {
        var completed = schedules.completeIfDue(id, now);
        completed.ifPresent(source -> activities.setPlanned(source, false));
        return completed.isPresent();
    }

    /** 既存予定更新TXへ参加する。別TXで自分の予定lockを待つ構成にはしない。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reopenFuture(List<Long> ids) {
        reopenFuture(ids, OffsetDateTime.now(wallClock));
    }

    /** 固定時計の境界試験とbatch共通cutoffを受け取る入口。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reopenFuture(List<Long> ids, OffsetDateTime now) {
        for (Long id : ids.stream().distinct().sorted().toList()) {
            schedules.reopenIfFuture(id, now).ifPresent(source -> activities.setPlanned(source, true));
        }
    }
}
