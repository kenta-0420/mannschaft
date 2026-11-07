package com.mannschaft.app.schedule.service;

import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.event.ScheduleActivityCreationEvent;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/** 新規insert専用。更新・削除のsaveを生成入口にせず、同domain内だけでEntityを扱う。 */
@Component
@RequiredArgsConstructor
public class ScheduleCreationWriter {
    private final ScheduleRepository schedules;
    private final ApplicationEventPublisher events;

    ScheduleEntity saveNew(ScheduleEntity schedule) {
        if (schedule.getId() != null) throw new IllegalArgumentException("新規予定だけを保存する");
        ScheduleEntity saved = schedules.save(schedule);
        if ((!saved.isTeamScope() && !saved.isOrganizationScope()) || saved.isRecurring()) return saved;
        events.publishEvent(new ScheduleActivityCreationEvent(saved.getId(), saved.getCreatedBy()));
        return saved;
    }
}
