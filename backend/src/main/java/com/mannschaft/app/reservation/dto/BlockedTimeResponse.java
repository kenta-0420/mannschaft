package com.mannschaft.app.reservation.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * ブロック時間（予約不可枠）レスポンスDTO。
 *
 * <p>機能B: {@code resource}（対象軸・{@code resourceType} / {@code resourceId} /
 * STAFF 時の担当スタッフ表示名 {@code resourceName}）を追加。</p>
 */
@Builder(toBuilder = true)
@Getter
public class BlockedTimeResponse {

    Long id;
    Long teamId;
    TimeSlotDto timeSlot;
    ResourceDto resource;
    BlockedAuditDto audit;
    Boolean endsNextDay;

    public record TimeSlotDto(LocalDate blockedDate, LocalTime startTime, LocalTime endTime) {}

    /**
     * 対象軸情報（機能B）。
     *
     * @param resourceType {@code TEAM} / {@code STAFF}
     * @param resourceId   STAFF 時のスタッフ user_id（TEAM 時は null）
     * @param resourceName STAFF 時の担当スタッフ表示名（{@code NameResolverService} で一括解決・TEAM 時は null）
     */
    public record ResourceDto(String resourceType, Long resourceId, String resourceName) {}

    public record BlockedAuditDto(String reason, Long createdBy, LocalDateTime createdAt, LocalDateTime updatedAt) {}
}
