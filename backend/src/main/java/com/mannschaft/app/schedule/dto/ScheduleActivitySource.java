package com.mannschaft.app.schedule.dto;

import java.time.LocalDateTime;
import java.util.List;

/** 活動ドメインへ渡す予定の値。クロスドメインEntity参照を避ける。 */
public record ScheduleActivitySource(Long id, String scopeType, Long scopeId,
                                      String title, LocalDateTime startAt, LocalDateTime endAt,
                                      Boolean allDay, String status, LocalDateTime updatedAt,
                                      List<Long> attendingUserIds) {
}
