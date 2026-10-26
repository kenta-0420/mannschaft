package com.mannschaft.app.corkboard.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * カード一括位置更新リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class BatchPositionRequest {

    @NotEmpty
    @Valid
    private final List<CardPosition> positions;

    /**
     * 個別カードの位置情報。
     */
    @Getter
    @RequiredArgsConstructor
    public static class CardPosition {

        @NotNull
        private final Long cardId;

        @NotNull
        private final Integer positionX;

        @NotNull
        private final Integer positionY;

        @NotNull
        private final Integer zIndex;
    }
}
