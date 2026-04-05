package com.mannschaft.app.tournament.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;

/**
 * 個人成績入力リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class PlayerStatRequest {

    @NotNull
    private final Long userId;

    @NotNull
    private final Long participantId;

    @NotBlank
    private final String statKey;

    private final Integer valueInt;
    private final BigDecimal valueDecimal;
    private final String valueTime;
}
