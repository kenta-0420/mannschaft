package com.mannschaft.app.village.dto;

import com.mannschaft.app.village.entity.VillageMeetupCandidateDateEntity;
import lombok.Builder;

import java.time.LocalDate;
import java.util.UUID;

/**
 * F17.1 Phase 3-β — 寄合候補日レスポンス。
 */
@Builder
public record MeetupCandidateDateResponse(
        UUID id,
        UUID meetupId,
        LocalDate candidateDate,
        Integer sortOrder) {

    public static MeetupCandidateDateResponse of(VillageMeetupCandidateDateEntity entity) {
        return MeetupCandidateDateResponse.builder()
                .id(entity.getId())
                .meetupId(entity.getMeetupId())
                .candidateDate(entity.getCandidateDate())
                .sortOrder(entity.getSortOrder())
                .build();
    }
}
