package com.mannschaft.app.forms.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * フォーム提出レスポンスDTO。
 */
@Builder(toBuilder = true)
@Getter
public class FormSubmissionResponse {

    Long id;
    String status;
    FormScopeDto scope;
    FormSubmissionMetaDto meta;
    FormSubmissionPdfDto pdf;
    FormSubmissionAuditDto audit;
    List<SubmissionValueResponse> values;

    public record FormScopeDto(String scopeType, Long scopeId) {}

    public record FormSubmissionMetaDto(Long templateId, Long submittedBy, Long workflowRequestId,
                                        Integer submissionCountForUser, Long version) {}

    public record FormSubmissionPdfDto(String pdfFileKey) {}

    public record FormSubmissionAuditDto(LocalDateTime createdAt, LocalDateTime updatedAt) {}
}
