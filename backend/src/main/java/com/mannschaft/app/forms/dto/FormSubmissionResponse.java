package com.mannschaft.app.forms.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * フォーム提出レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class FormSubmissionResponse {

    private final Long id;
    private final Long templateId;
    private final String scopeType;
    private final Long scopeId;
    private final String status;
    private final Long submittedBy;
    private final Long workflowRequestId;
    private final String pdfFileKey;
    private final Integer submissionCountForUser;
    private final Long version;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
    private final List<SubmissionValueResponse> values;
}
