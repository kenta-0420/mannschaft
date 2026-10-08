package com.mannschaft.app.activity.service;

import com.mannschaft.app.schedule.dto.ScheduleActivitySource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 自動活動の予定表示だけを更新する契約。試練先行の骨格。 */
@Service
public class ActivityPlanningService {
    @Transactional(propagation = Propagation.MANDATORY)
    public void setPlanned(ScheduleActivitySource source, boolean value) { }
}
