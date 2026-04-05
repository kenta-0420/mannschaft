package com.mannschaft.app.chart.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * カルテ一覧用サマリーレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class ChartRecordSummaryResponse {

    private final Long id;
    private final Long teamId;
    private final Long customerUserId;
    private final String customerDisplayName;
    private final Long staffUserId;
    private final String staffDisplayName;
    private final LocalDate visitDate;
    private final String chiefComplaint;
    private final Boolean isSharedToCustomer;
    private final Boolean isPinned;
    private final Boolean hasAllergyInfo;
    private final Integer photoCount;
    private final LocalDateTime createdAt;
}
