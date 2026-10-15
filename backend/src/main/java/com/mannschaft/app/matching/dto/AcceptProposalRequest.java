package com.mannschaft.app.matching.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 応募承諾リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class AcceptProposalRequest {

    private final String scheduleTitle;
    private final LocalDate confirmedDate;
    private final LocalTime confirmedTimeFrom;
    private final LocalTime confirmedTimeTo;
    private final String confirmedVenue;
}
