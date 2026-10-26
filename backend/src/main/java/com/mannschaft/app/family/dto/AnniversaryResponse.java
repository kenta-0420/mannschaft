package com.mannschaft.app.family.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 記念日レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class AnniversaryResponse {

    private final Long id;
    private final Long teamId;
    private final String name;
    private final LocalDate date;
    private final boolean repeatAnnually;
    private final int notifyDaysBefore;
    private final Long createdBy;
    private final LocalDateTime createdAt;
}
