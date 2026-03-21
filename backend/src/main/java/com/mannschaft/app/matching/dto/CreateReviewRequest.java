package com.mannschaft.app.matching.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * レビュー作成リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CreateReviewRequest {

    @NotNull
    private final Long proposalId;

    @NotNull
    @Min(1)
    @Max(5)
    private final Short rating;

    @Size(max = 1000)
    private final String comment;

    private final Boolean isPublic;
}
