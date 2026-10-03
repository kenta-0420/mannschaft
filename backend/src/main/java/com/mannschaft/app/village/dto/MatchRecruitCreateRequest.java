package com.mannschaft.app.village.dto;

import com.mannschaft.app.village.entity.enums.VillageMatchRecruitCategory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 練習試合・審判募集の作成リクエスト（F17.1 Phase 2 U6）。
 *
 * <p>{@code postedByTeamId} はチーム代表として投稿する場合のみ指定する（FK 張らない・原則1）。
 * 入力値の整合性（時刻順序・締切妥当性）は Service 層で検証する。</p>
 */
public record MatchRecruitCreateRequest(
        @NotNull VillageMatchRecruitCategory category,
        @NotBlank @Size(max = 100) String title,
        String description,
        // F17.1 §5.6: 日付を持たない募集（マネージャー募集・引っ越し手伝い等）を許すため
        // @NotNull を外した（V153 で DDL も NULL 許容に緩和済み）。
        LocalDate matchDate,
        LocalTime matchTimeStart,
        LocalTime matchTimeEnd,
        @Size(max = 200) String venue,
        Integer requiredCount,
        @Size(max = 200) String contactMethod,
        LocalDateTime applicationDeadline,
        Long postedByTeamId
) {
}
