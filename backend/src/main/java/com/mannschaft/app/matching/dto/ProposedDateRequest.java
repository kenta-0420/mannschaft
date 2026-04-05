package com.mannschaft.app.matching.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 日程候補リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class ProposedDateRequest {

    @NotNull
    private final LocalDate date;

    private final LocalTime timeFrom;

    private final LocalTime timeTo;
}
