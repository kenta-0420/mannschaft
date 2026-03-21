package com.mannschaft.app.forms.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * フォーム提出値レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class SubmissionValueResponse {

    private final Long id;
    private final Long submissionId;
    private final String fieldKey;
    private final String fieldType;
    private final String textValue;
    private final BigDecimal numberValue;
    private final LocalDate dateValue;
    private final String fileKey;
    private final Boolean isAutoFilled;
    private final LocalDateTime createdAt;
}
