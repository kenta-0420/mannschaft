package com.mannschaft.app.chart.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * カルテテンプレートレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class RecordTemplateResponse {

    private final Long id;
    private final String templateName;
    private final String chiefComplaint;
    private final String treatmentNote;
    private final String allergyInfo;
    private final String defaultCustomFields;
    private final Integer sortOrder;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
