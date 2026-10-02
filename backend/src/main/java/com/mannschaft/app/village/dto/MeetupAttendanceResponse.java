package com.mannschaft.app.village.dto;

import com.mannschaft.app.village.entity.VillageMeetupAttendanceEntity;
import com.mannschaft.app.village.entity.enums.VillageMeetupAttendanceStatus;
import lombok.Builder;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * F17.2 Wave1 ②寄合後半戦 — 出欠レスポンス（設計書 §4.4）。
 *
 * <p>{@code displayName} は<strong>村ニックネーム</strong>で解決する（実名スナップショット禁止・§10 G4）。
 * 未設定時は {@code null} 相当のフォールバックをサービス層で解決する。</p>
 */
@Builder
public record MeetupAttendanceResponse(
        UUID id,
        UUID meetupId,
        Long userId,
        String displayName,
        VillageMeetupAttendanceStatus status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static MeetupAttendanceResponse of(VillageMeetupAttendanceEntity entity, String displayName) {
        return MeetupAttendanceResponse.builder()
                .id(entity.getId())
                .meetupId(entity.getMeetupId())
                .userId(entity.getUserId())
                .displayName(displayName)
                .status(entity.getStatus())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
