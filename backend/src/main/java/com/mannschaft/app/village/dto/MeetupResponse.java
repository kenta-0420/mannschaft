package com.mannschaft.app.village.dto;

import com.mannschaft.app.village.entity.VillageMeetupEntity;
import com.mannschaft.app.village.entity.enums.VillageMeetupStatus;
import lombok.Builder;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * F17.1 Phase 3-β — 寄合レスポンス。
 *
 * <p>{@code candidateDates} はネストされた候補日一覧。詳細取得時のみ詰める。
 * 一覧取得時は省略可（null）。</p>
 */
@Builder
public record MeetupResponse(
        UUID id,
        UUID villageId,
        String title,
        String description,
        Long organizerUserId,
        VillageMeetupStatus status,
        LocalDate confirmedDate,
        LocalTime confirmedTime,
        String location,
        String decisionsNote,
        LocalDateTime createdAt,
        List<MeetupCandidateDateResponse> candidateDates) {

    public static MeetupResponse of(VillageMeetupEntity entity, List<MeetupCandidateDateResponse> candidateDates) {
        return MeetupResponse.builder()
                .id(entity.getId())
                .villageId(entity.getVillageId())
                .title(entity.getTitle())
                .description(entity.getDescription())
                .organizerUserId(entity.getOrganizerUserId())
                .status(entity.getStatus())
                .confirmedDate(entity.getConfirmedDate())
                .confirmedTime(entity.getConfirmedTime())
                .location(entity.getLocation())
                .decisionsNote(entity.getDecisionsNote())
                .createdAt(entity.getCreatedAt())
                .candidateDates(candidateDates)
                .build();
    }
}
