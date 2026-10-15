package com.mannschaft.app.tournament.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 個人成績項目定義リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class StatDefRequest {

    @NotBlank @Size(max = 50)
    private final String name;

    @NotBlank @Size(max = 30)
    private final String statKey;

    @Size(max = 20)
    private final String unit;

    private final String dataType;
    private final String aggregationType;
    private final Boolean isRankingTarget;

    @Size(max = 50)
    private final String rankingLabel;

    private final Integer sortOrder;
}
