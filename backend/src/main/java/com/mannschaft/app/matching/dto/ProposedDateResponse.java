package com.mannschaft.app.matching.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 日程候補レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class ProposedDateResponse {

    private final Long id;
    private final LocalDate proposedDate;
    private final LocalTime proposedTimeFrom;
    private final LocalTime proposedTimeTo;
    private final Boolean isSelected;
}
