package com.mannschaft.app.chart.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * 問診票レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class IntakeFormResponse {

    private final Long id;
    private final Long chartRecordId;
    private final String formType;
    private final String content;
    private final Long electronicSealId;
    private final LocalDateTime signedAt;
    private final Boolean isInitial;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
