package com.mannschaft.app.repairplan.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/**
 * 理事任期作成リクエスト（F08.8 Phase 5）。
 */
public record CreateTermRequest(

        /** 対象ユーザー ID（必須、users.id への ID 参照）。 */
        @NotNull
        Long userId,

        /** 任期開始日（必須）。 */
        @NotNull
        LocalDate termStart,

        /** 任期終了日（必須）。 */
        @NotNull
        LocalDate termEnd,

        /** 役職名（例: "理事長"、"理事" 等）。 */
        String roleName
) {
}
