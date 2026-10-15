package com.mannschaft.app.chart.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * カルテテンプレート作成リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CreateRecordTemplateRequest {

    @NotBlank
    @Size(max = 100)
    private final String templateName;

    private final String chiefComplaint;

    private final String treatmentNote;

    private final String allergyInfo;

    private final String defaultCustomFields;

    private final Integer sortOrder;
}
