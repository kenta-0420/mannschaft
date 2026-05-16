package com.mannschaft.app.village.dto;

import com.mannschaft.app.village.entity.VillageMatchRecruitApplicationEntity;
import com.mannschaft.app.village.entity.enums.VillageMatchApplicationStatus;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 練習試合募集への応募レスポンス（F17.1 Phase 2 U6）。
 *
 * <p>応募者の表示名（村ニックネーム）・チーム名は Service 層で解決する（クロスドメイン読取のみ・原則1 遵守）。
 * チーム未指定応募の場合 {@code applicantTeamId}/{@code applicantTeamName} は {@code null}。</p>
 */
public record MatchApplicationResponse(
        UUID id,
        UUID recruitId,
        Long applicantUserId,
        String applicantDisplayName,
        Long applicantTeamId,
        String applicantTeamName,
        String message,
        VillageMatchApplicationStatus status,
        Long reviewedByUserId,
        LocalDateTime reviewedAt,
        String reviewComment,
        LocalDateTime createdAt
) {

    public static MatchApplicationResponse of(VillageMatchRecruitApplicationEntity e,
                                              String applicantDisplayName,
                                              String applicantTeamName) {
        return new MatchApplicationResponse(
                e.getId(),
                e.getRecruitId(),
                e.getApplicantUserId(),
                applicantDisplayName,
                e.getApplicantTeamId(),
                applicantTeamName,
                e.getMessage(),
                e.getStatus(),
                e.getReviewedByUserId(),
                e.getReviewedAt(),
                e.getReviewComment(),
                e.getCreatedAt()
        );
    }
}
