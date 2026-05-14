package com.mannschaft.app.village.dto;

import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 村参加リクエスト（F17.1 Phase 1 B3）。
 *
 * @param subjectType 参加主体種別（USER / TEAM / ORGANIZATION）
 * @param subjectId   参加主体ID（USER の場合はリクエストユーザー本人のみ可）
 */
public record MembershipJoinRequest(
        @NotNull VillageSubjectType subjectType,
        @NotNull @Positive Long subjectId
) {
}
