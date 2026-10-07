package com.mannschaft.app.activity.dto;

import java.time.LocalDateTime;

/** 秒精度で比較する更新対象予定の状態。 */
public record ExpectedScheduleEntry(@jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Positive Long id,
                                    LocalDateTime updatedAt, String title,
                                    LocalDateTime startAt, LocalDateTime endAt,
                                    Boolean allDay, String status) {
}
