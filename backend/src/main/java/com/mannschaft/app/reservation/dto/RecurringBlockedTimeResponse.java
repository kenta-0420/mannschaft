package com.mannschaft.app.reservation.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;

/**
 * 定期予約不可枠 レスポンスDTO（F03.4.5 §4.6）。
 */
@Builder(toBuilder = true)
@Getter
public class RecurringBlockedTimeResponse {

    UUID id;
    Long teamId;
    Long lineId;
    /** 対象ライン名（NameResolver 不要・ライン一括取得で解決。チーム全体は null）。 */
    String lineName;
    String dayOfWeek;
    LocalTime startTime;
    LocalTime endTime;
    String reason;
    Boolean isPublic;
    Boolean isActive;
    LocalDateTime createdAt;
    LocalDateTime updatedAt;
}
