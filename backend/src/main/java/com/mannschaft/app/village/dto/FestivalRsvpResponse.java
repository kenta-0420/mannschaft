package com.mannschaft.app.village.dto;

import com.mannschaft.app.village.entity.VillageFestivalRsvpEntity;
import com.mannschaft.app.village.entity.enums.VillageFestivalRsvpStatus;
import lombok.Builder;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * F17.2 Wave2 ③お祭りの参加表明（RSVP）レスポンス（設計書 §5.6）。
 *
 * <p>{@code displayName} は<strong>村ニックネーム</strong>で解決する（実名スナップショット禁止・§10 G4）。</p>
 */
@Builder
public record FestivalRsvpResponse(
        UUID id,
        UUID festivalId,
        Long userId,
        String displayName,
        VillageFestivalRsvpStatus status,
        String roleLabel,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static FestivalRsvpResponse of(VillageFestivalRsvpEntity entity, String displayName) {
        return FestivalRsvpResponse.builder()
                .id(entity.getId())
                .festivalId(entity.getFestivalId())
                .userId(entity.getUserId())
                .displayName(displayName)
                .status(entity.getStatus())
                .roleLabel(entity.getRoleLabel())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
