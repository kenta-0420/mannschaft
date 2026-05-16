package com.mannschaft.app.village.dto;

import com.mannschaft.app.village.entity.enums.VillageMatchRecruitCategory;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 練習試合・審判募集の更新リクエスト（F17.1 Phase 2 U6）。
 *
 * <p>全フィールド optional。{@code null} のフィールドは更新しない（PATCH 的セマンティクス）。
 * ただし {@code title} に空文字を指定した場合は VILLAGE_029 で弾く。</p>
 */
public record MatchRecruitUpdateRequest(
        VillageMatchRecruitCategory category,
        @Size(max = 100) String title,
        String description,
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
