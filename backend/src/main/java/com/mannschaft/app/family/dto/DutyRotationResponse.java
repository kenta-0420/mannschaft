package com.mannschaft.app.family.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 当番ローテーションレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class DutyRotationResponse {

    private final Long id;
    private final Long teamId;
    private final String dutyName;
    private final String rotationType;
    private final List<Long> memberOrder;
    private final LocalDate startDate;
    private final String icon;
    private final boolean isEnabled;
    private final Long todayAssignee;
    private final LocalDateTime createdAt;
}
