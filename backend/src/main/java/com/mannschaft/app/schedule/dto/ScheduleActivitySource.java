package com.mannschaft.app.schedule.dto;

import java.time.OffsetDateTime;
import java.util.List;

/** 活動ドメインへ渡す予定の値。クロスドメインEntity参照を避ける。 */
public record ScheduleActivitySource(Long id, String scopeType, Long scopeId,
                                      String title, OffsetDateTime startAt, OffsetDateTime endAt,
                                      Boolean allDay, String status, OffsetDateTime updatedAt,
                                      List<Long> attendingUserIds) {
}
