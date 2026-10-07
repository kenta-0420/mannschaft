package com.mannschaft.app.activity.dto;

import java.time.OffsetDateTime;

/** 秒精度で比較する更新対象予定の状態。 */
public record ExpectedScheduleEntry(@jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Positive Long id,
                                    OffsetDateTime updatedAt, String title,
                                    OffsetDateTime startAt, OffsetDateTime endAt,
                                    Boolean allDay, String status) {
}
