package com.mannschaft.app.schedule.event;

/** 新しく保存された具体予定だけを、同じTX内で活動生成へ渡す値。通知eventとは独立する。 */
public record ScheduleActivityCreationEvent(Long scheduleId, Long createdBy) { }
