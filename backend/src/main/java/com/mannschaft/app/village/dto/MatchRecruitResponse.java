package com.mannschaft.app.village.dto;

import com.mannschaft.app.village.entity.VillageMatchRecruitEntity;
import com.mannschaft.app.village.entity.enums.VillageMatchRecruitCategory;
import com.mannschaft.app.village.entity.enums.VillageMatchRecruitStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;

/**
 * 練習試合・審判募集のレスポンス（F17.1 Phase 2 U6）。
 *
 * <p>表示名（投稿者ニックネーム・チーム名）は Service 層で解決する（クロスドメイン読取のみ・原則1 遵守）。
 * チーム未指定投稿の場合 {@code postedByTeamId}/{@code postedByTeamName} は {@code null}。</p>
 */
public record MatchRecruitResponse(
        UUID id,
        UUID villageId,
        Long postedByUserId,
        String postedByDisplayName,
        Long postedByTeamId,
        String postedByTeamName,
        VillageMatchRecruitCategory category,
        String title,
        String description,
        LocalDate matchDate,
        LocalTime matchTimeStart,
        LocalTime matchTimeEnd,
        String venue,
        Integer requiredCount,
        String contactMethod,
        LocalDateTime applicationDeadline,
        VillageMatchRecruitStatus status,
        LocalDateTime createdAt
) {

    public static MatchRecruitResponse of(VillageMatchRecruitEntity e,
                                          String postedByDisplayName,
                                          String postedByTeamName) {
        return new MatchRecruitResponse(
                e.getId(),
                e.getVillageId(),
                e.getPostedByUserId(),
                postedByDisplayName,
                e.getPostedByTeamId(),
                postedByTeamName,
                e.getCategory(),
                e.getTitle(),
                e.getDescription(),
                e.getMatchDate(),
                e.getMatchTimeStart(),
                e.getMatchTimeEnd(),
                e.getVenue(),
                e.getRequiredCount(),
                e.getContactMethod(),
                e.getApplicationDeadline(),
                e.getStatus(),
                e.getCreatedAt()
        );
    }
}
