package com.mannschaft.app.schedule.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * 年間行事コピーログレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class CopyLogResponse {

    private final Long id;

    private final Integer sourceAcademicYear;

    private final Integer targetAcademicYear;

    private final Integer totalCopied;

    private final Integer totalSkipped;

    private final String dateShiftMode;

    private final Long executedBy;

    private final LocalDateTime createdAt;
}
