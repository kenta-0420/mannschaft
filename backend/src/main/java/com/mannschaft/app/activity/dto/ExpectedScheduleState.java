package com.mannschaft.app.activity.dto;

import java.time.LocalDateTime;
import java.util.List;

/** 選択予定と繰返し更新対象すべての状態を確認後の競合検出に用いる。 */
public record ExpectedScheduleState(LocalDateTime updatedAt, String title, LocalDateTime startAt,
                                    LocalDateTime endAt, Boolean allDay, String status,
                                    @jakarta.validation.Valid List<@jakarta.validation.constraints.NotNull ExpectedScheduleEntry> schedules) {
}
