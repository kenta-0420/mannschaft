package com.mannschaft.app.village.dto;

import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 村参加申請の作成リクエスト（F17.1 Phase 1 B6 / 設計書 §4.4.4）。
 *
 * <p>USER の場合は操作者本人のみ、TEAM/ORG は当該チーム/組織の ADMIN/DEPUTY_ADMIN が
 * Service 層で検証される。</p>
 *
 * @param subjectType 申請主体（USER / TEAM / ORGANIZATION）
 * @param subjectId   申請主体ID
 * @param message     志望動機（任意・500字以内）
 */
public record JoinRequestCreateRequest(
        @NotNull
        VillageSubjectType subjectType,

        @NotNull
        @Positive
        Long subjectId,

        @Size(max = 500)
        String message
) {
}
