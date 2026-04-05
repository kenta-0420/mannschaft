package com.mannschaft.app.tournament.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * 昇降格実行リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CreatePromotionRequest {

    @NotNull
    private final List<PromotionEntry> entries;

    @Getter
    @RequiredArgsConstructor
    public static class PromotionEntry {

        @NotNull
        private final Long teamId;

        @NotNull
        private final Long fromDivisionId;

        @NotNull
        private final Long toDivisionId;

        @NotNull
        private final String type;

        @NotNull
        private final Integer finalRank;

        @Size(max = 200)
        private final String reason;
    }
}
